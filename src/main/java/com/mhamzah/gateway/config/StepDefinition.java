package com.mhamzah.gateway.config;

import com.mhamzah.gateway.condition.Condition;
import com.mhamzah.gateway.extension.BodyCodec;
import com.mhamzah.gateway.extension.MessageHandler;
import com.mhamzah.gateway.mapping.CompiledRule;
import com.mhamzah.gateway.schema.CompiledSchema;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpMethod;

/**
 * A compiled downstream call of a flow. Nullable components are optional features that are not configured.
 * {@code bodyCodec} is never null: the step's own, else its target system's, else {@code jsonCodec}.
 */
public record StepDefinition(
        String name,
        int order,
        String targetSystemName,
        GatewayProperties.TargetSystem targetSystem,
        HttpMethod method,
        String pathTemplate,
        Condition condition,
        Condition success,
        OnFailure onFailure,
        Duration timeout,
        CompiledSchema responseSchema,
        MessageHandler requestHandler,
        MessageHandler responseHandler,
        BodyCodec bodyCodec,
        String bodyCodecName,
        List<CompiledRule> requestRules) {

    public StepDefinition {
        requestRules = List.copyOf(requestRules);
    }
}
