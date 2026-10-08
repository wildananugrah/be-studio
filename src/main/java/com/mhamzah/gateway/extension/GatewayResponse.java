package com.mhamzah.gateway.extension;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** HTTP status, headers and JSON body returned to the client. */
public record GatewayResponse(int status, Map<String, String> headers, JsonNode body) {

    public GatewayResponse {
        headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
    }

    public static GatewayResponse of(int status, JsonNode body) {
        return new GatewayResponse(status, Map.of(), body);
    }
}
