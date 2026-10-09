package com.mhamzah.gateway.invoke;

import java.time.Duration;
import java.util.Map;
import javax.net.ssl.SSLContext;
import org.springframework.http.HttpMethod;

/**
 * A fully resolved downstream call.
 *
 * @param path encoded path plus query string, relative to {@code baseUrl}
 * @param headers every header to send, including Content-Type and Accept
 * @param body encoded body (JSON, XML, ...), or null to send none
 * @param sslContext TLS of an https target system (custom trust/key stores or trust-all), or null for the default
 */
public record DownstreamRequest(
        String targetSystem,
        String baseUrl,
        HttpMethod method,
        String path,
        Map<String, String> headers,
        String body,
        Duration connectTimeout,
        Duration readTimeout,
        SSLContext sslContext) {

    public DownstreamRequest {
        headers = Map.copyOf(headers);
    }

    public DownstreamRequest(String targetSystem, String baseUrl, HttpMethod method, String path,
            Map<String, String> headers, String body, Duration connectTimeout, Duration readTimeout) {
        this(targetSystem, baseUrl, method, path, headers, body, connectTimeout, readTimeout, null);
    }

    public String url() {
        return baseUrl + path;
    }
}
