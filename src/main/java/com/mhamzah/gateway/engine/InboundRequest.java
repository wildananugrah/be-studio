package com.mhamzah.gateway.engine;

import com.mhamzah.gateway.extension.InboundFile;
import java.util.Map;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.node.ObjectNode;

/**
 * The client request as received.
 *
 * @param path path relative to the API base path
 * @param headers header names lower-cased, first value only
 * @param body raw body text; empty when there is none (and for forms and uploads)
 * @param form the fields of a form or multipart request, as the flow's {@code $.request.body}; null for JSON
 * @param files uploaded files by form field ({@code body} for a raw non-JSON upload)
 */
public record InboundRequest(HttpMethod method, String path, Map<String, String> headers, Map<String, String> query,
        String body, ObjectNode form, Map<String, InboundFile> files) {

    public InboundRequest {
        headers = Map.copyOf(headers);
        query = Map.copyOf(query);
        body = body == null ? "" : body;
        files = files == null ? Map.of() : Map.copyOf(files);
    }

    /** A JSON (or empty) request. */
    public InboundRequest(HttpMethod method, String path, Map<String, String> headers, Map<String, String> query, String body) {
        this(method, path, headers, query, body, null, Map.of());
    }
}
