package com.mhamzah.gateway.config;

/**
 * Which of a flow's schemas are checked at run time ({@code gw_flow.schema_validation}). The schemas themselves stay
 * in use for mapping, OpenAPI and the API spec whatever the mode.
 */
public enum SchemaValidation {
    /** The client request and the responses (each step's, and the reply to the client). */
    BOTH,
    /** Only the client request ({@code request_schema_code}). */
    REQUEST,
    /** Only the responses: each step's {@code response_schema_code} and the flow's reply to the client. */
    RESPONSE,
    /** Nothing is checked. */
    NONE;

    public boolean request() {
        return this == BOTH || this == REQUEST;
    }

    public boolean response() {
        return this == BOTH || this == RESPONSE;
    }
}
