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
 * steps in one group run in parallel.
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
        List<CompiledRule> responseRules) {

    public FlowDefinition {
        groups = groups.stream().map(List::copyOf).toList();
        responseRules = List.copyOf(responseRules);
    }

    public List<StepDefinition> allSteps() {
        return groups.stream().flatMap(List::stream).toList();
    }
}
