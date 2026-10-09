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
        @DefaultValue Assistant assistant,
        Map<String, TargetSystem> targetSystems,
        @DefaultValue Db db,
        @DefaultValue Sql sql,
        @DefaultValue Files files,
        Map<String, FileStorage> storages) {

    public GatewayProperties {
        targetSystems = targetSystems == null ? Map.of() : Map.copyOf(targetSystems);
        storages = storages == null ? Map.of() : Map.copyOf(storages);
    }

    /**
     * Uploads: {@code maxSize} limits a raw (non-multipart) request body such as {@code application/pdf}; multipart
     * uploads are limited by {@code spring.servlet.multipart.*}, which application.yml sets from the same value.
     */
    public record Files(@DefaultValue("20MB") org.springframework.util.unit.DataSize maxSize) {}

    /**
     * Where file storage steps put files: {@code type} {@code local} (a directory, {@code baseDir}) or {@code s3}
     * ({@code bucket}, optional {@code prefix}, {@code region}, {@code endpoint} for S3-compatible servers such as
     * MinIO, {@code pathStyle}, and {@code accessKey} / {@code secretKey}; blank keys use the default AWS credential
     * chain). An S3 storage without a bucket is skipped. {@code allowedTypes} (e.g. {@code image/*},
     * {@code application/pdf}; empty = any) and {@code maxSize} (empty = only the upload limit) are checked before
     * storing.
     */
    public record FileStorage(
            @DefaultValue("local") String type,
            String baseDir,
            String bucket,
            String prefix,
            String region,
            String endpoint,
            String accessKey,
            String secretKey,
            @DefaultValue("false") boolean pathStyle,
            List<String> allowedTypes,
            org.springframework.util.unit.DataSize maxSize) {

        public FileStorage {
            allowedTypes = allowedTypes == null ? List.of() : allowedTypes.stream().map(String::strip)
                    .filter(s -> !s.isEmpty()).toList();
        }
    }

    /**
     * Databases that database query steps may use ({@code gw_flow_step.sql_text}; the step's {@code target_system}
     * names one of {@code datasources}). {@code maxRows}: a query returning more rows is cut off there and reports
     * {@code truncated: true}.
     */
    public record Sql(@DefaultValue("1000") int maxRows, Map<String, SqlDatasource> datasources) {

        public Sql {
            datasources = datasources == null ? Map.of() : Map.copyOf(datasources);
        }
    }

    /**
     * One database for query steps. A blank {@code url} means the gateway's own database (its connection pool).
     * {@code readOnly}: only SELECT / WITH statements, run in a read-only transaction. Values may use
     * {@code ${ENV_VAR}} placeholders like any Spring property.
     */
    public record SqlDatasource(
            String url,
            String username,
            String password,
            String driverClassName,
            @DefaultValue("5") int maxPoolSize,
            @DefaultValue("false") boolean readOnly) {

        public boolean gatewayDatabase() {
            return url == null || url.isBlank();
        }
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
    public record Studio(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("edit") Mode mode,
            @DefaultValue LogBuffer logBuffer) {

        /** Log lines kept in memory per correlation ID for the audit trail screen. */
        public record LogBuffer(
                @DefaultValue("2000") int maxTransactions,
                @DefaultValue("300") int maxLines) {}

        /** {@code edit}: full editor. {@code view-only}: browse everything, no save and no test runs. */
        public enum Mode {
            EDIT,
            VIEW_ONLY
        }

        public boolean viewOnly() {
            return mode == Mode.VIEW_ONLY;
        }
    }

    /**
     * Studio's project assistant (the "Ask" button). Needs {@code gateway.studio.enabled} too. The AI connection
     * ({@code AI_BASE_URL}, {@code AI_AUTH_KEY}, {@code AI_MODEL}, ...) comes from the environment or {@code envFile}.
     */
    public record Assistant(
            @DefaultValue("true") boolean enabled,
            @DefaultValue(".env") String envFile,
            @DefaultValue("64000") int maxTokens,
            @DefaultValue("20") int historyMessages) {}

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
            String bodyCodec,
            Tls tls) {

        public TargetSystem {
            staticHeaders = staticHeaders == null ? Map.of() : Map.copyOf(staticHeaders);
            tls = tls == null ? Tls.VERIFY : tls;
        }

        public TargetSystem(String baseUrl, int connectTimeoutMs, Integer readTimeoutMs,
                Map<String, String> staticHeaders, String bodyCodec) {
            this(baseUrl, connectTimeoutMs, readTimeoutMs, staticHeaders, bodyCodec, null);
        }
    }

    /**
     * TLS for an {@code https://} target system (ignored for {@code http://}).
     * <ul>
     *   <li>{@code VERIFY} (default): the JVM's trusted CAs and hostname verification.</li>
     *   <li>{@code INSECURE}: trust any certificate and host name. Development and test systems only.</li>
     *   <li>{@code CUSTOM}: {@code trustStore} replaces the JVM's CAs (the server's CA or self-signed certificate);
     *       {@code keyStore} presents a client certificate (mutual TLS). Either or both.</li>
     * </ul>
     * A store is PEM text ({@code -----BEGIN ...}), or a path to a {@code .pem}/{@code .crt} file, or to a PKCS12
     * ({@code .p12}/{@code .pfx}) or JKS ({@code .jks}) file; {@code classpath:} and {@code file:} prefixes work. A PEM
     * key store holds the certificate chain and the private key (one file or one text). Passwords are for PKCS12/JKS
     * stores and encrypted PEM keys. Every value may use {@code ${ENV_VAR}} placeholders, which is the way to keep keys
     * and passwords out of the database.
     */
    public record Tls(String mode, String trustStore, String trustStorePassword, String keyStore,
            String keyStorePassword) {

        public static final Tls VERIFY = new Tls("VERIFY", null, null, null, null);

        public Tls {
            mode = mode == null || mode.isBlank() ? "VERIFY" : mode.strip().toUpperCase(java.util.Locale.ROOT);
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
            @DefaultValue("gw_target_system_header") String targetSystemHeader,
            @DefaultValue("gw_storage") String storage) {

        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Tables {}

        public Tables(String flow, String flowStep, String mappingRule, String lookupEntry, String jsonSchema,
                String auditTransaction, String auditStep, String targetSystem, String targetSystemHeader) {
            this(flow, flowStep, mappingRule, lookupEntry, jsonSchema, auditTransaction, auditStep, targetSystem,
                    targetSystemHeader, "gw_storage");
        }

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
                    "target_system_header", targetSystemHeader,
                    "storage", storage);
        }
    }

    public record LiquibaseTables(
            @DefaultValue("gw_db_changelog") String changelog,
            @DefaultValue("gw_db_changelog_lock") String changelogLock) {}
}
