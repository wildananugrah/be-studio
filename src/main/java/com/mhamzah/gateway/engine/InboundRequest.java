package com.mhamzah.gateway.engine;

import java.util.Map;
import org.springframework.http.HttpMethod;

/**
 * The client request as received.
 *
 * @param path path relative to the API base path
 * @param headers header names lower-cased, first value only
 * @param body raw body text; empty when there is none
 */
public record InboundRequest(HttpMethod method, String path, Map<String, String> headers, Map<String, String> query, String body) {

    public InboundRequest {
        headers = Map.copyOf(headers);
        query = Map.copyOf(query);
        body = body == null ? "" : body;
    }
}
