package com.mhamzah.gateway.studio;

import java.util.List;

/**
 * The editable configuration as Gateway Studio sees it: every flow with its steps and mapping rules nested, the
 * database target systems with their headers, the lookup tables and the JSON schemas.
 *
 * <p>Omitted flags default to {@code enabled = true} and {@code required = false}. {@code version} identifies the
 * database state the document was read from, so a save can refuse to overwrite changes made by someone else in the
 * meantime. It is ignored on input.
 */
public record StudioConfig(
        String version, List<Flow> flows, List<Target> targets, List<Lookup> lookups, List<Schema> schemas,
        List<Storage> storages, List<Application> applications) {

    public StudioConfig(String version, List<Flow> flows, List<Target> targets, List<Lookup> lookups,
            List<Schema> schemas, List<Storage> storages) {
        this(version, flows, targets, lookups, schemas, storages, List.of());
    }

    public StudioConfig(String version, List<Flow> flows, List<Target> targets, List<Lookup> lookups,
            List<Schema> schemas) {
        this(version, flows, targets, lookups, schemas, List.of());
    }

    public StudioConfig {
        storages = storages == null ? List.of() : List.copyOf(storages);
        applications = applications == null ? List.of() : List.copyOf(applications);
        flows = flows == null ? List.of() : List.copyOf(flows);
        targets = targets == null ? List.of() : List.copyOf(targets);
        lookups = lookups == null ? List.of() : List.copyOf(lookups);
        schemas = schemas == null ? List.of() : List.copyOf(schemas);
    }

    public StudioConfig withVersion(String value) {
        return new StudioConfig(value, flows, targets, lookups, schemas, storages, applications);
    }

    /**
     * {@code gw_storage}: where file storage steps put files. {@code type} LOCAL ({@code baseDir}) or S3
     * ({@code bucket}, {@code prefix}, {@code region}, {@code endpoint}, {@code pathStyle}, {@code accessKey},
     * {@code secretKey}); values may use {@code ${ENV}} placeholders. {@code allowedTypes} comma-separated,
     * {@code maxSize} like {@code 10MB}.
     */
    public record Storage(
            String code,
            String type,
            String baseDir,
            String bucket,
            String prefix,
            String region,
            String endpoint,
            Boolean pathStyle,
            String accessKey,
            String secretKey,
            String allowedTypes,
            String maxSize,
            Boolean enabled) {

        public Storage {
            enabled = enabled == null || enabled;
            pathStyle = pathStyle != null && pathStyle;
        }
    }

    /** {@code gw_application}: a named group of flows, exported and imported as a unit. */
    public record Application(String code, String name, String description) {}

    /** {@code gw_flow} plus its steps and its {@code FLOW_RESPONSE} rules; {@code application} is its group. */
    public record Flow(
            String code,
            String name,
            String method,
            String path,
            String requestSchema,
            String responseSchema,
            String requestHandler,
            String responseHandler,
            String errorHandler,
            Integer successStatus,
            Integer timeoutMs,
            String auditMode,
            Boolean enabled,
            List<Step> steps,
            List<Rule> response,
            String application,
            List<Response> responses) {

        public Flow(String code, String name, String method, String path, String requestSchema, String responseSchema,
                String requestHandler, String responseHandler, String errorHandler, Integer successStatus,
                Integer timeoutMs, String auditMode, Boolean enabled, List<Step> steps, List<Rule> response,
                String application) {
            this(code, name, method, path, requestSchema, responseSchema, requestHandler, responseHandler, errorHandler,
                    successStatus, timeoutMs, auditMode, enabled, steps, response, application, List.of());
        }

        public Flow(String code, String name, String method, String path, String requestSchema, String responseSchema,
                String requestHandler, String responseHandler, String errorHandler, Integer successStatus,
                Integer timeoutMs, String auditMode, Boolean enabled, List<Step> steps, List<Rule> response) {
            this(code, name, method, path, requestSchema, responseSchema, requestHandler, responseHandler, errorHandler,
                    successStatus, timeoutMs, auditMode, enabled, steps, response, null, List.of());
        }

        public Flow {
            enabled = enabled == null || enabled;
            steps = steps == null ? List.of() : List.copyOf(steps);
            response = response == null ? List.of() : List.copyOf(response);
            responses = responses == null ? List.of() : List.copyOf(responses);
        }
    }

    /**
     * {@code gw_flow_response} plus its {@code FLOW_RESPONSE} rules: a named client response a branch answers with
     * ({@code next_on_success / next_on_failure = RESPOND:<name>}); {@code status} null = 200.
     */
    public record Response(String name, Integer status, String description, List<Rule> rules) {
        public Response {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    /** {@code gw_flow_step} plus its {@code STEP_REQUEST} rules. */
    public record Step(
            String name,
            int order,
            String targetSystem,
            String method,
            String path,
            String condition,
            String success,
            String onFailure,
            Integer timeoutMs,
            String responseSchema,
            String requestHandler,
            String responseHandler,
            String bodyCodec,
            Boolean enabled,
            List<Rule> rules,
            String sql,
            String bodyTemplate,
            String nextOnSuccess,
            String nextOnFailure,
            String responseExample) {

        public Step(String name, int order, String targetSystem, String method, String path, String condition,
                String success, String onFailure, Integer timeoutMs, String responseSchema, String requestHandler,
                String responseHandler, String bodyCodec, Boolean enabled, List<Rule> rules, String sql,
                String bodyTemplate, String nextOnSuccess, String nextOnFailure) {
            this(name, order, targetSystem, method, path, condition, success, onFailure, timeoutMs, responseSchema,
                    requestHandler, responseHandler, bodyCodec, enabled, rules, sql, bodyTemplate, nextOnSuccess,
                    nextOnFailure, null);
        }

        public Step {
            enabled = enabled == null || enabled;
            rules = rules == null ? List.of() : List.copyOf(rules);
        }

        /** An HTTP step. */
        public Step(String name, int order, String targetSystem, String method, String path, String condition,
                String success, String onFailure, Integer timeoutMs, String responseSchema, String requestHandler,
                String responseHandler, String bodyCodec, Boolean enabled, List<Rule> rules) {
            this(name, order, targetSystem, method, path, condition, success, onFailure, timeoutMs, responseSchema,
                    requestHandler, responseHandler, bodyCodec, enabled, rules, null, null);
        }

        public Step(String name, int order, String targetSystem, String method, String path, String condition,
                String success, String onFailure, Integer timeoutMs, String responseSchema, String requestHandler,
                String responseHandler, String bodyCodec, Boolean enabled, List<Rule> rules, String sql,
                String bodyTemplate) {
            this(name, order, targetSystem, method, path, condition, success, onFailure, timeoutMs, responseSchema,
                    requestHandler, responseHandler, bodyCodec, enabled, rules, sql, bodyTemplate, null, null);
        }
    }

    /** {@code gw_mapping_rule}; {@code seq} is the position in its list. */
    public record Rule(
            String type,
            String target,
            String source,
            String constant,
            String defaultValue,
            String lookup,
            String converter,
            String fieldHandler,
            Boolean required) {

        public Rule {
            required = required != null && required;
        }
    }

    /** {@code gw_target_system} plus its {@code gw_target_system_header} rows. */
    public record Target(
            String code,
            String baseUrl,
            Integer connectTimeoutMs,
            Integer readTimeoutMs,
            String bodyCodec,
            Boolean enabled,
            List<Header> headers,
            Tls tls) {

        public Target {
            enabled = enabled == null || enabled;
            headers = headers == null ? List.of() : List.copyOf(headers);
            tls = tls == null ? new Tls(null, null, null, null, null) : tls;
        }

        public Target(String code, String baseUrl, Integer connectTimeoutMs, Integer readTimeoutMs, String bodyCodec,
                Boolean enabled, List<Header> headers) {
            this(code, baseUrl, connectTimeoutMs, readTimeoutMs, bodyCodec, enabled, headers, null);
        }
    }

    /**
     * TLS of an https target ({@code gw_target_system.tls_*}): mode VERIFY (null), INSECURE or CUSTOM; stores are
     * PEM text or file paths (.pem/.crt/.p12/.pfx/.jks), values may use {@code ${ENV}} placeholders.
     */
    public record Tls(String mode, String trustStore, String trustStorePassword, String keyStore,
            String keyStorePassword) {}

    public record Header(String name, String value) {}

    /** The {@code gw_lookup_entry} rows of one {@code lookup_code}. */
    public record Lookup(String code, List<Entry> entries) {

        public Lookup {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    public record Entry(String source, String target) {}

    /** {@code gw_json_schema}: a JSON Schema (draft 2020-12) referenced by its code. */
    public record Schema(String code, String description, String text) {}
}
