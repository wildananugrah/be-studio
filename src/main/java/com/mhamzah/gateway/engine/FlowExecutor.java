package com.mhamzah.gateway.engine;

import com.mhamzah.gateway.audit.AuditRecord;
import com.mhamzah.gateway.audit.AuditRecord.StepRecord;
import com.mhamzah.gateway.config.FlowDefinition;
import com.mhamzah.gateway.config.FlowRegistry;
import com.mhamzah.gateway.config.FlowRegistry.RouteMatch;
import com.mhamzah.gateway.config.StepDefinition;
import com.mhamzah.gateway.engine.StepRunner.StepOutcome;
import com.mhamzah.gateway.extension.ErrorHandler;
import com.mhamzah.gateway.extension.ErrorType;
import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.extension.GatewayError;
import com.mhamzah.gateway.extension.GatewayResponse;
import com.mhamzah.gateway.extension.MessageHandler;
import com.mhamzah.gateway.extension.MessageView;
import com.mhamzah.gateway.invoke.DownstreamClient;
import com.mhamzah.gateway.mapping.JsonValues;
import com.mhamzah.gateway.mapping.MappedMessage;
import com.mhamzah.gateway.mapping.MappingEngine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Runs a matched flow end to end (spec Section 7) and produces the client response and its audit record. */
public class FlowExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FlowExecutor.class);
    private static final JsonMapper MAPPER = JsonValues.MAPPER;
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final MappingEngine mapping;
    private final ErrorHandler defaultErrorHandler;
    private final StepRunner stepRunner;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public FlowExecutor(DownstreamClient client, MappingEngine mapping, ErrorHandler defaultErrorHandler) {
        this.mapping = mapping;
        this.defaultErrorHandler = defaultErrorHandler;
        this.stepRunner = new StepRunner(client, mapping);
    }

    public ExecutionResult execute(FlowRegistry registry, RouteMatch match, InboundRequest in, String correlationId) {
        FlowDefinition flow = match.flow();
        Instant startedAt = Instant.now();
        long start = System.nanoTime();
        long deadline = start + flow.timeout().toNanos();
        ExecutionContext ctx = new ExecutionContext(flow.code(), correlationId, registry.lookups(),
                requestNode(in, match.pathVariables()), in.files());
        List<StepRecord> records = Collections.synchronizedList(new ArrayList<>());

        GatewayResponse response;
        GatewayError failure = null;
        try {
            response = run(flow, in, ctx, deadline, records);
        } catch (GatewayError e) {
            failure = e;
            response = handleFlowError(flow, e, ctx);
        } catch (RuntimeException e) {
            log.error("Unexpected failure in flow {}", flow.code(), e);
            failure = GatewayError.of(ErrorType.INTERNAL).cause(e).build();
            response = handleFlowError(flow, failure, ctx);
        }
        if (failure != null) {
            log.warn("flow={} failed: {} {} (step={})", flow.code(), failure.type(), failure.getMessage(), failure.stepName());
        }
        return finish(response, failure, ctx, in, startedAt, start, sorted(records, flow));
    }

    /** Response for a request that matched no flow. */
    public ExecutionResult notFound(InboundRequest in, String correlationId) {
        Instant startedAt = Instant.now();
        long start = System.nanoTime();
        ExecutionContext ctx = new ExecutionContext(null, correlationId, Map.of(), requestNode(in, Map.of()));
        GatewayError error = GatewayError.of(ErrorType.ROUTE_NOT_FOUND)
                .details(List.of(in.method() + " " + in.path())).build();
        return finish(handleError(defaultErrorHandler, error, ctx), error, ctx, in, startedAt, start, List.of());
    }

    private GatewayResponse run(FlowDefinition flow, InboundRequest in, ExecutionContext ctx, long deadline,
            List<StepRecord> records) {
        ObjectNode request = ctx.request();
        request.set("body", in.form() != null ? in.form() : parseInbound(in.body()));
        if (flow.requestSchema() != null && flow.schemaValidation().request()) {
            List<String> errors = flow.requestSchema().validate(request.get("body"));
            if (!errors.isEmpty()) {
                throw GatewayError.of(ErrorType.REQUEST_SCHEMA_INVALID).details(errors).build();
            }
        }
        if (flow.requestHandler() != null) {
            Map<String, String> headers = new LinkedHashMap<>();
            request.get("headers").properties().forEach(e -> headers.put(e.getKey(), e.getValue().asString()));
            MessageView view = new MessageView(headers, request.get("body"));
            GatewayResponse shortCircuit = invoke(flow.requestHandler(), view, ctx, "request_handler of flow", true);
            if (shortCircuit != null) {
                return shortCircuit;
            }
            ObjectNode newHeaders = F.objectNode();
            view.headers().forEach((k, v) -> newHeaders.put(k.toLowerCase(Locale.ROOT), v));
            request.set("headers", newHeaders);
            request.set("body", view.body() == null ? F.objectNode() : view.body());
        }

        // next_on_success / next_on_failure: a branching step runs alone in its group and picks what runs next
        String jumpTo = null;
        boolean ended = false;
        FlowDefinition.Response respond = null;
        GatewayError routedFailure = null;
        for (List<StepDefinition> group : flow.groups()) {
            String target = jumpTo;
            if (ended || (target != null && group.stream().noneMatch(s -> s.name().equals(target)))) {
                group.forEach(s -> skip(s, ctx, records)); // not on the path taken
                continue;
            }
            jumpTo = null;
            StepOutcome branch = runGroup(group, ctx, deadline, records);
            if (branch == null) {
                continue;
            }
            String route = branch.route() != null ? branch.route()
                    : branch.error() == null ? branch.step().nextOnSuccess() : branch.step().nextOnFailure();
            if (branch.error() != null && route != null) {
                routedFailure = branch.error();
            }
            if (route == null) {
                continue;
            }
            switch (route) {
                case StepDefinition.END -> ended = true;
                case StepDefinition.FAIL -> throw routedFailure != null ? routedFailure
                        : GatewayError.of(ErrorType.DOWNSTREAM_BUSINESS_ERROR).step(branch.step().name())
                                .message("Flow ended on the failure path after step '" + branch.step().name() + "'").build();
                default -> {
                    if (route.startsWith(StepDefinition.RESPOND)) {
                        ended = true;
                        respond = flow.responses().get(route.substring(StepDefinition.RESPOND.length()));
                    } else {
                        jumpTo = route;
                    }
                }
            }
        }
        if (respond != null) {
            // a named response: its own rules and status; the response schema describes the default one only
            MappedMessage named = mapping.apply(respond.rules(), ctx);
            MessageView view = new MessageView(named.headers(), named.body());
            if (flow.responseHandler() != null) {
                invoke(flow.responseHandler(), view, ctx, "response_handler of flow", false);
            }
            // a STATUS rule (e.g. the backend's own status) wins over the response's fixed status
            return new GatewayResponse(named.status() != null ? named.status() : respond.status(), view.headers(), view.body());
        }

        MappedMessage out = mapping.apply(flow.responseRules(), ctx);
        if (flow.responseSchema() != null && flow.schemaValidation().response()) {
            List<String> errors = flow.responseSchema().validate(out.body());
            if (!errors.isEmpty()) {
                throw GatewayError.of(ErrorType.RESPONSE_SCHEMA_INVALID).details(errors).build();
            }
        }
        MessageView view = new MessageView(out.headers(), out.body());
        if (flow.responseHandler() != null) {
            invoke(flow.responseHandler(), view, ctx, "response_handler of flow", false);
        }
        return new GatewayResponse(out.status() != null ? out.status() : flow.successStatus(), view.headers(), view.body());
    }

    private static void skip(StepDefinition step, ExecutionContext ctx, List<StepRecord> records) {
        ctx.putStepResult(step.name(), F.objectNode().put("outcome", "SKIPPED"));
        records.add(new StepRecord(step.name(), step.targetSystemName(), step.methodName(), null, null,
                "SKIPPED", null, null, null, null));
    }

    /** Runs a group; returns the outcome of its branching step (which runs alone), else null. */
    private StepOutcome runGroup(List<StepDefinition> group, ExecutionContext ctx, long deadline, List<StepRecord> records) {
        if (System.nanoTime() >= deadline) {
            throw GatewayError.of(ErrorType.FLOW_TIMEOUT).build();
        }
        List<StepDefinition> toRun = new ArrayList<>();
        for (StepDefinition step : group) {
            boolean run;
            try {
                run = step.condition() == null || step.condition().evaluate(ctx);
            } catch (GatewayError e) {
                throw GatewayError.of(e.type()).step(step.name()).message(e.getMessage()).details(e.details()).build();
            }
            if (run) {
                toRun.add(step);
            } else {
                skip(step, ctx, records);
            }
        }
        if (toRun.isEmpty()) {
            return null;
        }

        CompletionService<StepOutcome> completion = new ExecutorCompletionService<>(executor);
        Map<Future<StepOutcome>, StepDefinition> pending = new IdentityHashMap<>();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        for (StepDefinition step : toRun) {
            pending.put(completion.submit(() -> withMdc(mdc, () -> stepRunner.run(step, ctx, deadline))), step);
        }

        List<StepOutcome> stops = new ArrayList<>();
        StepOutcome branch = null;
        boolean timedOut = false;
        try {
            while (!pending.isEmpty() && stops.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                Future<StepOutcome> done = remaining > 0 ? completion.poll(remaining, TimeUnit.NANOSECONDS) : null;
                if (done == null) {
                    timedOut = true;
                    break;
                }
                pending.remove(done);
                StepOutcome outcome = result(done);
                records.add(outcome.record());
                if (outcome.step().branches()) {
                    branch = outcome;
                }
                if (outcome.stopsFlow()) {
                    stops.add(outcome);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            timedOut = true;
        }

        // Cancel what is still running; collect STOP failures that completed meanwhile.
        String cancelledOutcome = timedOut ? "TIMEOUT" : "CANCELLED";
        for (var e : pending.entrySet()) {
            Future<StepOutcome> f = e.getKey();
            if (f.isDone() && !f.isCancelled()) {
                StepOutcome outcome = result(f);
                records.add(outcome.record());
                if (outcome.stopsFlow()) {
                    stops.add(outcome);
                }
            } else {
                f.cancel(true);
                StepDefinition s = e.getValue();
                records.add(new StepRecord(s.name(), s.targetSystemName(), s.methodName(), null, null,
                        cancelledOutcome, null, null, null, null));
            }
        }
        if (!stops.isEmpty()) {
            throw stops.stream().min(Comparator.comparing(o -> o.step().name())).orElseThrow().error();
        }
        if (timedOut) {
            throw GatewayError.of(ErrorType.FLOW_TIMEOUT).build();
        }
        return branch;
    }

    private static StepOutcome result(Future<StepOutcome> f) {
        try {
            return f.get();
        } catch (ExecutionException e) {
            throw GatewayError.of(ErrorType.INTERNAL).cause(e.getCause()).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw GatewayError.of(ErrorType.INTERNAL).cause(e).build();
        }
    }

    private static <T> T withMdc(Map<String, String> mdc, java.util.concurrent.Callable<T> work) throws Exception {
        if (mdc != null) {
            MDC.setContextMap(mdc);
        }
        try {
            return work.call();
        } finally {
            MDC.clear();
        }
    }

    /** Calls a message handler, converting unexpected exceptions into HANDLER_ERROR. */
    static GatewayResponse invoke(MessageHandler handler, MessageView view, ExecutionContext ctx, String what,
            boolean mayShortCircuit) {
        GatewayResponse r;
        try {
            r = handler.handle(view, ctx);
        } catch (GatewayError e) {
            throw e;
        } catch (RuntimeException e) {
            throw GatewayError.of(ErrorType.HANDLER_ERROR).message("Failed: " + what).cause(e).build();
        }
        if (r != null && !mayShortCircuit) {
            log.warn("Ignoring response returned by {}; short-circuit is only honored at the flow request hook", what);
            return null;
        }
        return r;
    }

    /** Flow error handler, then the default handler, then a hard-coded 500. Never throws. */
    /** The flow's error handler, or with {@code error_handler = RESPOND:<response>} that response mapped from the error. */
    private GatewayResponse handleFlowError(FlowDefinition flow, GatewayError error, ExecutionContext ctx) {
        String name = flow.errorHandlerName();
        if (name == null || !name.startsWith(StepDefinition.RESPOND)) {
            return handleError(flow.errorHandler(), error, ctx);
        }
        // what the standard error response would be: its status and code are part of $.error
        GatewayResponse standard = handleError(defaultErrorHandler, error, ctx);
        FlowDefinition.Response respond = flow.responses().get(name.substring(StepDefinition.RESPOND.length()));
        if (respond == null) {
            return standard;
        }
        ObjectNode e = F.objectNode();
        e.put("type", error.type().name());
        e.put("code", standard.body() == null ? error.type().defaultCode() : standard.body().path("errorCode").asString());
        e.put("status", standard.status());
        e.put("message", standard.body() == null ? error.type().defaultMessage() : standard.body().path("errorMessage").asString());
        e.put("reason", error.getMessage());
        if (error.stepName() != null) {
            e.put("step", error.stepName());
        }
        var details = e.putArray("details");
        error.details().forEach(details::add);
        ctx.putError(e);
        try {
            MappedMessage out = mapping.apply(respond.rules(), ctx);
            return new GatewayResponse(out.status() != null ? out.status() : respond.status(), out.headers(), out.body());
        } catch (RuntimeException ex) {
            log.error("Error response '{}' of flow {} could not be built; sending the standard error: {}",
                    respond.name(), flow.code(), ex.getMessage());
            return standard;
        }
    }

    private GatewayResponse handleError(ErrorHandler handler, GatewayError error, ExecutionContext ctx) {
        if (handler != null && handler != defaultErrorHandler) {
            try {
                GatewayResponse r = handler.handle(error, ctx);
                if (r != null) {
                    return r;
                }
            } catch (RuntimeException e) {
                log.error("Error handler failed; falling back to default", e);
            }
        }
        try {
            return defaultErrorHandler.handle(error, ctx);
        } catch (RuntimeException e) {
            log.error("Default error handler failed", e);
            ObjectNode body = F.objectNode();
            body.put("errorCode", ErrorType.INTERNAL.defaultCode());
            body.put("errorMessage", ErrorType.INTERNAL.defaultMessage());
            body.put("correlationId", ctx.correlationId());
            return GatewayResponse.of(500, body);
        }
    }

    /** What the client receives: the response headers plus the JSON content type the gateway always sends. */
    private static Map<String, String> clientHeaders(GatewayResponse response) {
        Map<String, String> out = new LinkedHashMap<>();
        if (response.headers().keySet().stream().noneMatch("Content-Type"::equalsIgnoreCase)) {
            out.put("Content-Type", "application/json");
        }
        out.putAll(response.headers());
        return out;
    }

    private ExecutionResult finish(GatewayResponse response, GatewayError failure, ExecutionContext ctx,
            InboundRequest in, Instant startedAt, long start, List<StepRecord> records) {
        Map<String, String> headers = new LinkedHashMap<>(response.headers());
        headers.put(CORRELATION_HEADER, ctx.correlationId());
        GatewayResponse finalResponse = new GatewayResponse(response.status(), headers, response.body());
        String errorCode = failure == null || response.body() == null ? null
                : response.body().path("errorCode").asString(null);
        JsonNode requestBody = ctx.request().get("body");
        if (!in.files().isEmpty()) {
            // uploads: the form fields plus each file's description (never the content) for the audit trail
            ObjectNode withFiles = F.objectNode();
            withFiles.set("body", requestBody == null ? F.objectNode() : requestBody);
            withFiles.set("files", ctx.request().get("files"));
            requestBody = withFiles;
        }
        AuditRecord audit = new AuditRecord(ctx.correlationId(), ctx.flowCode(), in.method().name(), in.path(),
                finalResponse.status(), failure == null ? null : failure.type().name(), errorCode,
                requestBody != null ? requestBody : F.stringNode(in.body()), finalResponse.body(), startedAt,
                (System.nanoTime() - start) / 1_000_000, records, new java.util.TreeMap<>(in.headers()), clientHeaders(finalResponse));
        return new ExecutionResult(finalResponse, audit);
    }

    private static List<StepRecord> sorted(List<StepRecord> records, FlowDefinition flow) {
        Map<String, Integer> order = new LinkedHashMap<>();
        flow.allSteps().forEach(s -> order.put(s.name(), order.size()));
        synchronized (records) {
            return records.stream().sorted(Comparator.comparing(r -> order.getOrDefault(r.stepName(), 0))).toList();
        }
    }

    private static ObjectNode requestNode(InboundRequest in, Map<String, String> pathVariables) {
        ObjectNode request = F.objectNode();
        ObjectNode headers = request.putObject("headers");
        in.headers().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v));
        ObjectNode path = request.putObject("path");
        pathVariables.forEach(path::put);
        ObjectNode query = request.putObject("query");
        in.query().forEach(query::put);
        ObjectNode files = request.putObject("files");
        in.files().forEach((field, file) -> files.set(field, file.describe()));
        return request;
    }

    private static JsonNode parseInbound(String body) {
        if (body == null || body.isBlank()) {
            return F.objectNode();
        }
        try {
            return MAPPER.readTree(body);
        } catch (JacksonException e) {
            throw GatewayError.of(ErrorType.INVALID_JSON).details(List.of(e.getOriginalMessage())).build();
        }
    }

    @Override
    public void close() {
        executor.close();
    }
}
