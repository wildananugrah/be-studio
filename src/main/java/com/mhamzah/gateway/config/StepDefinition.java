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
 * {@code nextOnSuccess} / {@code nextOnFailure} route the flow after this step: null (the next group; on failure
 * {@code onFailure} decides), {@link #END}, {@link #FAIL} (success only), {@link #RESPOND}{@code <name>} or the name
 * of a later step.
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
        BodyTemplate bodyTemplate,
        String nextOnSuccess,
        String nextOnFailure) {

    /** Route: stop running steps and build the flow's response. */
    public static final String END = "END";
    /** Route (on success): end the flow with the error of the failure that was routed to this branch. */
    public static final String FAIL = "FAIL";
    /** Route prefix: stop running steps and answer with the flow's named response, {@code RESPOND:<name>}. */
    public static final String RESPOND = "RESPOND:";

    public StepDefinition {
        requestRules = List.copyOf(requestRules);
    }

    public StepDefinition(String name, int order, String targetSystemName, GatewayProperties.TargetSystem targetSystem,
            HttpMethod method, String pathTemplate, Condition condition, Condition success, OnFailure onFailure,
            Duration timeout, CompiledSchema responseSchema, MessageHandler requestHandler,
            MessageHandler responseHandler, BodyCodec bodyCodec, String bodyCodecName, List<CompiledRule> requestRules,
            SqlStatement sql, FileStore fileStore, BodyTemplate bodyTemplate) {
        this(name, order, targetSystemName, targetSystem, method, pathTemplate, condition, success, onFailure, timeout,
                responseSchema, requestHandler, responseHandler, bodyCodec, bodyCodecName, requestRules, sql, fileStore,
                bodyTemplate, null, null);
    }

    public StepDefinition withNext(String onSuccess, String onFailureRoute) {
        return new StepDefinition(name, order, targetSystemName, targetSystem, method, pathTemplate, condition, success,
                onFailure, timeout, responseSchema, requestHandler, responseHandler, bodyCodec, bodyCodecName,
                requestRules, sql, fileStore, bodyTemplate, onSuccess, onFailureRoute);
    }

    /** True when the step chooses where the flow goes next (it then runs alone in its step_order group). */
    public boolean branches() {
        return nextOnSuccess != null || nextOnFailure != null;
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
