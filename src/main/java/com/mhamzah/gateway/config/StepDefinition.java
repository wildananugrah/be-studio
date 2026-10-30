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
 * of a later step. {@code validateResponse}: whether {@code responseSchema} is checked (the step's response_validation,
 * else its flow's schema_validation).
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
        String nextOnFailure,
        List<Case> cases,
        boolean validateResponse) {

    /**
     * One case of a condition step: when {@code when} is true (the first true case wins), the flow goes to
     * {@code route} (a later step, {@link #END}, {@link #FAIL} or {@link #RESPOND}{@code <name>}).
     */
    public record Case(String label, Condition when, String route) {}

    /** The reserved {@code target_system} of a condition step: no call; its cases choose where the flow goes. */
    public static final String CONDITION = "CONDITION";

    /** Route: stop running steps and build the flow's response. */
    public static final String END = "END";
    /** Route (on success): end the flow with the error of the failure that was routed to this branch. */
    public static final String FAIL = "FAIL";
    /**
     * The reserved {@code target_system} of a transform step: no downstream call; its BODY rules build its result
     * ({@code $.steps.<name>.body}) from the request and the earlier steps, for the steps after it and the response.
     */
    public static final String TRANSFORM = "TRANSFORM";
    /** Route prefix: stop running steps and answer with the flow's named response, {@code RESPOND:<name>}. */
    public static final String RESPOND = "RESPOND:";

    public StepDefinition {
        requestRules = List.copyOf(requestRules);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public StepDefinition(String name, int order, String targetSystemName, GatewayProperties.TargetSystem targetSystem,
            HttpMethod method, String pathTemplate, Condition condition, Condition success, OnFailure onFailure,
            Duration timeout, CompiledSchema responseSchema, MessageHandler requestHandler,
            MessageHandler responseHandler, BodyCodec bodyCodec, String bodyCodecName, List<CompiledRule> requestRules,
            SqlStatement sql, FileStore fileStore, BodyTemplate bodyTemplate, String nextOnSuccess, String nextOnFailure) {
        this(name, order, targetSystemName, targetSystem, method, pathTemplate, condition, success, onFailure, timeout,
                responseSchema, requestHandler, responseHandler, bodyCodec, bodyCodecName, requestRules, sql, fileStore,
                bodyTemplate, nextOnSuccess, nextOnFailure, List.of(), true);
    }

    public StepDefinition(String name, int order, String targetSystemName, GatewayProperties.TargetSystem targetSystem,
            HttpMethod method, String pathTemplate, Condition condition, Condition success, OnFailure onFailure,
            Duration timeout, CompiledSchema responseSchema, MessageHandler requestHandler,
            MessageHandler responseHandler, BodyCodec bodyCodec, String bodyCodecName, List<CompiledRule> requestRules,
            SqlStatement sql, FileStore fileStore, BodyTemplate bodyTemplate, String nextOnSuccess, String nextOnFailure,
            List<Case> cases) {
        this(name, order, targetSystemName, targetSystem, method, pathTemplate, condition, success, onFailure, timeout,
                responseSchema, requestHandler, responseHandler, bodyCodec, bodyCodecName, requestRules, sql, fileStore,
                bodyTemplate, nextOnSuccess, nextOnFailure, cases, true);
    }

    /** A condition step: checks its cases and routes; calls nothing. */
    public boolean isCondition() {
        return sql == null && fileStore == null && targetSystem == null && method == null
                && CONDITION.equals(targetSystemName);
    }

    public StepDefinition(String name, int order, String targetSystemName, GatewayProperties.TargetSystem targetSystem,
            HttpMethod method, String pathTemplate, Condition condition, Condition success, OnFailure onFailure,
            Duration timeout, CompiledSchema responseSchema, MessageHandler requestHandler,
            MessageHandler responseHandler, BodyCodec bodyCodec, String bodyCodecName, List<CompiledRule> requestRules,
            SqlStatement sql, FileStore fileStore, BodyTemplate bodyTemplate) {
        this(name, order, targetSystemName, targetSystem, method, pathTemplate, condition, success, onFailure, timeout,
                responseSchema, requestHandler, responseHandler, bodyCodec, bodyCodecName, requestRules, sql, fileStore,
                bodyTemplate, null, null, List.of(), true);
    }

    public StepDefinition withNext(String onSuccess, String onFailureRoute) {
        return new StepDefinition(name, order, targetSystemName, targetSystem, method, pathTemplate, condition, success,
                onFailure, timeout, responseSchema, requestHandler, responseHandler, bodyCodec, bodyCodecName,
                requestRules, sql, fileStore, bodyTemplate, onSuccess, onFailureRoute, cases, validateResponse);
    }

    public StepDefinition withCases(List<Case> value) {
        return new StepDefinition(name, order, targetSystemName, targetSystem, method, pathTemplate, condition, success,
                onFailure, timeout, responseSchema, requestHandler, responseHandler, bodyCodec, bodyCodecName,
                requestRules, sql, fileStore, bodyTemplate, nextOnSuccess, nextOnFailure, value, validateResponse);
    }

    public StepDefinition withValidateResponse(boolean value) {
        return new StepDefinition(name, order, targetSystemName, targetSystem, method, pathTemplate, condition, success,
                onFailure, timeout, responseSchema, requestHandler, responseHandler, bodyCodec, bodyCodecName,
                requestRules, sql, fileStore, bodyTemplate, nextOnSuccess, nextOnFailure, cases, value);
    }

    /** True when the step chooses where the flow goes next (it then runs alone in its step_order group). */
    public boolean branches() {
        return nextOnSuccess != null || nextOnFailure != null || !cases.isEmpty();
    }

    public boolean isStorage() {
        return fileStore != null;
    }

    public boolean isSql() {
        return sql != null;
    }

    /** A transform step: re-maps what the flow has so far into its own result, without calling anything. */
    public boolean isTransform() {
        return sql == null && fileStore == null && targetSystem == null && method == null
                && TRANSFORM.equals(targetSystemName);
    }

    /** {@code GET}, {@code POST}, ..., {@code SQL} for a database query step, {@code TRANSFORM} for a transform. */
    public String methodName() {
        return sql != null ? "SQL" : method == null ? targetSystemName : method.name();
    }
}
