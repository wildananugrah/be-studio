package com.mhamzah.gateway.extension;

import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/**
 * Mutable HTTP headers and JSON body of the message at a {@link MessageHandler} hook
 * (inbound request, outgoing step request, step response or final response).
 * Header names are case-insensitive. {@link #status()} is the HTTP status of the final response at the flow
 * {@code response_handler} hook, where {@link #setStatus} changes it, and of a step response (read-only there);
 * null elsewhere.
 */
public final class MessageView {

    private final Map<String, String> headers;
    private JsonNode body;
    private Integer status;

    public MessageView(Map<String, String> headers, JsonNode body) {
        this(headers, body, null);
    }

    public MessageView(Map<String, String> headers, JsonNode body, Integer status) {
        this.status = status;
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

    /** The HTTP status of the message, when it has one (see the class description). */
    public Integer status() {
        return status;
    }

    /** Changes the status of the final response; only the flow {@code response_handler} hook uses it. */
    public void setStatus(Integer status) {
        this.status = status;
    }

    public JsonNode body() {
        return body;
    }

    public void setBody(JsonNode body) {
        this.body = body;
    }
}
