package com.mhamzah.gateway.extension;

import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/**
 * Mutable HTTP headers and JSON body of the message at a {@link MessageHandler} hook
 * (inbound request, outgoing step request, step response or final response).
 * Header names are case-insensitive.
 */
public final class MessageView {

    private final Map<String, String> headers;
    private JsonNode body;

    public MessageView(Map<String, String> headers, JsonNode body) {
        this.headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            this.headers.putAll(headers);
        }
        this.body = body;
    }

    /** Mutable, case-insensitive header map. */
    public Map<String, String> headers() {
        return headers;
    }

    public JsonNode body() {
        return body;
    }

    public void setBody(JsonNode body) {
        this.body = body;
    }
}
