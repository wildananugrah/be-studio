package com.mhamzah.gateway.config;

import com.mhamzah.gateway.extension.ErrorHandler;
import com.mhamzah.gateway.extension.MessageHandler;
import com.mhamzah.gateway.mapping.CompiledRule;
import com.mhamzah.gateway.schema.CompiledSchema;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.web.util.pattern.PathPattern;

/**
 * A compiled inbound endpoint. {@code groups} holds steps grouped by {@code step_order}, ascending;
 * steps in one group run in parallel. {@code responseRules} build the default response ({@code successStatus});
 * {@code responses} are the named ones a branch can answer with ({@code RESPOND:<name>}).
 */
public record FlowDefinition(
        String code,
        HttpMethod method,
        PathPattern pathPattern,
        CompiledSchema requestSchema,
        CompiledSchema responseSchema,
        MessageHandler requestHandler,
        MessageHandler responseHandler,
        ErrorHandler errorHandler,
        String errorHandlerName,
        int successStatus,
        Duration timeout,
        AuditMode auditMode,
        List<List<StepDefinition>> groups,
        List<CompiledRule> responseRules,
        java.util.Map<String, Response> responses) {

    /** A named client response ({@code gw_flow_response}) with its own status and FLOW_RESPONSE rules. */
    public record Response(String name, int status, List<CompiledRule> rules) {
        public Response {
            rules = List.copyOf(rules);
        }
    }

    public FlowDefinition(String code, HttpMethod method, PathPattern pathPattern, CompiledSchema requestSchema,
            CompiledSchema responseSchema, MessageHandler requestHandler, MessageHandler responseHandler,
            ErrorHandler errorHandler, String errorHandlerName, int successStatus, Duration timeout,
            AuditMode auditMode, List<List<StepDefinition>> groups, List<CompiledRule> responseRules) {
        this(code, method, pathPattern, requestSchema, responseSchema, requestHandler, responseHandler, errorHandler,
                errorHandlerName, successStatus, timeout, auditMode, groups, responseRules, java.util.Map.of());
    }

    public FlowDefinition {
        groups = groups.stream().map(List::copyOf).toList();
        responseRules = List.copyOf(responseRules);
        responses = responses == null ? java.util.Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(responses));
    }

    public List<StepDefinition> allSteps() {
        return groups.stream().flatMap(List::stream).toList();
    }
}
