package com.mhamzah.gateway.config;

import com.mhamzah.gateway.condition.Condition;
import com.mhamzah.gateway.extension.BodyCodec;
import com.mhamzah.gateway.extension.MessageHandler;
import com.mhamzah.gateway.mapping.BodyTemplate;
import com.mhamzah.gateway.mapping.CompiledRule;
import com.mhamzah.gateway.schema.CompiledSchema;
import com.mhamzah.gateway.sql.SqlStatement;
import com.mhamzah.gateway.storage.FileStore;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpMethod;

/**
 * A compiled downstream call of a flow. Nullable components are optional features that are not configured.
 * {@code bodyCodec} is never null: the step's own, else its target system's, else {@code jsonCodec}.
 * A database query step has {@code sql} set and {@code targetSystem}, {@code method} and {@code pathTemplate} null;
 * {@code targetSystemName} is then its datasource. A file storage step has {@code fileStore} set (its
 * {@code targetSystemName}), {@code method} PUT (store) or DELETE, and {@code pathTemplate} the object key template.
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
        List<CompiledRule> requestRules,
        SqlStatement sql,
        FileStore fileStore,
        BodyTemplate bodyTemplate) {

    public StepDefinition {
        requestRules = List.copyOf(requestRules);
    }

    public boolean isStorage() {
        return fileStore != null;
    }

    public boolean isSql() {
        return sql != null;
    }

    /** {@code GET}, {@code POST}, ... or {@code SQL} for a database query step. */
    public String methodName() {
        return sql != null ? "SQL" : method.name();
    }
}
