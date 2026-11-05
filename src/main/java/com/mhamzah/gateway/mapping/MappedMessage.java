package com.mhamzah.gateway.mapping;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Output of applying a rule set: JSON body (an object, or an array when rules write one to {@code $}) plus string-valued
 * headers, query parameters and path variables, the HTTP status a STATUS rule set (null: none did) and the step whose
 * downloaded file a FILE rule sends (null: none did).
 */
public record MappedMessage(JsonNode body, Map<String, String> headers, Map<String, String> query,
        Map<String, String> pathVariables, Integer status, String fileStep) {

    public MappedMessage(JsonNode body, Map<String, String> headers, Map<String, String> query,
            Map<String, String> pathVariables, Integer status) {
        this(body, headers, query, pathVariables, status, null);
    }

    public MappedMessage(JsonNode body, Map<String, String> headers, Map<String, String> query,
            Map<String, String> pathVariables) {
        this(body, headers, query, pathVariables, null, null);
    }
}
