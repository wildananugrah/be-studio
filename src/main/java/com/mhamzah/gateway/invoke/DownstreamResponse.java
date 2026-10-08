package com.mhamzah.gateway.invoke;

import java.util.Map;

/** Raw downstream HTTP response. Header names are lower-case. */
public record DownstreamResponse(int status, Map<String, String> headers, String body) {

    public DownstreamResponse {
        headers = Map.copyOf(headers);
        body = body == null ? "" : body;
    }

    public boolean is2xx() {
        return status >= 200 && status < 300;
    }
}
