package com.mhamzah.gateway.config;

import com.mhamzah.gateway.mapping.LookupTable;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;

/** Immutable snapshot of all compiled configuration. Replaced as a whole on reload. */
public record FlowRegistry(
        List<FlowDefinition> flows,
        Map<String, LookupTable> lookups,
        Map<String, ResolvedTarget> targetSystems,
        Instant loadedAt) {

    public FlowRegistry {
        flows = List.copyOf(flows);
        lookups = Map.copyOf(lookups);
        targetSystems = Map.copyOf(targetSystems);
    }

    public record RouteMatch(FlowDefinition flow, Map<String, String> pathVariables) {}

    /** Finds the most specific enabled flow for {@code method} and {@code path} (relative to the API base path). */
    public Optional<RouteMatch> match(HttpMethod method, String path) {
        PathContainer container = PathContainer.parsePath(path);
        return flows.stream()
                .filter(f -> f.method().equals(method) && f.pathPattern().matches(container))
                .min(Comparator.comparing(FlowDefinition::pathPattern, PathPattern.SPECIFICITY_COMPARATOR))
                .map(f -> new RouteMatch(f, f.pathPattern().matchAndExtract(container).getUriVariables()));
    }
}
