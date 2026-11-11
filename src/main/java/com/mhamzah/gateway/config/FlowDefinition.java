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
 * {@code responses} are the named ones a branch can answer with ({@code RESPOND:<name>}). {@code schemaValidation}
 * says whether {@code requestSchema} / {@code responseSchema} are checked; each step resolves its own
 * ({@link StepDefinition#validateResponse()}). {@code serviceCode} is the flow's SNAP service code
 * ({@code gw_flow.service_code}). {@code requestValidation} holds the request checks and the schema picked by a field
 * ({@code gw_flow.request_validation}); null when not set.
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
        java.util.Map<String, Response> responses,
        SchemaValidation schemaValidation,
        String serviceCode,
        com.mhamzah.gateway.validation.RequestValidation requestValidation) {

    public FlowDefinition(String code, HttpMethod method, PathPattern pathPattern, CompiledSchema requestSchema,
            CompiledSchema responseSchema, MessageHandler requestHandler, MessageHandler responseHandler,
            ErrorHandler errorHandler, String errorHandlerName, int successStatus, Duration timeout,
            AuditMode auditMode, List<List<StepDefinition>> groups, List<CompiledRule> responseRules,
            java.util.Map<String, Response> responses, SchemaValidation schemaValidation, String serviceCode) {
        this(code, method, pathPattern, requestSchema, responseSchema, requestHandler, responseHandler, errorHandler,
                errorHandlerName, successStatus, timeout, auditMode, groups, responseRules, responses, schemaValidation,
                serviceCode, null);
    }

    /**
     * Reserved named response: its BODY rules build the optional {@code additionalInfo} of SNAP responses
     * ({@code snapResponseHandler}, {@code snapErrorHandler}); it is never a client response itself, so nothing routes
     * to it.
     */
    public static final String ADDITIONAL_INFO_RESPONSE = "additionalInfo";

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
                errorHandlerName, successStatus, timeout, auditMode, groups, responseRules, java.util.Map.of(), null, null);
    }

    public FlowDefinition {
        groups = groups.stream().map(List::copyOf).toList();
        responseRules = List.copyOf(responseRules);
        responses = responses == null ? java.util.Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(responses));
        schemaValidation = schemaValidation == null ? SchemaValidation.BOTH : schemaValidation;
    }

    public List<StepDefinition> allSteps() {
        return groups.stream().flatMap(List::stream).toList();
    }
}
