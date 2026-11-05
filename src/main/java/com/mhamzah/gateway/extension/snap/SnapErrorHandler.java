package com.mhamzah.gateway.extension.snap;

import com.mhamzah.gateway.config.FlowDefinition;
import com.mhamzah.gateway.config.FlowRegistry;
import com.mhamzah.gateway.config.FlowRegistryHolder;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.config.StepDefinition;
import com.mhamzah.gateway.extension.ErrorHandler;
import com.mhamzah.gateway.extension.ErrorType;
import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.extension.GatewayError;
import com.mhamzah.gateway.extension.GatewayResponse;
import com.mhamzah.gateway.mapping.JsonPath;
import com.mhamzah.gateway.mapping.JsonValues;
import com.mhamzah.gateway.mapping.LookupTable;
import com.mhamzah.gateway.mapping.MappingEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * SNAP error responses:
 * {@code {"responseCode": "4032414", "responseMessage": "Insufficient Funds",
 * "additionalInfo": {...}}}. Set
 * {@code gw_flow.error_handler = snapErrorHandler}; everything else is rows.
 *
 * <ul>
 * <li>A downstream failure ({@code DOWNSTREAM_HTTP_ERROR} /
 * {@code DOWNSTREAM_BUSINESS_ERROR}) is translated by the
 * lookup {@code SNAP_ERR_<target system>} of the failed step. Its code is read
 * from the paths in the row
 * {@code @codePath} (default {@code $.body.errorCode}, {@code $.body.code},
 * {@code $.body.responseCode}); numbers
 * and text match the same row ({@code 30} = {@code "30"}). Keys are tried in
 * order: {@code <status>:<code>},
 * {@code <code>}, {@code HTTP_<status>}, then the table's {@code *} row.</li>
 * <li>Anything else, or a downstream failure without a match, goes through
 * {@code SNAP_GATEWAY_ERROR} by error type
 * ({@code REQUEST_SCHEMA_MISSING} and {@code MAPPING_ERROR_CLIENT} for the
 * client's mistakes), with built-in
 * defaults.</li>
 * <li>A row's value is a SNAP code ({@code "403.14"}) or
 * {@code {"snap": "403.14", "message": "...", "reason":
 *       "...", "messagePath": "$.body.message", "httpStatus": 200}}:
 * {@code message} replaces the catalog text,
 * {@code reason} fills {@code [reason]}, {@code messagePath} takes the text
 * from the failed call ({@code
 *       {status, headers, body}}; when it is missing or empty {@code message} /
 * the catalog are used), and {@code
 *       httpStatus} sends another HTTP status than the SNAP code's
 * ({@code responseCode} keeps the SNAP one). The
 * downstream's own message is copied only through {@code messagePath}.</li>
 * <li>A status that cannot carry a body (204, 205, 304, 1xx) is sent as
 * {@code gateway.snap.no-body-status}
 * (200).</li>
 * <li>{@code additionalInfo} is optional: it is sent only when the flow has the
 * named response {@value
 * #ADDITIONAL_INFO} and its BODY rules produce at least one field (they can
 * read {@code $.error.*}, {@code
 *       $.steps.*} — the failed call's {@code body} included — and the
 * request). The same response builds it for
 * success responses ({@link SnapResponseHandler}).</li>
 * </ul>
 * It never throws: when something goes wrong it answers 500 Internal Server
 * Error in the same format.
 */
@Component(SnapErrorHandler.BEAN_NAME)
public class SnapErrorHandler implements ErrorHandler {

    public static final String BEAN_NAME = "snapErrorHandler";
    /**
     * Name of the flow's named response whose rules build {@code additionalInfo}.
     */
    public static final String ADDITIONAL_INFO = SnapAdditionalInfo.FIELD;

    private static final Logger log = LoggerFactory.getLogger(SnapErrorHandler.class);
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final List<String> DEFAULT_CODE_PATHS = List.of("$.body.errorCode", "$.body.code",
            "$.body.responseCode");
    private static final Map<String, String> GATEWAY_DEFAULTS = Map.ofEntries(
            Map.entry("ROUTE_NOT_FOUND", "404.02"),
            Map.entry("INVALID_JSON", "400.00"),
            Map.entry("REQUEST_SCHEMA_INVALID", "400.01"),
            Map.entry("REQUEST_SCHEMA_MISSING", "400.02"),
            Map.entry("MAPPING_ERROR_CLIENT", "400.02"),
            Map.entry("MAPPING_ERROR", "500.00"),
            Map.entry("FILE_TOO_LARGE", "400.01"),
            Map.entry("FILE_REJECTED", "400.01"),
            Map.entry("DOWNSTREAM_HTTP_ERROR", "500.02"),
            Map.entry("DOWNSTREAM_BUSINESS_ERROR", "500.02"),
            Map.entry("DOWNSTREAM_CONNECTION", "500.02"),
            Map.entry("DOWNSTREAM_INVALID_RESPONSE", "500.02"),
            Map.entry("DOWNSTREAM_TIMEOUT", "504.00"),
            Map.entry("FLOW_TIMEOUT", "504.00"),
            Map.entry("DATABASE_ERROR", "500.02"),
            Map.entry("STORAGE_ERROR", "500.02"),
            Map.entry("HANDLER_ERROR", "500.01"),
            Map.entry("RESPONSE_SCHEMA_INVALID", "500.01"),
            Map.entry("INTERNAL", "500.01"));
    /** A field named in a mapping error, e.g. {@code $.request.body.amount}. */
    private static final Pattern REQUEST_FIELD = Pattern
            .compile("\\$\\.request\\.(?:body|query|path|headers)\\.([A-Za-z0-9_.\\[\\]-]+)");
    private static final Pattern REQUIRED_PROPERTY = Pattern.compile("required property '([^']+)'");

    private final Supplier<FlowRegistry> registry;
    private final MappingEngine mapping;
    private final int noBodyStatus;

    @Autowired
    public SnapErrorHandler(ObjectProvider<FlowRegistryHolder> holder, ObjectProvider<MappingEngine> mapping,
            ObjectProvider<GatewayProperties> properties) {
        this(() -> holder.getObject().current(), mapping.getIfAvailable(MappingEngine::new), noBodyStatus(properties));
    }

    public SnapErrorHandler(Supplier<FlowRegistry> registry, MappingEngine mapping) {
        this(registry, mapping, 200);
    }

    public SnapErrorHandler(Supplier<FlowRegistry> registry, MappingEngine mapping, int noBodyStatus) {
        this.registry = registry;
        this.mapping = mapping;
        this.noBodyStatus = noBodyStatus;
    }

    static int noBodyStatus(ObjectProvider<GatewayProperties> properties) {
        GatewayProperties p = properties.getIfAvailable();
        return p == null || p.snap() == null ? 200 : p.snap().noBodyStatus();
    }

    /**
     * What a lookup row says: a SNAP code, optionally with its own message, reason,
     * path to the downstream's message
     * and HTTP status.
     */
    private record Choice(String key, String message, String reason, String messagePath, Integer httpStatus) {
        Choice(String key) {
            this(key, null, null, null, null);
        }
    }

    @Override
    public GatewayResponse handle(GatewayError error, ExecutionContext ctx) {
        try {
            FlowDefinition flow = flow(ctx.flowCode());
            Choice choice = choose(error, ctx, flow);
            SnapCodes.Code code = SnapCodes.find(choice.key(), ctx);
            if (code == null) {
                log.warn("'{}' is not a SNAP code (expected e.g. 403.14); answering 500.01", choice.key());
                code = SnapCodes.require("500.01", ctx);
                choice = new Choice("500.01");
            }
            String message = downstreamMessage(choice, error);
            if (message == null) {
                message = SnapCodes.fill(choice.message() != null ? choice.message() : code.message(),
                        field(error), choice.reason());
            }
            String responseCode = code.responseCode(ctx.serviceCode());
            ObjectNode body = F.objectNode();
            body.put("responseCode", responseCode);
            body.put("responseMessage", message);
            ObjectNode info = additionalInfo(flow, error, code, responseCode, message, ctx);
            if (info != null) {
                body.set(ADDITIONAL_INFO, info);
            }
            int status = choice.httpStatus() != null ? choice.httpStatus() : code.status();
            return GatewayResponse.of(SnapCodes.sendable(status, noBodyStatus, ctx.flowCode()), body);
        } catch (RuntimeException e) {
            log.error("SNAP error response could not be built; answering 500", e);
            ObjectNode body = F.objectNode();
            body.put("responseCode", "500" + SnapCodes.responseServiceCode(ctx.serviceCode()) + "01");
            body.put("responseMessage", "Internal Server Error");
            return GatewayResponse.of(500, body);
        }
    }

    private Choice choose(GatewayError error, ExecutionContext ctx, FlowDefinition flow) {
        boolean downstream = error.type() == ErrorType.DOWNSTREAM_HTTP_ERROR
                || error.type() == ErrorType.DOWNSTREAM_BUSINESS_ERROR;
        String target = downstream ? targetOf(flow, error.stepName()) : null;
        LookupTable table = target == null ? null : ctx.lookupTable(SnapCodes.DOWNSTREAM_PREFIX + target).orElse(null);
        if (table != null) {
            Choice c = fromDownstream(table, error);
            if (c != null) {
                return c;
            }
        }
        String key = gatewayKey(error);
        LookupTable gateway = ctx.lookupTable(SnapCodes.GATEWAY_ERRORS).orElse(null);
        JsonNode row = gateway == null ? null : gateway.entries().get(key);
        if (row == null && gateway != null) {
            row = gateway.fallback();
        }
        return row != null ? choice(row) : new Choice(GATEWAY_DEFAULTS.getOrDefault(key, "500.01"));
    }

    /**
     * {@code <status>:<code>}, {@code <code>}, {@code HTTP_<status>}, then
     * {@code *}; null when nothing matches.
     */
    private static Choice fromDownstream(LookupTable table, GatewayError error) {
        ObjectNode result = stepResult(error);
        String code = null;
        for (String path : codePaths(table)) {
            JsonNode value = JsonPath.compile(path).read(result);
            if (!JsonValues.isAbsent(value) && value.isValueNode()) {
                code = JsonValues.keyOf(value).strip();
                break;
            }
        }
        Integer status = error.downstreamStatus();
        List<String> keys = new ArrayList<>();
        if (status != null && code != null) {
            keys.add(status + ":" + code);
        }
        if (code != null) {
            keys.add(code);
        }
        if (status != null) {
            keys.add("HTTP_" + status);
        }
        for (String k : keys) {
            JsonNode row = table.entries().get(k);
            if (row != null) {
                return choice(row);
            }
        }
        return table.fallback() == null ? null : choice(table.fallback());
    }

    private static List<String> codePaths(LookupTable table) {
        JsonNode paths = table.entries().get(SnapCodes.CODE_PATH_KEY);
        if (paths == null) {
            return DEFAULT_CODE_PATHS;
        }
        if (paths.isArray()) {
            List<String> out = new ArrayList<>();
            paths.forEach(p -> out.add(p.asString()));
            return out;
        }
        return List.of(paths.asString());
    }

    private static Choice choice(JsonNode row) {
        if (row.isObject()) {
            return new Choice(SnapCodes.key(row.get("snap")), text(row.get("message")), text(row.get("reason")),
                    text(row.get("messagePath")), httpStatus(row.get("httpStatus")));
        }
        return new Choice(SnapCodes.key(row));
    }

    /**
     * A row's {@code httpStatus}: 100-599, as a number or text; anything else is
     * ignored with a warning.
     */
    private static Integer httpStatus(JsonNode n) {
        if (JsonValues.isAbsent(n)) {
            return null;
        }
        try {
            int status = n.isNumber() ? n.asInt() : Integer.parseInt(n.asString().strip());
            if (status >= 100 && status <= 599) {
                return status;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the warning
        }
        log.warn("httpStatus '{}' of a SNAP row is not an HTTP status (100-599); using the SNAP code's", n);
        return null;
    }

    /**
     * The text at the row's {@code messagePath} in the failed call; null when it
     * has none or it is empty.
     */
    private static String downstreamMessage(Choice choice, GatewayError error) {
        if (choice.messagePath() == null || choice.messagePath().isBlank()) {
            return null;
        }
        try {
            JsonNode value = JsonPath.compile(choice.messagePath()).read(stepResult(error));
            if (!JsonValues.isAbsent(value) && value.isValueNode() && !JsonValues.keyOf(value).isBlank()) {
                return JsonValues.keyOf(value).strip();
            }
        } catch (RuntimeException e) {
            log.warn("messagePath '{}' of a SNAP row could not be read: {}", choice.messagePath(), e.getMessage());
        }
        return null;
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? null : n.asString();
    }

    private static String gatewayKey(GatewayError error) {
        if (error.type() == ErrorType.REQUEST_SCHEMA_INVALID
                && error.details().stream().anyMatch(d -> d.contains("required"))) {
            return "REQUEST_SCHEMA_MISSING";
        }
        if (error.type() == ErrorType.MAPPING_ERROR && error.clientError()) {
            return "MAPPING_ERROR_CLIENT";
        }
        return error.type().name();
    }

    /**
     * The field a client error is about, for {@code {field name}}; null when it
     * cannot be told.
     */
    static String field(GatewayError error) {
        if (error.type() == ErrorType.REQUEST_SCHEMA_INVALID && !error.details().isEmpty()) {
            String detail = error.details().stream().filter(d -> d.contains("required")).findFirst()
                    .orElse(error.details().getFirst());
            int colon = detail.indexOf(": ");
            String location = colon < 0 ? "$" : detail.substring(0, colon);
            String path = location.equals("$") ? "" : location.replaceFirst("^\\$\\.?", "");
            Matcher m = REQUIRED_PROPERTY.matcher(detail);
            if (m.find()) {
                return path.isEmpty() ? m.group(1) : path + "." + m.group(1);
            }
            return path.isEmpty() ? null : path;
        }
        if (error.type() == ErrorType.MAPPING_ERROR && error.clientError()) {
            for (String text : texts(error)) {
                Matcher m = REQUEST_FIELD.matcher(text);
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return null;
    }

    private static List<String> texts(GatewayError error) {
        List<String> out = new ArrayList<>(error.details());
        if (error.getMessage() != null) {
            out.add(error.getMessage());
        }
        return out;
    }

    /**
     * {@code additionalInfo} from the flow's named response
     * {@value #ADDITIONAL_INFO}; null when it has none.
     */
    private ObjectNode additionalInfo(FlowDefinition flow, GatewayError error, SnapCodes.Code code, String responseCode,
            String message, ExecutionContext ctx) {
        if (flow == null || !flow.responses().containsKey(ADDITIONAL_INFO)) {
            return null;
        }
        ObjectNode e = F.objectNode();
        e.put("type", error.type().name());
        e.put("code", responseCode);
        e.put("status", code.status());
        e.put("message", message);
        e.put("reason", error.getMessage());
        if (error.stepName() != null) {
            e.put("step", error.stepName());
        }
        var details = e.putArray("details");
        error.details().forEach(details::add);
        ctx.putError(e);
        return SnapAdditionalInfo.build(flow, mapping, ctx);
    }

    private FlowDefinition flow(String code) {
        return SnapAdditionalInfo.flow(registry, code);
    }

    private static String targetOf(FlowDefinition flow, String stepName) {
        if (flow == null || stepName == null) {
            return null;
        }
        return flow.allSteps().stream().filter(s -> s.name().equals(stepName)).findFirst()
                .map(StepDefinition::targetSystemName).orElse(null);
    }

    /**
     * The failed call as a step result:
     * {@code {"status": ..., "headers": {...}, "body": ...}}.
     */
    private static ObjectNode stepResult(GatewayError error) {
        ObjectNode result = F.objectNode();
        if (error.downstreamStatus() != null) {
            result.put("status", error.downstreamStatus());
        }
        ObjectNode headers = result.putObject("headers");
        error.downstreamHeaders().forEach(headers::put);
        result.set("body", error.downstreamBody());
        return result;
    }
}