package com.mhamzah.gateway.extension;

import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Standard error response used when a flow has no {@code error_handler}, when no flow matched,
 * and as the fallback for custom error handlers.
 *
 * <pre>{ "errorCode", "errorMessage", "correlationId", "step"?, "details"? }</pre>
 */
@Component(DefaultErrorHandler.BEAN_NAME)
public class DefaultErrorHandler implements ErrorHandler {

    public static final String BEAN_NAME = "defaultErrorHandler";

    @Override
    public GatewayResponse handle(GatewayError error, ExecutionContext ctx) {
        boolean client = error.type() == ErrorType.MAPPING_ERROR && error.clientError();
        int status = client ? 400 : error.type().defaultStatus();
        String code = client ? "GW-400-MAPPING" : error.type().defaultCode();
        return GatewayResponse.of(status, body(code, error.type().defaultMessage(), error, ctx));
    }

    /** Builds the standard error body; reusable by custom handlers. */
    public static ObjectNode body(String errorCode, String errorMessage, GatewayError error, ExecutionContext ctx) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("errorCode", errorCode);
        body.put("errorMessage", errorMessage);
        body.put("correlationId", ctx.correlationId());
        if (error.stepName() != null) {
            body.put("step", error.stepName());
        }
        if (!error.details().isEmpty()) {
            ArrayNode details = body.putArray("details");
            error.details().forEach(details::add);
        }
        return body;
    }
}
