package com.mhamzah.gateway.engine;

import com.mhamzah.gateway.audit.AuditRecord.StepRecord;
import com.mhamzah.gateway.codec.JsonCodec;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.config.OnFailure;
import com.mhamzah.gateway.config.StepDefinition;
import com.mhamzah.gateway.extension.BodyCodec;
import com.mhamzah.gateway.extension.ErrorType;
import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.extension.GatewayError;
import com.mhamzah.gateway.extension.MessageView;
import com.mhamzah.gateway.invoke.DownstreamClient;
import com.mhamzah.gateway.invoke.DownstreamException;
import com.mhamzah.gateway.invoke.DownstreamRequest;
import com.mhamzah.gateway.invoke.DownstreamResponse;
import com.mhamzah.gateway.mapping.MappedMessage;
import com.mhamzah.gateway.mapping.MappingEngine;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs one step (spec Section 7, item 6.3): request mapping, request handler, HTTP call, response handler,
 * response schema, store into context, success expression. Never throws; failures are returned in the outcome.
 */
final class StepRunner {

    /** Result of one step. {@code error} is null on success. */
    record StepOutcome(StepDefinition step, GatewayError error, StepRecord record) {
        boolean stopsFlow() {
            return error != null && step.onFailure() == OnFailure.STOP;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(StepRunner.class);
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{([^}/]+)}");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final DownstreamClient client;
    private final MappingEngine mapping;

    StepRunner(DownstreamClient client, MappingEngine mapping) {
        this.client = client;
        this.mapping = mapping;
    }

    StepOutcome run(StepDefinition step, ExecutionContext ctx, long deadlineNanos) {
        Attempt a = new Attempt(step);
        try {
            call(step, ctx, deadlineNanos, a);
            return a.outcome(null);
        } catch (GatewayError e) {
            ctx.putStepResult(step.name(), a.result(e));
            return a.outcome(e);
        } catch (RuntimeException e) {
            log.error("Step '{}' failed unexpectedly", step.name(), e);
            GatewayError err = GatewayError.of(ErrorType.INTERNAL).step(step.name()).cause(e).build();
            ctx.putStepResult(step.name(), a.result(err));
            return a.outcome(err);
        }
    }

    private void call(StepDefinition step, ExecutionContext ctx, long deadlineNanos, Attempt a) {
        GatewayProperties.TargetSystem target = step.targetSystem();
        MappedMessage mapped = withStep(step, () -> mapping.apply(step.requestRules(), ctx));

        Map<String, String> headers = new LinkedHashMap<>(target.staticHeaders());
        headers.putAll(mapped.headers());
        // the handler must see exactly what will be sent (no body for GET), e.g. so signatures match
        MessageView request = new MessageView(headers, sendsBody(step.method(), mapped.body()) ? mapped.body() : null);
        if (step.requestHandler() != null) {
            FlowExecutor.invoke(step.requestHandler(), request, ctx, "request_handler of step '" + step.name() + "'", false);
        }
        BodyCodec codec = step.bodyCodec();
        JsonNode body = sendsBody(step.method(), request.body()) ? request.body() : null;
        a.requestPayload = body;
        String wireBody = null;
        if (body != null) {
            wireBody = encode(step, body, request.headers(), ctx);
            request.headers().putIfAbsent("Content-Type", codec.contentType());
        }
        request.headers().putIfAbsent("Accept", codec.accept());
        Map<String, String> outHeaders = new LinkedHashMap<>(request.headers());
        outHeaders.put("X-Correlation-Id", ctx.correlationId());

        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw GatewayError.of(ErrorType.FLOW_TIMEOUT).step(step.name()).build();
        }
        boolean cappedByFlow = remainingNanos < step.timeout().toNanos();
        Duration readTimeout = cappedByFlow ? Duration.ofNanos(remainingNanos) : step.timeout();
        DownstreamRequest req = new DownstreamRequest(step.targetSystemName(), stripSlash(target.baseUrl()),
                step.method(), path(step.pathTemplate(), mapped.pathVariables(), mapped.query()), outHeaders, wireBody,
                Duration.ofMillis(target.connectTimeoutMs()), readTimeout);
        a.url = req.url();

        DownstreamResponse resp;
        try {
            resp = client.call(req);
        } catch (DownstreamException e) {
            a.outcome = e.kind() == DownstreamException.Kind.TIMEOUT ? "TIMEOUT" : "FAILED";
            ErrorType type = e.kind() == DownstreamException.Kind.CONNECTION ? ErrorType.DOWNSTREAM_CONNECTION
                    : cappedByFlow ? ErrorType.FLOW_TIMEOUT : ErrorType.DOWNSTREAM_TIMEOUT;
            throw GatewayError.of(type).step(step.name()).message(e.getMessage()).cause(e).build();
        } finally {
            a.finishCall();
        }
        a.status = resp.status();
        Decoded decoded = decode(codec, resp);
        JsonNode respBody = decoded.body();
        a.headers = resp.headers();
        if (!resp.is2xx()) {
            a.responsePayload = respBody != null ? respBody : F.stringNode(resp.body());
            throw GatewayError.of(ErrorType.DOWNSTREAM_HTTP_ERROR).step(step.name())
                    .message("Downstream returned HTTP " + resp.status())
                    .downstream(resp.status(), resp.headers(), a.responsePayload).build();
        }
        if (respBody == null) {
            a.responsePayload = F.stringNode(resp.body());
            String message = codec == JsonCodec.INSTANCE ? "Downstream response is not JSON"
                    : "Downstream response could not be decoded by body_codec '" + step.bodyCodecName() + "': "
                            + decoded.error();
            throw GatewayError.of(ErrorType.DOWNSTREAM_INVALID_RESPONSE).step(step.name())
                    .message(message)
                    .downstream(resp.status(), resp.headers(), a.responsePayload).build();
        }
        MessageView response = new MessageView(resp.headers(), respBody);
        if (step.responseHandler() != null) {
            FlowExecutor.invoke(step.responseHandler(), response, ctx, "response_handler of step '" + step.name() + "'", false);
        }
        a.headers = lowerCase(response.headers());
        a.responsePayload = response.body();
        if (step.responseSchema() != null) {
            List<String> errors = step.responseSchema().validate(response.body());
            if (!errors.isEmpty()) {
                throw GatewayError.of(ErrorType.DOWNSTREAM_INVALID_RESPONSE).step(step.name())
                        .message("Downstream response failed schema " + step.responseSchema().code())
                        .details(errors).downstream(resp.status(), a.headers, response.body()).build();
            }
        }
        a.outcome = "SUCCESS";
        ctx.putStepResult(step.name(), a.result(null));
        if (step.success() != null && !withStep(step, () -> step.success().evaluate(ctx))) {
            a.outcome = "FAILED";
            throw GatewayError.of(ErrorType.DOWNSTREAM_BUSINESS_ERROR).step(step.name())
                    .message("success_expr of step '" + step.name() + "' is false")
                    .downstream(resp.status(), a.headers, response.body()).build();
        }
    }

    /** Attaches the step name to errors raised by mapping or expressions. */
    private static <T> T withStep(StepDefinition step, java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (GatewayError e) {
            if (e.stepName() != null) {
                throw e;
            }
            throw GatewayError.of(e.type()).step(step.name()).message(e.getMessage()).details(e.details())
                    .clientError(e.clientError()).cause(e.getCause()).build();
        }
    }

    private static boolean sendsBody(HttpMethod method, JsonNode body) {
        if (body == null || method == HttpMethod.GET) {
            return false;
        }
        return method != HttpMethod.DELETE || !body.isEmpty();
    }

    static String path(String template, Map<String, String> pathVariables, Map<String, String> query) {
        Matcher m = TEMPLATE_VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = pathVariables.getOrDefault(m.group(1), "");
            m.appendReplacement(sb, Matcher.quoteReplacement(UriUtils.encodePathSegment(value, StandardCharsets.UTF_8)));
        }
        m.appendTail(sb);
        if (!query.isEmpty()) {
            StringJoiner q = new StringJoiner("&", "?", "");
            query.forEach((k, v) -> q.add(UriUtils.encodeQueryParam(k, StandardCharsets.UTF_8) + "="
                    + UriUtils.encodeQueryParam(v, StandardCharsets.UTF_8)));
            sb.append(q);
        }
        return sb.toString();
    }

    private static String stripSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** The request body in the step's wire format; a body the codec cannot encode is a mapping error. */
    private static String encode(StepDefinition step, JsonNode body, Map<String, String> headers, ExecutionContext ctx) {
        try {
            return step.bodyCodec().encode(body, headers, ctx);
        } catch (GatewayError e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw GatewayError.of(ErrorType.MAPPING_ERROR).step(step.name())
                    .message("body_codec '" + step.bodyCodecName() + "' cannot encode the request body: " + e.getMessage())
                    .cause(e).build();
        } catch (RuntimeException e) {
            log.error("body_codec '{}' of step '{}' failed to encode", step.bodyCodecName(), step.name(), e);
            throw GatewayError.of(ErrorType.HANDLER_ERROR).step(step.name()).cause(e).build();
        }
    }

    /** A decoded response body, or null with the reason when the codec could not decode it. */
    private record Decoded(JsonNode body, String error) {}

    /** Decoded body ({} when empty). */
    private static Decoded decode(BodyCodec codec, DownstreamResponse resp) {
        if (resp.body().isBlank()) {
            return new Decoded(F.objectNode(), null);
        }
        try {
            JsonNode body = codec.decode(resp.body(), resp.headers());
            return body == null ? new Decoded(null, "decoded to null") : new Decoded(body, null);
        } catch (RuntimeException e) {
            return new Decoded(null, e.getMessage());
        }
    }

    private static Map<String, String> lowerCase(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((k, v) -> out.put(k.toLowerCase(java.util.Locale.ROOT), v));
        return out;
    }

    /** Mutable record of one attempt, turned into the context result and the audit record. */
    private static final class Attempt {
        final StepDefinition step;
        final Instant startedAt = Instant.now();
        final long startNanos = System.nanoTime();
        Long durationMs;
        String url;
        Integer status;
        String outcome = "FAILED";
        Map<String, String> headers = Map.of();
        JsonNode requestPayload;
        JsonNode responsePayload;

        Attempt(StepDefinition step) {
            this.step = step;
        }

        void finishCall() {
            durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        }

        ObjectNode result(GatewayError error) {
            ObjectNode r = F.objectNode();
            r.put("outcome", outcome);
            if (status != null) {
                r.put("status", status);
            }
            ObjectNode h = r.putObject("headers");
            headers.forEach(h::put);
            r.set("body", responsePayload == null ? F.objectNode() : responsePayload);
            if (error != null) {
                r.put("errorType", error.type().name());
            }
            return r;
        }

        StepOutcome outcome(GatewayError error) {
            if (durationMs == null) {
                finishCall();
            }
            log.info("step={} target={} {} {} status={} outcome={} {}ms", step.name(), step.targetSystemName(),
                    step.method(), url, status, outcome, durationMs);
            StepRecord record = new StepRecord(step.name(), step.targetSystemName(), step.method().name(), url,
                    status, outcome, requestPayload, responsePayload, startedAt, durationMs);
            return new StepOutcome(step, error, record);
        }
    }
}
