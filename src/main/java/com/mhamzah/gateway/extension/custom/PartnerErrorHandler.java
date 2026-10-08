package com.mhamzah.gateway.extension.custom;

import com.mhamzah.gateway.extension.ErrorHandler;
import com.mhamzah.gateway.extension.ErrorType;
import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.extension.GatewayError;
import com.mhamzah.gateway.extension.GatewayResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Example {@link ErrorHandler} written from scratch (for simple code translation prefer {@code LookupErrorHandler}).
 * Some partners expect HTTP 200 for everything, with an ISO-8583-style response code in the body:
 * <pre>
 *   30 Format error            invalid JSON / schema / missing request field
 *   68 Response received too late   downstream or flow timeout
 *   xx (downstream code)       business / HTTP error carrying {"responseCode": "xx"} from core
 *   96 System malfunction      everything else
 * </pre>
 * Use it on a flow: {@code gw_flow.error_handler = 'partnerErrorHandler'}.
 */
@Component("partnerErrorHandler")
public class PartnerErrorHandler implements ErrorHandler {

    @Override
    public GatewayResponse handle(GatewayError error, ExecutionContext ctx) {
        String code;
        String message;
        JsonNode downstreamCode = error.downstreamBody() == null ? null : error.downstreamBody().get("responseCode");

        if (isClientError(error)) {
            code = "30";
            message = "Format error";
        } else if (error.type() == ErrorType.DOWNSTREAM_TIMEOUT || error.type() == ErrorType.FLOW_TIMEOUT) {
            code = "68";
            message = "Response received too late";
        } else if ((error.type() == ErrorType.DOWNSTREAM_BUSINESS_ERROR || error.type() == ErrorType.DOWNSTREAM_HTTP_ERROR)
                && downstreamCode != null && downstreamCode.isString()) {
            code = downstreamCode.stringValue();
            message = error.downstreamBody().path("responseMessage").asString("Transaction failed");
        } else {
            code = "96";
            message = "System malfunction";
        }

        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("responseCode", code);
        body.put("responseMessage", message);
        body.put("correlationId", ctx.correlationId());
        if (!error.details().isEmpty()) {
            ArrayNode details = body.putArray("details");
            error.details().forEach(details::add);
        }
        return GatewayResponse.of(200, body);
    }

    private static boolean isClientError(GatewayError error) {
        return error.type() == ErrorType.INVALID_JSON
                || error.type() == ErrorType.REQUEST_SCHEMA_INVALID
                || error.type() == ErrorType.MAPPING_ERROR && error.clientError();
    }
}
