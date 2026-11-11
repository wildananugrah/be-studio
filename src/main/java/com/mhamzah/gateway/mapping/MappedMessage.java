package com.mhamzah.gateway.mapping;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Output of applying a rule set: JSON body (an object, or an array when rules write one to {@code $}) plus string-valued
 * headers, query parameters and path variables, the HTTP status a STATUS rule set (null: none did) and the step whose
 * downloaded file a FILE rule sends (null: none did). {@code removedHeaders}: the header names REMOVE rules named, in lower
 * case, to drop from headers added after mapping (a target system's defaults, a file response's own).
 */
public record MappedMessage(JsonNode body, Map<String, String> headers, Map<String, String> query,
        Map<String, String> pathVariables, Integer status, String fileStep, java.util.Set<String> removedHeaders) {

    public MappedMessage {
        removedHeaders = removedHeaders == null ? java.util.Set.of() : java.util.Set.copyOf(removedHeaders);
    }

    public MappedMessage(JsonNode body, Map<String, String> headers, Map<String, String> query,
            Map<String, String> pathVariables, Integer status, String fileStep) {
        this(body, headers, query, pathVariables, status, fileStep, null);
    }

    /** Drops the headers REMOVE rules named (case-insensitive) from {@code headers}, e.g. a target system's defaults. */
    public void removeHeadersFrom(Map<String, String> headers) {
        if (!removedHeaders.isEmpty()) {
            headers.keySet().removeIf(h -> removedHeaders.contains(h.toLowerCase(java.util.Locale.ROOT)));
        }
    }

    public MappedMessage(JsonNode body, Map<String, String> headers, Map<String, String> query,
            Map<String, String> pathVariables, Integer status) {
        this(body, headers, query, pathVariables, status, null);
    }

    public MappedMessage(JsonNode body, Map<String, String> headers, Map<String, String> query,
            Map<String, String> pathVariables) {
        this(body, headers, query, pathVariables, null, null);
    }
}
