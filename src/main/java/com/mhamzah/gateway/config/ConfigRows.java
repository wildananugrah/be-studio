package com.mhamzah.gateway.config;

import java.util.List;

/** Raw config rows as loaded from the database, before validation and compilation. */
public record ConfigRows(
        List<FlowRow> flows,
        List<StepRow> steps,
        List<RuleRow> rules,
        List<LookupRow> lookups,
        List<SchemaRow> schemas,
        List<TargetRow> targets,
        List<TargetHeaderRow> targetHeaders) {

    public ConfigRows {
        flows = List.copyOf(flows);
        steps = List.copyOf(steps);
        rules = List.copyOf(rules);
        lookups = List.copyOf(lookups);
        schemas = List.copyOf(schemas);
        targets = List.copyOf(targets);
        targetHeaders = List.copyOf(targetHeaders);
    }

    /** {@code gw_flow}. */
    public record FlowRow(
            long id,
            String code,
            String name,
            String httpMethod,
            String pathPattern,
            String requestSchemaCode,
            String responseSchemaCode,
            String requestHandler,
            String responseHandler,
            String errorHandler,
            Integer successStatus,
            Integer timeoutMs,
            String auditMode,
            boolean enabled) {

        public FlowRow withHandlers(String request, String response, String error) {
            return new FlowRow(id, code, name, httpMethod, pathPattern, requestSchemaCode, responseSchemaCode,
                    request, response, error, successStatus, timeoutMs, auditMode, enabled);
        }

        public FlowRow withSchemas(String request, String response) {
            return new FlowRow(id, code, name, httpMethod, pathPattern, request, response,
                    requestHandler, responseHandler, errorHandler, successStatus, timeoutMs, auditMode, enabled);
        }

        public FlowRow withAuditMode(String mode) {
            return new FlowRow(id, code, name, httpMethod, pathPattern, requestSchemaCode, responseSchemaCode,
                    requestHandler, responseHandler, errorHandler, successStatus, timeoutMs, mode, enabled);
        }

        public FlowRow withEnabled(boolean value) {
            return new FlowRow(id, code, name, httpMethod, pathPattern, requestSchemaCode, responseSchemaCode,
                    requestHandler, responseHandler, errorHandler, successStatus, timeoutMs, auditMode, value);
        }
    }

    /** {@code gw_flow_step}. */
    public record StepRow(
            long id,
            long flowId,
            String name,
            int stepOrder,
            String targetSystem,
            String httpMethod,
            String pathTemplate,
            String conditionExpr,
            String successExpr,
            String onFailure,
            Integer timeoutMs,
            String responseSchemaCode,
            String requestHandler,
            String responseHandler,
            String bodyCodec,
            boolean enabled) {

        public StepRow withTarget(String value) {
            return new StepRow(id, flowId, name, stepOrder, value, httpMethod, pathTemplate, conditionExpr,
                    successExpr, onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler,
                    bodyCodec, enabled);
        }

        public StepRow withPathTemplate(String value) {
            return new StepRow(id, flowId, name, stepOrder, targetSystem, httpMethod, value, conditionExpr,
                    successExpr, onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler,
                    bodyCodec, enabled);
        }

        public StepRow withCondition(String value) {
            return new StepRow(id, flowId, name, stepOrder, targetSystem, httpMethod, pathTemplate, value,
                    successExpr, onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler,
                    bodyCodec, enabled);
        }

        public StepRow withSuccess(String value) {
            return new StepRow(id, flowId, name, stepOrder, targetSystem, httpMethod, pathTemplate, conditionExpr,
                    value, onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler,
                    bodyCodec, enabled);
        }

        public StepRow withBodyCodec(String value) {
            return new StepRow(id, flowId, name, stepOrder, targetSystem, httpMethod, pathTemplate, conditionExpr,
                    successExpr, onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler,
                    value, enabled);
        }

        public StepRow withEnabled(boolean value) {
            return new StepRow(id, flowId, name, stepOrder, targetSystem, httpMethod, pathTemplate, conditionExpr,
                    successExpr, onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler,
                    bodyCodec, value);
        }
    }

    /** {@code gw_mapping_rule}. */
    public record RuleRow(
            long id,
            long flowId,
            Long stepId,
            String phase,
            int seq,
            String targetType,
            String targetPath,
            String sourcePath,
            String constantValue,
            String defaultValue,
            String converter,
            String lookupCode,
            String fieldHandler,
            boolean required) {}

    /** {@code gw_lookup_entry}. */
    public record LookupRow(long id, String lookupCode, String sourceValue, String targetValue) {}

    /** {@code gw_json_schema}. */
    public record SchemaRow(long id, String code, String schemaText) {}

    /**
     * {@code gw_target_system}: a downstream system. {@code baseUrl}, the TLS stores and their passwords may contain
     * {@code ${...}} placeholders. {@code tlsMode} null means VERIFY (see {@link GatewayProperties.Tls}).
     */
    public record TargetRow(
            long id,
            String code,
            String baseUrl,
            Integer connectTimeoutMs,
            Integer readTimeoutMs,
            String bodyCodec,
            boolean enabled,
            String tlsMode,
            String tlsTrustStore,
            String tlsTrustStorePassword,
            String tlsKeyStore,
            String tlsKeyStorePassword) {

        public TargetRow(long id, String code, String baseUrl, Integer connectTimeoutMs, Integer readTimeoutMs,
                String bodyCodec, boolean enabled) {
            this(id, code, baseUrl, connectTimeoutMs, readTimeoutMs, bodyCodec, enabled, null, null, null, null, null);
        }
    }

    /** {@code gw_target_system_header}: a fixed header sent to a target. {@code headerValue} may contain {@code ${...}}. */
    public record TargetHeaderRow(long id, String targetCode, String headerName, String headerValue) {}
}
