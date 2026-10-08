package com.mhamzah.gateway.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code gateway.*} settings from application.yml (spec Section 4). */
@ConfigurationProperties("gateway")
public record GatewayProperties(
        @DefaultValue("/api") String apiBasePath,
        @DefaultValue("30000") long defaultFlowTimeoutMs,
        @DefaultValue("10000") long defaultStepTimeoutMs,
        @DefaultValue Admin admin,
        @DefaultValue Audit audit,
        @DefaultValue Masking masking,
        @DefaultValue Docs docs,
        @DefaultValue Studio studio,
        Map<String, TargetSystem> targetSystems,
        @DefaultValue Db db) {

    public GatewayProperties {
        targetSystems = targetSystems == null ? Map.of() : Map.copyOf(targetSystems);
    }

    /** Admin endpoints; when {@code token} is blank every admin call is rejected. */
    public record Admin(String token) {}

    public record Audit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("true") boolean storePayloads,
            @DefaultValue("10000") int queueCapacity,
            @DefaultValue("100") int batchSize) {}

    /** API documentation: Swagger UI at {@code /docs}, OpenAPI at {@code /docs/openapi.json}. Off unless enabled. */
    public record Docs(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("JSON Gateway API") String title) {}

    /**
     * Gateway Studio, the browser UI for editing flows, target systems and lookups: {@code /studio}. Off unless
     * enabled. Its API needs the admin token and writes straight to the config tables.
     */
    public record Studio(@DefaultValue("false") boolean enabled) {}

    public record Masking(
            @DefaultValue({"pin", "password", "cardNo", "cvv", "authorization", "x-admin-token"}) List<String> fields,
            @DefaultValue("****") String mask) {}

    /**
     * A downstream system steps can call; base URLs are per environment and never stored in the DB.
     * {@code bodyCodec} is the bean name of the {@link com.mhamzah.gateway.extension.BodyCodec} for its steps (null = JSON).
     */
    public record TargetSystem(
            String baseUrl,
            @DefaultValue("3000") int connectTimeoutMs,
            Integer readTimeoutMs,
            Map<String, String> staticHeaders,
            String bodyCodec) {

        public TargetSystem {
            staticHeaders = staticHeaders == null ? Map.of() : Map.copyOf(staticHeaders);
        }
    }

    /** Database schema and table names (spec Section 4.2). */
    public record Db(String schema, @DefaultValue Tables tables, @DefaultValue LiquibaseTables liquibaseTables) {

        public boolean hasSchema() {
            return schema != null && !schema.isBlank();
        }

        /** {@code schema.table} when a schema is configured, otherwise {@code table}. */
        public String qualify(String table) {
            return hasSchema() ? schema + "." + table : table;
        }
    }

    public record Tables(
            @DefaultValue("gw_flow") String flow,
            @DefaultValue("gw_flow_step") String flowStep,
            @DefaultValue("gw_mapping_rule") String mappingRule,
            @DefaultValue("gw_lookup_entry") String lookupEntry,
            @DefaultValue("gw_json_schema") String jsonSchema,
            @DefaultValue("gw_audit_transaction") String auditTransaction,
            @DefaultValue("gw_audit_step") String auditStep,
            @DefaultValue("gw_target_system") String targetSystem,
            @DefaultValue("gw_target_system_header") String targetSystemHeader) {

        /** Logical name (as used in {@code @Table} and Liquibase {@code tbl.*} parameters) to configured name. */
        public Map<String, String> byLogicalName() {
            return Map.of(
                    "flow", flow,
                    "flow_step", flowStep,
                    "mapping_rule", mappingRule,
                    "lookup_entry", lookupEntry,
                    "json_schema", jsonSchema,
                    "audit_transaction", auditTransaction,
                    "audit_step", auditStep,
                    "target_system", targetSystem,
                    "target_system_header", targetSystemHeader);
        }
    }

    public record LiquibaseTables(
            @DefaultValue("gw_db_changelog") String changelog,
            @DefaultValue("gw_db_changelog_lock") String changelogLock) {}
}
