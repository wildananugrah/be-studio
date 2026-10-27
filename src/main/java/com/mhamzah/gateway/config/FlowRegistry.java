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

/**
 * Immutable snapshot of all compiled configuration. Replaced as a whole on reload. {@code applications} and
 * {@code flowApplications} (flow code to application code) group the flows, e.g. in the API documentation.
 */
public record FlowRegistry(
        List<FlowDefinition> flows,
        Map<String, LookupTable> lookups,
        Map<String, ResolvedTarget> targetSystems,
        Instant loadedAt,
        List<Application> applications,
        Map<String, String> flowApplications) {

    public FlowRegistry {
        flows = List.copyOf(flows);
        lookups = Map.copyOf(lookups);
        targetSystems = Map.copyOf(targetSystems);
        applications = applications == null ? List.of() : List.copyOf(applications);
        flowApplications = flowApplications == null ? Map.of() : Map.copyOf(flowApplications);
    }

    public FlowRegistry(List<FlowDefinition> flows, Map<String, LookupTable> lookups,
            Map<String, ResolvedTarget> targetSystems, Instant loadedAt) {
        this(flows, lookups, targetSystems, loadedAt, List.of(), Map.of());
    }

    /** A {@code gw_application}: the group a flow belongs to. */
    public record Application(String code, String name, String description) {}

    /** The application code of a flow; null when it has none. */
    public String applicationOf(FlowDefinition flow) {
        return flowApplications.get(flow.code());
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
