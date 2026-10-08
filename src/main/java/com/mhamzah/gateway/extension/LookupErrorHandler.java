package com.mhamzah.gateway.extension;

import com.mhamzah.gateway.mapping.JsonPath;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Base class for the common "translate the downstream error code" case. A subclass says where the
 * downstream code is and which lookup table translates it; the rest is configuration rows in
 * {@code gw_lookup_entry} whose target value is a JSON object:
 *
 * <pre>{"status": 422, "errorCode": "INSUFFICIENT_FUNDS", "errorMessage": "Insufficient balance"}</pre>
 *
 * Applies to {@link ErrorType#DOWNSTREAM_HTTP_ERROR} and {@link ErrorType#DOWNSTREAM_BUSINESS_ERROR};
 * anything else, or an unmatched code, is handled by {@link DefaultErrorHandler}.
 */
public abstract class LookupErrorHandler implements ErrorHandler {

    private final DefaultErrorHandler fallback = new DefaultErrorHandler();
    private volatile JsonPath compiledPath;

    /**
     * Path of the downstream error code within the failed step's result
     * ({@code {"status": ..., "headers": {...}, "body": {...}}}), e.g. {@code $.body.responseCode}.
     */
    protected abstract String errorCodePath();

    /** {@code gw_lookup_entry.lookup_code} of the table that translates the downstream code. */
    protected abstract String lookupCode();

    /** {@link #lookupCode()}, readable outside subclasses (Gateway Studio shows the table next to the handler). */
    public final String translatingLookupCode() {
        return lookupCode();
    }

    /** {@link #errorCodePath()}, readable outside subclasses. */
    public final String downstreamCodePath() {
        return errorCodePath();
    }

    @Override
    public GatewayResponse handle(GatewayError error, ExecutionContext ctx) {
        if (error.type() != ErrorType.DOWNSTREAM_HTTP_ERROR && error.type() != ErrorType.DOWNSTREAM_BUSINESS_ERROR) {
            return fallback.handle(error, ctx);
        }
        JsonNode code = path().read(stepResult(error));
        Optional<JsonNode> mapped = code == null ? Optional.empty() : ctx.lookup(lookupCode(), code);
        if (mapped.isEmpty() || !mapped.get().isObject()) {
            return fallback.handle(error, ctx);
        }
        JsonNode m = mapped.get();
        int status = m.path("status").asInt(error.type().defaultStatus());
        String errorCode = m.path("errorCode").asString(error.type().defaultCode());
        String errorMessage = m.path("errorMessage").asString(error.type().defaultMessage());
        return GatewayResponse.of(status, buildBody(errorCode, errorMessage, code, error, ctx));
    }

    /** Builds the client body; override to change its shape. */
    protected ObjectNode buildBody(String errorCode, String errorMessage, JsonNode downstreamCode,
            GatewayError error, ExecutionContext ctx) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("errorCode", errorCode);
        body.put("errorMessage", errorMessage);
        body.put("correlationId", ctx.correlationId());
        return body;
    }

    private static ObjectNode stepResult(GatewayError error) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        if (error.downstreamStatus() != null) {
            result.put("status", error.downstreamStatus());
        }
        ObjectNode headers = result.putObject("headers");
        error.downstreamHeaders().forEach(headers::put);
        result.set("body", error.downstreamBody());
        return result;
    }

    private JsonPath path() {
        JsonPath p = compiledPath;
        if (p == null) {
            p = JsonPath.compile(errorCodePath());
            compiledPath = p;
        }
        return p;
    }
}
