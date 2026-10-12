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
        List<TargetHeaderRow> targetHeaders,
        List<StorageRow> storages,
        List<ApplicationRow> applications,
        List<ResponseRow> responses) {

    public ConfigRows(List<FlowRow> flows, List<StepRow> steps, List<RuleRow> rules, List<LookupRow> lookups,
            List<SchemaRow> schemas, List<TargetRow> targets, List<TargetHeaderRow> targetHeaders,
            List<StorageRow> storages, List<ApplicationRow> applications) {
        this(flows, steps, rules, lookups, schemas, targets, targetHeaders, storages, applications, List.of());
    }

    public ConfigRows(List<FlowRow> flows, List<StepRow> steps, List<RuleRow> rules, List<LookupRow> lookups,
            List<SchemaRow> schemas, List<TargetRow> targets, List<TargetHeaderRow> targetHeaders,
            List<StorageRow> storages) {
        this(flows, steps, rules, lookups, schemas, targets, targetHeaders, storages, List.of());
    }

    public ConfigRows(List<FlowRow> flows, List<StepRow> steps, List<RuleRow> rules, List<LookupRow> lookups,
            List<SchemaRow> schemas, List<TargetRow> targets, List<TargetHeaderRow> targetHeaders) {
        this(flows, steps, rules, lookups, schemas, targets, targetHeaders, List.of());
    }

    public ConfigRows {
        storages = storages == null ? List.of() : List.copyOf(storages);
        applications = applications == null ? List.of() : List.copyOf(applications);
        responses = responses == null ? List.of() : List.copyOf(responses);
        flows = List.copyOf(flows);
        steps = List.copyOf(steps);
        rules = List.copyOf(rules);
        lookups = List.copyOf(lookups);
        schemas = List.copyOf(schemas);
        targets = List.copyOf(targets);
        targetHeaders = List.copyOf(targetHeaders);
    }

    /** {@code gw_flow_response}: a named client response of a flow ({@code status} null = 200). */
    public record ResponseRow(long id, long flowId, String name, Integer status, String description) {}

    /** {@code gw_application}: the group a flow belongs to (for export and for finding things). */
    public record ApplicationRow(long id, String code, String name, String description) {}

    /** {@code gw_flow}; {@code applicationCode} names its {@code gw_application} (null in tests = none). */
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
            boolean enabled,
            String applicationCode) {

        public FlowRow(long id, String code, String name, String httpMethod, String pathPattern,
                String requestSchemaCode, String responseSchemaCode, String requestHandler, String responseHandler,
                String errorHandler, Integer successStatus, Integer timeoutMs, String auditMode, boolean enabled) {
            this(id, code, name, httpMethod, pathPattern, requestSchemaCode, responseSchemaCode, requestHandler,
                    responseHandler, errorHandler, successStatus, timeoutMs, auditMode, enabled, null);
        }

        public FlowRow withApplication(String value) {
            return new FlowRow(id, code, name, httpMethod, pathPattern, requestSchemaCode, responseSchemaCode,
                    requestHandler, responseHandler, errorHandler, successStatus, timeoutMs, auditMode, enabled, value);
        }

        public FlowRow withHandlers(String request, String response, String error) {
            return new FlowRow(id, code, name, httpMethod, pathPattern, requestSchemaCode, responseSchemaCode,
                    request, response, error, successStatus, timeoutMs, auditMode, enabled, applicationCode);
        }

        public FlowRow withSchemas(String request, String response) {
            return new FlowRow(id, code, name, httpMethod, pathPattern, request, response,
                    requestHandler, responseHandler, errorHandler, successStatus, timeoutMs, auditMode, enabled, applicationCode);
        }

        public FlowRow withAuditMode(String mode) {
            return new FlowRow(id, code, name, httpMethod, pathPattern, requestSchemaCode, responseSchemaCode,
                    requestHandler, responseHandler, errorHandler, successStatus, timeoutMs, mode, enabled, applicationCode);
        }

        public FlowRow withEnabled(boolean value) {
            return new FlowRow(id, code, name, httpMethod, pathPattern, requestSchemaCode, responseSchemaCode,
                    requestHandler, responseHandler, errorHandler, successStatus, timeoutMs, auditMode, value, applicationCode);
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
            boolean enabled,
            String sqlText,
            String bodyTemplate,
            String nextOnSuccess,
            String nextOnFailure,
            String responseExample) {

        public StepRow(long id, long flowId, String name, int stepOrder, String targetSystem, String httpMethod,
                String pathTemplate, String conditionExpr, String successExpr, String onFailure, Integer timeoutMs,
                String responseSchemaCode, String requestHandler, String responseHandler, String bodyCodec,
                boolean enabled, String sqlText, String bodyTemplate, String nextOnSuccess, String nextOnFailure) {
            this(id, flowId, name, stepOrder, targetSystem, httpMethod, pathTemplate, conditionExpr, successExpr,
                    onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler, bodyCodec, enabled,
                    sqlText, bodyTemplate, nextOnSuccess, nextOnFailure, null);
        }

        public StepRow(long id, long flowId, String name, int stepOrder, String targetSystem, String httpMethod,
                String pathTemplate, String conditionExpr, String successExpr, String onFailure, Integer timeoutMs,
                String responseSchemaCode, String requestHandler, String responseHandler, String bodyCodec,
                boolean enabled, String sqlText, String bodyTemplate) {
            this(id, flowId, name, stepOrder, targetSystem, httpMethod, pathTemplate, conditionExpr, successExpr,
                    onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler, bodyCodec, enabled,
                    sqlText, bodyTemplate, null, null);
        }

        public StepRow(long id, long flowId, String name, int stepOrder, String targetSystem, String httpMethod,
                String pathTemplate, String conditionExpr, String successExpr, String onFailure, Integer timeoutMs,
                String responseSchemaCode, String requestHandler, String responseHandler, String bodyCodec,
                boolean enabled, String sqlText) {
            this(id, flowId, name, stepOrder, targetSystem, httpMethod, pathTemplate, conditionExpr, successExpr,
                    onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler, bodyCodec, enabled,
                    sqlText, null);
        }

        /** An HTTP step (no {@code sql_text}). */
        public StepRow(long id, long flowId, String name, int stepOrder, String targetSystem, String httpMethod,
                String pathTemplate, String conditionExpr, String successExpr, String onFailure, Integer timeoutMs,
                String responseSchemaCode, String requestHandler, String responseHandler, String bodyCodec,
                boolean enabled) {
            this(id, flowId, name, stepOrder, targetSystem, httpMethod, pathTemplate, conditionExpr, successExpr,
                    onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler, bodyCodec, enabled, null);
        }

        /** A database query step: {@code sql_text} set; {@code target_system} names a SQL datasource. */
        public boolean isSql() {
            return sqlText != null && !sqlText.isBlank();
        }

        public StepRow withSql(String value) {
            return new StepRow(id, flowId, name, stepOrder, targetSystem, httpMethod, pathTemplate, conditionExpr,
                    successExpr, onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler,
                    bodyCodec, enabled, value, bodyTemplate, nextOnSuccess, nextOnFailure, responseExample);
        }

        public StepRow withBodyTemplate(String value) {
            return new StepRow(id, flowId, name, stepOrder, targetSystem, httpMethod, pathTemplate, conditionExpr,
                    successExpr, onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler,
                    bodyCodec, enabled, sqlText, value, nextOnSuccess, nextOnFailure, responseExample);
        }

        public StepRow withTarget(String value) {
            return new StepRow(id, flowId, name, stepOrder, value, httpMethod, pathTemplate, conditionExpr,
                    successExpr, onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler,
                    bodyCodec, enabled, sqlText, bodyTemplate, nextOnSuccess, nextOnFailure, responseExample);
        }

        public StepRow withMethod(String value) {
            return new StepRow(id, flowId, name, stepOrder, targetSystem, value, pathTemplate, conditionExpr,
                    successExpr, onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler,
                    bodyCodec, enabled, sqlText, bodyTemplate, nextOnSuccess, nextOnFailure, responseExample);
        }

        public StepRow withPathTemplate(String value) {
            return new StepRow(id, flowId, name, stepOrder, targetSystem, httpMethod, value, conditionExpr,
                    successExpr, onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler,
                    bodyCodec, enabled, sqlText, bodyTemplate, nextOnSuccess, nextOnFailure, responseExample);
        }

        public StepRow withCondition(String value) {
            return new StepRow(id, flowId, name, stepOrder, targetSystem, httpMethod, pathTemplate, value,
                    successExpr, onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler,
                    bodyCodec, enabled, sqlText, bodyTemplate, nextOnSuccess, nextOnFailure, responseExample);
        }

        public StepRow withSuccess(String value) {
            return new StepRow(id, flowId, name, stepOrder, targetSystem, httpMethod, pathTemplate, conditionExpr,
                    value, onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler,
                    bodyCodec, enabled, sqlText, bodyTemplate, nextOnSuccess, nextOnFailure, responseExample);
        }

        public StepRow withBodyCodec(String value) {
            return new StepRow(id, flowId, name, stepOrder, targetSystem, httpMethod, pathTemplate, conditionExpr,
                    successExpr, onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler,
                    value, enabled, sqlText, bodyTemplate, nextOnSuccess, nextOnFailure, responseExample);
        }

        /** Where the flow goes after this step: on success (null = next step, END, FAIL or a step name) and on failure (null = on_failure, END or a step name). */
        public StepRow withNext(String onSuccess, String onFailure) {
            return new StepRow(id, flowId, name, stepOrder, targetSystem, httpMethod, pathTemplate, conditionExpr,
                    successExpr, this.onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler,
                    bodyCodec, enabled, sqlText, bodyTemplate, onSuccess, onFailure, responseExample);
        }

        public StepRow withEnabled(boolean value) {
            return new StepRow(id, flowId, name, stepOrder, targetSystem, httpMethod, pathTemplate, conditionExpr,
                    successExpr, onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler,
                    bodyCodec, value, sqlText, bodyTemplate, nextOnSuccess, nextOnFailure, responseExample);
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
            boolean required,
            String responseName) {

        /** A rule of a step or of the default response ({@code response_name} null). */
        public RuleRow(long id, long flowId, Long stepId, String phase, int seq, String targetType, String targetPath,
                String sourcePath, String constantValue, String defaultValue, String converter, String lookupCode,
                String fieldHandler, boolean required) {
            this(id, flowId, stepId, phase, seq, targetType, targetPath, sourcePath, constantValue, defaultValue,
                    converter, lookupCode, fieldHandler, required, null);
        }
    }

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

    /**
     * {@code gw_storage}: where file storage steps put files. {@code storageType} LOCAL or S3; text values may contain
     * {@code ${...}} placeholders; {@code allowedTypes} comma-separated; {@code maxSize} like {@code 10MB}.
     */
    public record StorageRow(
            long id,
            String code,
            String storageType,
            String baseDir,
            String bucket,
            String keyPrefix,
            String region,
            String endpoint,
            boolean pathStyle,
            String accessKey,
            String secretKey,
            String allowedTypes,
            String maxSize,
            boolean enabled) {}

    /** {@code gw_target_system_header}: a fixed header sent to a target. {@code headerValue} may contain {@code ${...}}. */
    public record TargetHeaderRow(long id, String targetCode, String headerName, String headerValue) {}
}
