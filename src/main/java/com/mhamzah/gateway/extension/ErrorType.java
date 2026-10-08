package com.mhamzah.gateway.extension;

/** Every failure the gateway can produce, with its default HTTP status and error code (spec Section 9). */
public enum ErrorType {
    ROUTE_NOT_FOUND(404, "GW-404-ROUTE", "No route matches the request"),
    INVALID_JSON(400, "GW-400-JSON", "Request body is not valid JSON"),
    REQUEST_SCHEMA_INVALID(400, "GW-400-SCHEMA", "Request validation failed"),
    /** Default is the server-side variant; a mapping that fails on a {@code $.request.*} source is a 400 (see {@link GatewayError#clientError()}). */
    MAPPING_ERROR(500, "GW-500-MAPPING", "Message mapping failed"),
    DOWNSTREAM_HTTP_ERROR(502, "GW-502-DOWNSTREAM", "Downstream call failed"),
    DOWNSTREAM_BUSINESS_ERROR(422, "GW-422-BUSINESS", "Downstream reported a business error"),
    DOWNSTREAM_CONNECTION(502, "GW-502-CONNECTION", "Downstream system is unreachable"),
    DOWNSTREAM_INVALID_RESPONSE(502, "GW-502-INVALID-RESPONSE", "Downstream returned an invalid response"),
    DOWNSTREAM_TIMEOUT(504, "GW-504-DOWNSTREAM", "Downstream call timed out"),
    FLOW_TIMEOUT(504, "GW-504-FLOW", "Request processing timed out"),
    HANDLER_ERROR(500, "GW-500-HANDLER", "Custom handler failed"),
    RESPONSE_SCHEMA_INVALID(500, "GW-500-RESPONSE-SCHEMA", "Response validation failed"),
    INTERNAL(500, "GW-500-INTERNAL", "Internal gateway error");

    private final int defaultStatus;
    private final String defaultCode;
    private final String defaultMessage;

    ErrorType(int defaultStatus, String defaultCode, String defaultMessage) {
        this.defaultStatus = defaultStatus;
        this.defaultCode = defaultCode;
        this.defaultMessage = defaultMessage;
    }

    public int defaultStatus() {
        return defaultStatus;
    }

    public String defaultCode() {
        return defaultCode;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
