package com.mhamzah.gateway.extension;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * HTTP status, headers and JSON body returned to the client; or, when {@code file} is set (a FILE rule), that file
 * as the body ({@code body} then holds its description, for the audit trail).
 */
public record GatewayResponse(int status, Map<String, String> headers, JsonNode body, InboundFile file) {

    public GatewayResponse {
        headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
    }

    public GatewayResponse(int status, Map<String, String> headers, JsonNode body) {
        this(status, headers, body, null);
    }

    public static GatewayResponse of(int status, JsonNode body) {
        return new GatewayResponse(status, Map.of(), body);
    }
}
