package com.mhamzah.gateway.invoke;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link DownstreamClient} on the JDK {@link HttpClient}, which supports a read timeout per request
 * (the effective step timeout is capped by the remaining flow time, so it differs per call).
 * One client is kept per connect timeout.
 */
public class HttpDownstreamClient implements DownstreamClient {

    /** Headers the JDK client manages itself and refuses to set. */
    private static final Set<String> RESTRICTED = Set.of("connection", "content-length", "expect", "host", "upgrade");

    private final Map<Duration, HttpClient> clients = new ConcurrentHashMap<>();

    @Override
    public DownstreamResponse call(DownstreamRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url()))
                .timeout(request.readTimeout())
                .method(request.method().name(), request.body() == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8));
        request.headers().forEach((name, value) -> {
            if (!RESTRICTED.contains(name.toLowerCase(Locale.ROOT))) {
                builder.setHeader(name, value);
            }
        });
        HttpClient client = clients.computeIfAbsent(request.connectTimeout(), timeout -> HttpClient.newBuilder()
                .connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, String> headers = new LinkedHashMap<>();
            response.headers().map().forEach((k, v) -> {
                if (!k.startsWith(":") && !v.isEmpty()) {
                    headers.put(k.toLowerCase(Locale.ROOT), v.getFirst());
                }
            });
            return new DownstreamResponse(response.statusCode(), headers, response.body());
        } catch (HttpConnectTimeoutException e) {
            throw new DownstreamException(DownstreamException.Kind.CONNECTION, "Connect timed out: " + request.url(), e);
        } catch (HttpTimeoutException e) {
            throw new DownstreamException(DownstreamException.Kind.TIMEOUT, "Read timed out after "
                    + request.readTimeout().toMillis() + "ms: " + request.url(), e);
        } catch (ConnectException e) {
            throw new DownstreamException(DownstreamException.Kind.CONNECTION, "Connection refused: " + request.url(), e);
        } catch (IOException e) {
            throw new DownstreamException(DownstreamException.Kind.CONNECTION, "I/O error calling " + request.url()
                    + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownstreamException(DownstreamException.Kind.CONNECTION, "Interrupted calling " + request.url(), e);
        }
    }
}
