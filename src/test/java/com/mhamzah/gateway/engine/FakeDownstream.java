package com.mhamzah.gateway.engine;

import com.mhamzah.gateway.invoke.DownstreamClient;
import com.mhamzah.gateway.invoke.DownstreamException;
import com.mhamzah.gateway.invoke.DownstreamRequest;
import com.mhamzah.gateway.invoke.DownstreamResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Scripted downstream keyed by request path (without query). Records every request it receives. */
class FakeDownstream implements DownstreamClient {

    final List<DownstreamRequest> requests = new CopyOnWriteArrayList<>();
    final List<String> completed = new CopyOnWriteArrayList<>();
    private final Map<String, Function<DownstreamRequest, DownstreamResponse>> routes = new ConcurrentHashMap<>();

    FakeDownstream on(String path, Function<DownstreamRequest, DownstreamResponse> handler) {
        routes.put(path, handler);
        return this;
    }

    FakeDownstream ok(String path, String json) {
        return on(path, r -> new DownstreamResponse(200, Map.of("content-type", "application/json"), json));
    }

    FakeDownstream slow(String path, Duration delay, String json) {
        return on(path, r -> {
            sleep(delay);
            return new DownstreamResponse(200, Map.of(), json);
        });
    }

    static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownstreamException(DownstreamException.Kind.CONNECTION, "interrupted", e);
        }
    }

    DownstreamRequest last(String path) {
        return requests.stream().filter(r -> r.path().startsWith(path)).reduce((a, b) -> b).orElseThrow();
    }

    @Override
    public DownstreamResponse call(DownstreamRequest request) {
        requests.add(request);
        String path = request.path().split("\\?")[0];
        var handler = routes.get(path);
        if (handler == null) {
            return new DownstreamResponse(404, Map.of(), "{\"error\":\"no fake route " + path + "\"}");
        }
        DownstreamResponse response = handler.apply(request);
        completed.add(path);
        return response;
    }
}
