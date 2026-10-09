package com.mhamzah.gateway.config;

import com.mhamzah.gateway.codec.JsonCodec;
import com.mhamzah.gateway.condition.Condition;
import com.mhamzah.gateway.config.ConfigRows.FlowRow;
import com.mhamzah.gateway.config.ConfigRows.LookupRow;
import com.mhamzah.gateway.config.ConfigRows.RuleRow;
import com.mhamzah.gateway.config.ConfigRows.SchemaRow;
import com.mhamzah.gateway.config.ConfigRows.StepRow;
import com.mhamzah.gateway.config.ConfigRows.TargetHeaderRow;
import com.mhamzah.gateway.config.ConfigRows.TargetRow;
import com.mhamzah.gateway.extension.BodyCodec;
import com.mhamzah.gateway.extension.DefaultErrorHandler;
import com.mhamzah.gateway.extension.ErrorHandler;
import com.mhamzah.gateway.extension.FieldHandler;
import com.mhamzah.gateway.extension.MessageHandler;
import com.mhamzah.gateway.mapping.BodyTemplate;
import com.mhamzah.gateway.mapping.CompiledRule;
import com.mhamzah.gateway.mapping.Converters;
import com.mhamzah.gateway.mapping.JsonPath;
import com.mhamzah.gateway.mapping.JsonValues;
import com.mhamzah.gateway.mapping.LookupTable;
import com.mhamzah.gateway.mapping.TargetType;
import com.mhamzah.gateway.schema.CompiledSchema;
import com.mhamzah.gateway.sql.SqlDatasources;
import com.mhamzah.gateway.sql.SqlStatement;
import com.mhamzah.gateway.sql.SqlText;
import com.mhamzah.gateway.storage.FileStore;
import com.mhamzah.gateway.storage.FileStores;
import com.mhamzah.gateway.storage.StorageKeys;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpMethod;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.util.pattern.PatternParseException;

/**
 * Validates raw config rows and compiles them into a {@link FlowRegistry}. Every problem is collected and
 * reported together in a {@link ConfigValidationException} (spec Section 11).
 */
public class ConfigCompiler {

    /** Resolves custom handler beans by name; returns null when the bean is missing or has the wrong type. */
    public interface HandlerLookup {
        <T> T find(String name, Class<T> type);
    }

    private static final Pattern STEP_NAME = Pattern.compile("[A-Za-z0-9_\\-]+");
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{([^}/]+)}");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> CONTEXT_ROOTS = Set.of("request", "steps", "correlationId");
    private static final String STEP_REQUEST = "STEP_REQUEST";
    private static final String FLOW_RESPONSE = "FLOW_RESPONSE";

    /** RFC 7230 token characters, so a header name can never smuggle in other headers. */
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z\\-]+");
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 3000;

    private final HandlerLookup handlers;
    private final Map<String, GatewayProperties.TargetSystem> configTargets;
    private final UnaryOperator<String> placeholders;
    private final Duration defaultFlowTimeout;
    private final Duration defaultStepTimeout;
    private final SqlDatasources sqlDatasources;
    private final FileStores fileStores;

    /**
     * @param configTargets target systems from application.yml ({@code gateway.target-systems}); database rows win
     * @param placeholders resolves {@code ${...}} in database base URLs and header values; throws
     *     {@link IllegalArgumentException} for unresolvable placeholders
     */
    public ConfigCompiler(HandlerLookup handlers, Map<String, GatewayProperties.TargetSystem> configTargets,
            UnaryOperator<String> placeholders, Duration defaultFlowTimeout, Duration defaultStepTimeout) {
        this(handlers, configTargets, placeholders, defaultFlowTimeout, defaultStepTimeout, SqlDatasources.none(),
                FileStores.none());
    }

    /** @param sqlDatasources the databases database query steps may use ({@code gateway.sql.datasources}) */
    public ConfigCompiler(HandlerLookup handlers, Map<String, GatewayProperties.TargetSystem> configTargets,
            UnaryOperator<String> placeholders, Duration defaultFlowTimeout, Duration defaultStepTimeout,
            SqlDatasources sqlDatasources, FileStores fileStores) {
        this.sqlDatasources = sqlDatasources;
        this.fileStores = fileStores;
        this.handlers = handlers;
        this.configTargets = configTargets;
        this.placeholders = placeholders;
        this.defaultFlowTimeout = defaultFlowTimeout;
        this.defaultStepTimeout = defaultStepTimeout;
    }

    public FlowRegistry compile(ConfigRows rows) {
        return new Run(rows).compile();
    }

    /** State of one compilation. */
    private final class Run {
        private final ConfigRows rows;
        private final List<String> errors = new ArrayList<>();
        private Map<String, CompiledSchema> schemas;
        private Map<String, LookupTable> lookups;
        private Map<String, ResolvedTarget> targets;
        private Map<String, FileStore> storages;
        /** Body codec of each target system that sets one (a missing bean is reported once, for the target). */
        private final Map<String, BodyCodec> targetCodecs = new HashMap<>();

        Run(ConfigRows rows) {
            this.rows = rows;
        }

        FlowRegistry compile() {
            schemas = compileSchemas(rows.schemas());
            lookups = buildLookups(rows.lookups());
            targets = resolveTargets();
            storages = resolveStorages();
            checkFlowCodes();

            Map<Long, List<StepRow>> stepsByFlow = rows.steps().stream()
                    .collect(Collectors.groupingBy(StepRow::flowId));
            Map<Long, List<RuleRow>> rulesByFlow = rows.rules().stream()
                    .collect(Collectors.groupingBy(RuleRow::flowId));
            Map<String, String> routes = new HashMap<>();
            List<FlowDefinition> flows = new ArrayList<>();
            rows.flows().stream()
                    .filter(FlowRow::enabled)
                    .sorted(Comparator.comparing(FlowRow::code, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .forEach(f -> {
                        FlowDefinition def = compileFlow(f, stepsByFlow.getOrDefault(f.id(), List.of()),
                                rulesByFlow.getOrDefault(f.id(), List.of()), routes);
                        if (def != null) {
                            flows.add(def);
                        }
                    });
            if (!errors.isEmpty()) {
                throw new ConfigValidationException(errors);
            }
            return new FlowRegistry(flows, lookups, targets, Instant.now());
        }

        /** application.yml targets, replaced by enabled gw_target_system rows of the same code (database wins). */
        private Map<String, ResolvedTarget> resolveTargets() {
            Map<String, ResolvedTarget> out = new TreeMap<>();
            configTargets.forEach((code, system) -> {
                String where = "target system '" + code + "' (application config)";
                checkBaseUrl(system.baseUrl(), where, "base-url");
                checkTls(system.baseUrl(), system.tls(), where);
                out.put(code, new ResolvedTarget(code, system, ResolvedTarget.Source.CONFIG, false));
                targetCodec(code, system.bodyCodec(), where, "body-codec");
            });

            Map<String, TargetRow> dbTargets = new HashMap<>();
            Set<String> disabled = new HashSet<>();
            for (TargetRow t : rows.targets()) {
                if (t.code() == null || t.code().isBlank()) {
                    errors.add("gw_target_system id " + t.id() + ": code is required");
                } else if (!t.enabled()) {
                    disabled.add(t.code());
                } else if (dbTargets.putIfAbsent(t.code(), t) != null) {
                    errors.add("Duplicate target system code '" + t.code() + "'");
                }
            }
            Map<String, Map<String, String>> headers = new HashMap<>();
            for (TargetHeaderRow h : rows.targetHeaders()) {
                String where = "target system '" + h.targetCode() + "' header '" + h.headerName() + "'";
                if (disabled.contains(h.targetCode())) {
                    continue;
                }
                if (!dbTargets.containsKey(h.targetCode())) {
                    errors.add("gw_target_system_header id " + h.id() + " references unknown target system '"
                            + h.targetCode() + "'");
                    continue;
                }
                if (h.headerName() == null || !HEADER_NAME.matcher(h.headerName()).matches()) {
                    errors.add(where + ": invalid header name '" + h.headerName() + "'");
                    continue;
                }
                String value = resolve(h.headerValue() == null ? "" : h.headerValue(), where);
                if (value == null) {
                    continue; // unresolvable placeholder, already reported
                }
                if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                    errors.add(where + ": header value must not contain line breaks");
                    continue;
                }
                headers.computeIfAbsent(h.targetCode(), k -> new LinkedHashMap<>()).put(h.headerName(), value);
            }

            dbTargets.forEach((code, t) -> {
                String where = "target system '" + code + "'";
                String baseUrl = resolve(t.baseUrl(), where);
                if (baseUrl != null) {
                    checkBaseUrl(baseUrl, where, "base_url");
                }
                if (t.connectTimeoutMs() != null && t.connectTimeoutMs() <= 0) {
                    errors.add(where + ": connect_timeout_ms must be > 0");
                }
                if (t.readTimeoutMs() != null && t.readTimeoutMs() <= 0) {
                    errors.add(where + ": read_timeout_ms must be > 0");
                }
                int connect = t.connectTimeoutMs() == null ? DEFAULT_CONNECT_TIMEOUT_MS : t.connectTimeoutMs();
                var tls = new GatewayProperties.Tls(t.tlsMode(), optional(t.tlsTrustStore(), where),
                        optional(t.tlsTrustStorePassword(), where), optional(t.tlsKeyStore(), where),
                        optional(t.tlsKeyStorePassword(), where));
                if (baseUrl != null) {
                    checkTls(baseUrl, tls, where);
                }
                var system = new GatewayProperties.TargetSystem(baseUrl, connect, t.readTimeoutMs(),
                        headers.getOrDefault(code, Map.of()), t.bodyCodec(), tls);
                out.put(code, new ResolvedTarget(code, system, ResolvedTarget.Source.DATABASE,
                        configTargets.containsKey(code)));
                targetCodecs.remove(code); // the database row replaces the application.yml definition
                targetCodec(code, t.bodyCodec(), where, "body_codec");
            });
            return out;
        }

        private void targetCodec(String code, String name, String where, String column) {
            if (name != null && !name.isBlank()) {
                BodyCodec codec = codec(name, where, column);
                if (codec != null) {
                    targetCodecs.put(code, codec);
                }
            }
        }

        /** The {@link BodyCodec} bean {@code name}; {@code jsonCodec} always resolves, even without a bean. */
        private BodyCodec codec(String name, String where, String column) {
            return JsonCodec.BEAN_NAME.equals(name) ? JsonCodec.INSTANCE : handler(name, BodyCodec.class, where, column);
        }

        private String resolve(String text, String where) {
            if (text == null) {
                errors.add(where + ": base_url is required");
                return null;
            }
            try {
                return placeholders.apply(text);
            } catch (IllegalArgumentException e) {
                errors.add(where + ": " + e.getMessage());
                return null;
            }
        }

        /**
         * application.yml storages, replaced by enabled {@code gw_storage} rows of the same code and extended by the
         * other enabled rows. Placeholders are resolved now; a row that cannot be built is reported.
         */
        private Map<String, FileStore> resolveStorages() {
            Map<String, FileStore> out = new HashMap<>(fileStores.configured());
            Set<String> seen = new HashSet<>();
            for (ConfigRows.StorageRow r : rows.storages()) {
                String where = "storage '" + r.code() + "'";
                if (r.code() == null || !STEP_NAME.matcher(r.code()).matches()) {
                    errors.add(where + ": code must match " + STEP_NAME.pattern());
                    continue;
                }
                if (!seen.add(r.code())) {
                    errors.add(where + ": duplicate code");
                    continue;
                }
                if (!r.enabled()) {
                    out.remove(r.code());
                    continue;
                }
                String type = r.storageType() == null ? "LOCAL" : r.storageType().strip().toUpperCase(Locale.ROOT);
                if (!type.equals("LOCAL") && !type.equals("S3")) {
                    errors.add(where + ": storage_type must be LOCAL or S3, not " + r.storageType());
                    continue;
                }
                org.springframework.util.unit.DataSize max = null;
                if (r.maxSize() != null && !r.maxSize().isBlank()) {
                    try {
                        max = org.springframework.util.unit.DataSize.parse(r.maxSize().strip().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        errors.add(where + ": max_size '" + r.maxSize() + "' is not a size such as 10MB or 512KB");
                        continue;
                    }
                }
                int before = errors.size();
                GatewayProperties.FileStorage cfg = new GatewayProperties.FileStorage(type.toLowerCase(Locale.ROOT),
                        setting(r.baseDir(), where, "base_dir"), setting(r.bucket(), where, "bucket"),
                        setting(r.keyPrefix(), where, "key_prefix"), setting(r.region(), where, "region"),
                        setting(r.endpoint(), where, "endpoint"),
                        setting(r.accessKey(), where, "access_key"), setting(r.secretKey(), where, "secret_key"),
                        r.pathStyle(),
                        r.allowedTypes() == null ? List.of() : List.of(r.allowedTypes().split(",")), max);
                if (errors.size() > before) {
                    continue;
                }
                if (type.equals("LOCAL") && cfg.baseDir() == null) {
                    errors.add(where + ": base_dir is required for a LOCAL storage");
                    continue;
                }
                if (cfg.endpoint() != null) {
                    try {
                        URI u = new URI(cfg.endpoint());
                        if (!"http".equalsIgnoreCase(u.getScheme()) && !"https".equalsIgnoreCase(u.getScheme())) {
                            throw new URISyntaxException(cfg.endpoint(), "not http(s)");
                        }
                    } catch (URISyntaxException e) {
                        errors.add(where + ": endpoint must be an http(s) URL, e.g. http://minio:9000");
                        continue;
                    }
                }
                try {
                    out.put(r.code(), fileStores.build(r.code(), cfg));
                } catch (RuntimeException e) {
                    errors.add(where + ": " + e.getMessage());
                }
            }
            return out;
        }

        /** A storage setting with placeholders resolved; null when blank or unresolvable (reported). */
        private String setting(String text, String where, String column) {
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                String v = placeholders.apply(text.strip());
                return v == null || v.isBlank() ? null : v;
            } catch (IllegalArgumentException e) {
                errors.add(where + ": " + column + ": " + e.getMessage());
                return null;
            }
        }

        /** Optional text with {@code ${...}} placeholders resolved; null when absent or unresolvable (reported). */
        private String optional(String text, String where) {
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return placeholders.apply(text);
            } catch (IllegalArgumentException e) {
                errors.add(where + ": tls: " + e.getMessage());
                return null;
            }
        }

        /**
         * Validates the TLS settings of an https target by building its context now (a missing file or a wrong
         * password is a config error, not a failed call later); a reload re-reads the stores. Ignored for http.
         */
        private void checkTls(String baseUrl, GatewayProperties.Tls tls, String where) {
            if (tls == null) {
                return;
            }
            if (!Set.of("VERIFY", "INSECURE", "CUSTOM").contains(tls.mode())) {
                errors.add(where + ": tls_mode '" + tls.mode() + "' must be VERIFY, INSECURE or CUSTOM");
                return;
            }
            if (baseUrl == null || !baseUrl.regionMatches(true, 0, "https:", 0, 6)) {
                return;
            }
            try {
                com.mhamzah.gateway.invoke.TlsContexts.rebuild(tls);
                if ("INSECURE".equals(tls.mode())) {
                    org.slf4j.LoggerFactory.getLogger(ConfigCompiler.class).warn(
                            "{}: tls_mode INSECURE, certificates and host names are NOT verified (dev/test only)", where);
                }
            } catch (IllegalArgumentException e) {
                errors.add(where + ": tls: " + e.getMessage());
            }
        }

        /** An absolute http(s) URL with a host and no query or fragment; a base path is allowed. */
        private void checkBaseUrl(String baseUrl, String where, String column) {
            if (baseUrl == null || baseUrl.isBlank()) {
                errors.add(where + ": " + column + " is required");
                return;
            }
            try {
                URI uri = new URI(baseUrl);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    errors.add(where + ": " + column + " '" + baseUrl + "' must start with http:// or https://");
                } else if (uri.getHost() == null) {
                    errors.add(where + ": " + column + " '" + baseUrl + "' has no host");
                } else if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                    errors.add(where + ": " + column + " '" + baseUrl + "' must not contain a query or fragment");
                }
            } catch (URISyntaxException e) {
                errors.add(where + ": " + column + " '" + baseUrl + "' is not a valid URL: " + e.getReason());
            }
        }

        private Map<String, CompiledSchema> compileSchemas(List<SchemaRow> rows) {
            Map<String, CompiledSchema> out = new HashMap<>();
            for (SchemaRow s : rows) {
                try {
                    out.put(s.code(), CompiledSchema.compile(s.code(), s.schemaText()));
                } catch (IllegalArgumentException e) {
                    errors.add("json schema '" + s.code() + "': " + e.getMessage());
                }
            }
            return out;
        }

        private Map<String, LookupTable> buildLookups(List<LookupRow> rows) {
            Map<String, Map<String, tools.jackson.databind.JsonNode>> entries = new LinkedHashMap<>();
            Map<String, tools.jackson.databind.JsonNode> fallbacks = new HashMap<>();
            for (LookupRow l : rows) {
                if (LookupTable.FALLBACK_KEY.equals(l.sourceValue())) {
                    fallbacks.put(l.lookupCode(), JsonValues.parseLiteral(l.targetValue()));
                    entries.computeIfAbsent(l.lookupCode(), k -> new HashMap<>());
                } else {
                    entries.computeIfAbsent(l.lookupCode(), k -> new HashMap<>())
                            .put(l.sourceValue(), JsonValues.parseLiteral(l.targetValue()));
                }
            }
            Map<String, LookupTable> out = new HashMap<>();
            entries.forEach((code, map) -> out.put(code, new LookupTable(code, map, fallbacks.get(code))));
            return out;
        }

        private void checkFlowCodes() {
            Set<String> seen = new HashSet<>();
            for (FlowRow f : rows.flows()) {
                if (f.code() == null || f.code().isBlank()) {
                    errors.add("flow id " + f.id() + ": code is required");
                } else if (!seen.add(f.code())) {
                    errors.add("Duplicate flow code '" + f.code() + "'");
                }
            }
        }

        private FlowDefinition compileFlow(FlowRow f, List<StepRow> stepRows, List<RuleRow> ruleRows,
                Map<String, String> routes) {
            String where = "flow '" + f.code() + "'";
            HttpMethod method = method(f.httpMethod(), where);
            PathPattern pattern = pathPattern(f.pathPattern(), where);
            if (method != null && pattern != null) {
                String key = method + " " + f.pathPattern().replaceAll("\\{[^}]*}", "{}");
                String other = routes.putIfAbsent(key, f.code());
                if (other != null) {
                    errors.add(where + " has the same route as flow '" + other + "' (" + method + " " + f.pathPattern() + ")");
                }
            }
            CompiledSchema requestSchema = schema(f.requestSchemaCode(), where, "request_schema_code");
            CompiledSchema responseSchema = schema(f.responseSchemaCode(), where, "response_schema_code");
            MessageHandler requestHandler = handler(f.requestHandler(), MessageHandler.class, where, "request_handler");
            MessageHandler responseHandler = handler(f.responseHandler(), MessageHandler.class, where, "response_handler");
            String errorHandlerName = f.errorHandler() == null || f.errorHandler().isBlank()
                    ? DefaultErrorHandler.BEAN_NAME : f.errorHandler();
            ErrorHandler errorHandler = handler(errorHandlerName, ErrorHandler.class, where, "error_handler");
            int successStatus = f.successStatus() == null ? 200 : f.successStatus();
            if (successStatus < 100 || successStatus > 599) {
                errors.add(where + ": success_status " + successStatus + " is not a valid HTTP status");
            }
            Duration timeout = timeout(f.timeoutMs(), defaultFlowTimeout, where);
            AuditMode auditMode = enumValue(AuditMode.class, f.auditMode() == null ? "INHERIT" : f.auditMode(),
                    where, "audit_mode");

            List<StepRow> enabledSteps = stepRows.stream().filter(StepRow::enabled).toList();
            Set<Long> disabledStepIds = stepRows.stream().filter(s -> !s.enabled()).map(StepRow::id)
                    .collect(Collectors.toSet());
            Map<String, Integer> stepOrders = checkSteps(enabledSteps, where);

            List<RuleRow> sortedRules = ruleRows.stream()
                    .sorted(Comparator.comparingInt(RuleRow::seq).thenComparingLong(RuleRow::id))
                    .toList();
            Map<Long, List<RuleRow>> stepRules = new HashMap<>();
            List<CompiledRule> responseRules = new ArrayList<>();
            Set<Long> enabledStepIds = enabledSteps.stream().map(StepRow::id).collect(Collectors.toSet());
            for (RuleRow r : sortedRules) {
                String rw = "mapping rule " + r.id() + " of " + where;
                if (STEP_REQUEST.equals(r.phase())) {
                    if (r.stepId() == null) {
                        errors.add(rw + ": STEP_REQUEST rule requires step_id");
                    } else if (disabledStepIds.contains(r.stepId())) {
                        continue;
                    } else if (!enabledStepIds.contains(r.stepId())) {
                        errors.add(rw + ": step_id " + r.stepId() + " is not a step of this flow");
                    } else {
                        stepRules.computeIfAbsent(r.stepId(), k -> new ArrayList<>()).add(r);
                    }
                } else if (FLOW_RESPONSE.equals(r.phase())) {
                    if (r.stepId() != null) {
                        errors.add(rw + ": FLOW_RESPONSE rule must not have step_id");
                        continue;
                    }
                    CompiledRule rule = rule(r, rw, Set.of(TargetType.BODY, TargetType.HEADER), FLOW_RESPONSE);
                    if (rule != null) {
                        checkStepRefs(rule.source(), stepOrders, Integer.MAX_VALUE, null, rw);
                        responseRules.add(rule);
                    }
                } else {
                    errors.add(rw + ": phase '" + r.phase() + "' must be STEP_REQUEST or FLOW_RESPONSE");
                }
            }

            TreeMap<Integer, List<StepDefinition>> groups = new TreeMap<>();
            for (StepRow s : enabledSteps.stream().sorted(Comparator.comparing(StepRow::name)).toList()) {
                StepDefinition def = step(s, where, stepRules.getOrDefault(s.id(), List.of()), stepOrders);
                if (def != null) {
                    groups.computeIfAbsent(s.stepOrder(), k -> new ArrayList<>()).add(def);
                }
            }
            if (method == null || pattern == null) {
                return null;
            }
            return new FlowDefinition(f.code(), method, pattern, requestSchema, responseSchema, requestHandler,
                    responseHandler, errorHandler, errorHandlerName, successStatus, timeout, auditMode,
                    new ArrayList<>(groups.values()), responseRules);
        }

        /** Checks step names and orders; returns name to order for the valid steps. */
        private Map<String, Integer> checkSteps(List<StepRow> steps, String where) {
            Map<String, Integer> orders = new HashMap<>();
            for (StepRow s : steps) {
                if (s.stepOrder() < 1) {
                    errors.add(where + ": step '" + s.name() + "' step_order must be >= 1");
                }
                if (s.name() == null || !STEP_NAME.matcher(s.name()).matches()) {
                    errors.add(where + ": step name '" + s.name() + "' must match " + STEP_NAME.pattern());
                    continue;
                }
                if (orders.putIfAbsent(s.name(), s.stepOrder()) != null) {
                    errors.add(where + ": Duplicate step name '" + s.name() + "'");
                }
            }
            return orders;
        }

        private StepDefinition step(StepRow s, String flowWhere, List<RuleRow> ruleRows, Map<String, Integer> orders) {
            String where = flowWhere + " step '" + s.name() + "'";
            if (s.isSql()) {
                return sqlStep(s, where, ruleRows, orders);
            }
            if (storages.containsKey(s.targetSystem())) {
                return storageStep(s, where, ruleRows, orders);
            }
            ResolvedTarget resolved = targets.get(s.targetSystem());
            GatewayProperties.TargetSystem target = resolved == null ? null : resolved.system();
            if (target == null) {
                errors.add(where + ": target_system '" + s.targetSystem()
                        + "' is not configured (gw_target_system or gateway.target-systems)");
            }
            HttpMethod method = method(s.httpMethod(), where);
            if (s.pathTemplate() == null || !s.pathTemplate().startsWith("/")) {
                errors.add(where + ": path_template must start with '/'");
            }
            Condition condition = condition(s.conditionExpr(), where, "condition_expr");
            if (condition != null) {
                condition.references().forEach(p -> checkStepRefs(p, orders, s.stepOrder(), null, where + " condition_expr"));
            }
            Condition success = condition(s.successExpr(), where, "success_expr");
            if (success != null) {
                success.references().forEach(p -> checkStepRefs(p, orders, s.stepOrder(), s.name(), where + " success_expr"));
            }
            OnFailure onFailure = enumValue(OnFailure.class, s.onFailure() == null ? "STOP" : s.onFailure(), where, "on_failure");
            Integer targetTimeout = target == null ? null : target.readTimeoutMs();
            Duration timeout = timeout(s.timeoutMs(),
                    targetTimeout != null ? Duration.ofMillis(targetTimeout) : defaultStepTimeout, where);
            CompiledSchema responseSchema = schema(s.responseSchemaCode(), where, "response_schema_code");
            MessageHandler requestHandler = handler(s.requestHandler(), MessageHandler.class, where, "request_handler");
            MessageHandler responseHandler = handler(s.responseHandler(), MessageHandler.class, where, "response_handler");
            BodyTemplate template = null;
            if (s.bodyTemplate() != null && !s.bodyTemplate().isBlank()) {
                try {
                    template = BodyTemplate.parse(s.bodyTemplate());
                    template.references().forEach(p -> checkStepRefs(p, orders, s.stepOrder(), null, where + " body_template"));
                } catch (IllegalArgumentException e) {
                    errors.add(where + ": body_template: " + e.getMessage());
                }
            }
            String codecName = JsonCodec.BEAN_NAME;
            BodyCodec codec = JsonCodec.INSTANCE;
            if (s.bodyCodec() != null && !s.bodyCodec().isBlank()) {
                codecName = s.bodyCodec();
                codec = codec(codecName, where, "body_codec");
            } else if (target != null && target.bodyCodec() != null && !target.bodyCodec().isBlank()) {
                codecName = target.bodyCodec();
                codec = targetCodecs.get(s.targetSystem()); // null when invalid, already reported for the target
            } else if (template != null && template.kind() == BodyTemplate.Kind.XML) {
                // an XML template expects XML back: a SOAP envelope (decoded to its Body) or plain XML
                codecName = s.bodyTemplate().contains(":Envelope") || s.bodyTemplate().contains("<Envelope")
                        ? "soapCodec" : "xmlCodec";
                codec = codec(codecName, where, "body_codec (default for an XML body_template)");
            }

            List<CompiledRule> rules = new ArrayList<>();
            for (RuleRow r : ruleRows) {
                String rw = "mapping rule " + r.id() + " of " + where;
                CompiledRule rule = rule(r, rw, Set.of(TargetType.values()), STEP_REQUEST);
                if (rule != null) {
                    checkStepRefs(rule.source(), orders, s.stepOrder(), null, rw);
                    rules.add(rule);
                }
            }
            if (s.pathTemplate() != null) {
                Set<String> pathTargets = rules.stream().filter(r -> r.targetType() == TargetType.PATH)
                        .map(CompiledRule::targetName).collect(Collectors.toSet());
                Matcher m = TEMPLATE_VAR.matcher(s.pathTemplate());
                while (m.find()) {
                    if (!pathTargets.contains(m.group(1))) {
                        errors.add(where + ": path_template variable {" + m.group(1) + "} has no PATH mapping rule");
                    }
                }
            }
            return new StepDefinition(s.name(), s.stepOrder(), s.targetSystem(), target, method, s.pathTemplate(),
                    condition, success, onFailure, timeout, responseSchema, requestHandler, responseHandler, codec, codecName,
                    rules, null, null, template);
        }

        /**
         * A file storage step: {@code target_system} names a storage of {@code gateway.storages}; {@code http_method}
         * PUT stores the file of the BODY rule writing {@code $.file} ({@code $.request.files.<field>}), DELETE
         * removes; {@code path_template} is the object key, its {@code {vars}} PATH rules or built-ins
         * ({@link StorageKeys#BUILT_INS}).
         */
        private StepDefinition storageStep(StepRow s, String where, List<RuleRow> ruleRows, Map<String, Integer> orders) {
            FileStore store = storages.get(s.targetSystem());
            if (targets.containsKey(s.targetSystem())) {
                errors.add(where + ": '" + s.targetSystem() + "' is both a target system and a file storage; rename one");
            }
            HttpMethod method = s.httpMethod() == null ? null : HttpMethod.valueOf(s.httpMethod().strip().toUpperCase(Locale.ROOT));
            if (method != HttpMethod.PUT && method != HttpMethod.DELETE) {
                errors.add(where + ": a file storage step's http_method is PUT (store the file) or DELETE, not "
                        + s.httpMethod());
            }
            String keyProblem = StorageKeys.check(s.pathTemplate());
            if (keyProblem != null) {
                errors.add(where + ": " + keyProblem);
            }
            Condition condition = condition(s.conditionExpr(), where, "condition_expr");
            if (condition != null) {
                condition.references().forEach(p -> checkStepRefs(p, orders, s.stepOrder(), null, where + " condition_expr"));
            }
            Condition success = condition(s.successExpr(), where, "success_expr");
            if (success != null) {
                success.references().forEach(p -> checkStepRefs(p, orders, s.stepOrder(), s.name(), where + " success_expr"));
            }
            OnFailure onFailure = enumValue(OnFailure.class, s.onFailure() == null ? "STOP" : s.onFailure(), where, "on_failure");
            Duration timeout = timeout(s.timeoutMs(), defaultStepTimeout, where);
            CompiledSchema responseSchema = schema(s.responseSchemaCode(), where, "response_schema_code");
            MessageHandler requestHandler = handler(s.requestHandler(), MessageHandler.class, where, "request_handler");
            MessageHandler responseHandler = handler(s.responseHandler(), MessageHandler.class, where, "response_handler");

            List<CompiledRule> rules = new ArrayList<>();
            Set<String> pathTargets = new HashSet<>();
            boolean setsFile = false;
            for (RuleRow r : ruleRows) {
                String rw = "mapping rule " + r.id() + " of " + where;
                CompiledRule rule = rule(r, rw, Set.of(TargetType.BODY, TargetType.PATH), "a file storage step (BODY $.file / $.contentType, PATH key variables)");
                if (rule != null) {
                    checkStepRefs(rule.source(), orders, s.stepOrder(), null, rw);
                    rules.add(rule);
                    if (rule.targetType() == TargetType.PATH) {
                        pathTargets.add(rule.targetName());
                    } else if (rule.targetPath() != null && List.of("file").equals(rule.targetPath().fieldNames())) {
                        setsFile = true;
                    }
                }
            }
            if (keyProblem == null) {
                for (String v : StorageKeys.variables(s.pathTemplate())) {
                    if (!pathTargets.contains(v) && !StorageKeys.BUILT_INS.contains(v)) {
                        errors.add(where + ": key variable {" + v + "} has no PATH mapping rule and is not one of "
                                + new java.util.TreeSet<>(StorageKeys.BUILT_INS));
                    }
                }
            }
            if (method == HttpMethod.PUT && !setsFile && requestHandler == null) {
                errors.add(where + ": a PUT file storage step needs a BODY rule writing $.file (e.g. from $.request.files.file)");
            }
            if (method == null) {
                return null;
            }
            return new StepDefinition(s.name(), s.stepOrder(), s.targetSystem(), null, method, s.pathTemplate(),
                    condition, success, onFailure, timeout, responseSchema, requestHandler, responseHandler,
                    JsonCodec.INSTANCE, JsonCodec.BEAN_NAME, rules, null, store, null);
        }

        /**
         * A database query step: {@code target_system} names a datasource, {@code sql_text} is one statement, and
         * every {@code :name} in it needs a BODY rule writing {@code $.name} (the step's request mapping builds the
         * parameters). http_method, path_template and body_codec do not apply.
         */
        private StepDefinition sqlStep(StepRow s, String where, List<RuleRow> ruleRows, Map<String, Integer> orders) {
            SqlDatasources.Entry ds = sqlDatasources.find(s.targetSystem());
            if (ds == null) {
                errors.add(where + ": target_system '" + s.targetSystem()
                        + "' is not a SQL datasource (gateway.sql.datasources)");
            }
            SqlText sql = null;
            try {
                sql = SqlText.parse(s.sqlText());
            } catch (IllegalArgumentException e) {
                errors.add(where + ": sql_text: " + e.getMessage());
            }
            if (sql != null && ds != null && ds.readOnly() && !sql.isQuery()) {
                errors.add(where + ": datasource '" + ds.name() + "' is read-only: sql_text must be a SELECT or WITH"
                        + " query, not " + sql.firstKeyword());
            }
            Condition condition = condition(s.conditionExpr(), where, "condition_expr");
            if (condition != null) {
                condition.references().forEach(p -> checkStepRefs(p, orders, s.stepOrder(), null, where + " condition_expr"));
            }
            Condition success = condition(s.successExpr(), where, "success_expr");
            if (success != null) {
                success.references().forEach(p -> checkStepRefs(p, orders, s.stepOrder(), s.name(), where + " success_expr"));
            }
            OnFailure onFailure = enumValue(OnFailure.class, s.onFailure() == null ? "STOP" : s.onFailure(), where, "on_failure");
            Duration timeout = timeout(s.timeoutMs(), defaultStepTimeout, where);
            CompiledSchema responseSchema = schema(s.responseSchemaCode(), where, "response_schema_code");
            MessageHandler requestHandler = handler(s.requestHandler(), MessageHandler.class, where, "request_handler");
            MessageHandler responseHandler = handler(s.responseHandler(), MessageHandler.class, where, "response_handler");

            List<CompiledRule> rules = new ArrayList<>();
            Set<String> provided = new HashSet<>();
            for (RuleRow r : ruleRows) {
                String rw = "mapping rule " + r.id() + " of " + where;
                CompiledRule rule = rule(r, rw, Set.of(TargetType.BODY), "a database query step (its rules set :parameters)");
                if (rule != null) {
                    checkStepRefs(rule.source(), orders, s.stepOrder(), null, rw);
                    rules.add(rule);
                    if (rule.targetPath() != null && !rule.targetPath().fieldNames().isEmpty()) {
                        provided.add(rule.targetPath().fieldNames().getFirst());
                    }
                }
            }
            if (sql != null && requestHandler == null) {
                for (String p : sql.parameterNames()) {
                    if (!provided.contains(p)) {
                        errors.add(where + ": SQL parameter :" + p + " has no mapping rule (a BODY rule writing $." + p + ")");
                    }
                }
            }
            if (ds == null || sql == null) {
                return null;
            }
            return new StepDefinition(s.name(), s.stepOrder(), s.targetSystem(), null, null, null, condition, success,
                    onFailure, timeout, responseSchema, requestHandler, responseHandler, JsonCodec.INSTANCE,
                    JsonCodec.BEAN_NAME, rules, new SqlStatement(sql, ds, sqlDatasources.maxRows()), null, null);
        }

        private CompiledRule rule(RuleRow r, String where, Set<TargetType> allowedTargets, String phase) {
            int before = errors.size();
            TargetType targetType = enumValue(TargetType.class, r.targetType(), where, "target_type");
            if (targetType != null && !allowedTargets.contains(targetType)) {
                errors.add(where + ": target_type " + targetType + " is not allowed for " + phase);
            }
            JsonPath target = null;
            if (targetType == TargetType.BODY) {
                target = path(r.targetPath(), where, "target_path");
            } else if (targetType != null && (r.targetPath() == null || r.targetPath().isBlank())) {
                errors.add(where + ": target_path must be a " + targetType + " name");
            }
            JsonPath source = r.sourcePath() == null ? null : path(r.sourcePath(), where, "source_path");
            if (source != null && !CONTEXT_ROOTS.contains(source.firstField())) {
                errors.add(where + ": source_path must start with $.request, $.steps or $.correlationId");
            }
            boolean hasSource = r.sourcePath() != null;
            boolean hasConstant = r.constantValue() != null;
            if (hasSource && hasConstant || !hasSource && !hasConstant && r.fieldHandler() == null) {
                errors.add(where + ": must set exactly one of source_path / constant_value (or a field_handler)");
            }
            int sourceWildcards = source == null ? 0 : source.wildcardCount();
            if (targetType != null && targetType != TargetType.BODY && sourceWildcards > 0) {
                errors.add(where + ": a wildcard source cannot be written to a " + targetType + " target");
            }
            if (target != null) {
                int targetWildcards = target.wildcardCount();
                if (targetWildcards > 0 && targetWildcards != sourceWildcards) {
                    errors.add(where + ": target has " + targetWildcards + " [*] but source has " + sourceWildcards
                            + "; wildcard counts must match (or the target must have none)");
                }
            }
            Converters.Converter converter = null;
            if (r.converter() != null) {
                try {
                    converter = Converters.parse(r.converter());
                } catch (IllegalArgumentException e) {
                    errors.add(where + ": converter '" + r.converter() + "': " + e.getMessage());
                }
            }
            LookupTable lookup = null;
            if (r.lookupCode() != null) {
                lookup = lookups.get(r.lookupCode());
                if (lookup == null) {
                    errors.add(where + ": lookup_code '" + r.lookupCode() + "' has no gw_lookup_entry rows");
                }
            }
            FieldHandler fieldHandler = handler(r.fieldHandler(), FieldHandler.class, where, "field_handler");
            if (errors.size() > before) {
                return null;
            }
            return new CompiledRule(r.id(), r.seq(), targetType, target,
                    targetType == TargetType.BODY ? null : r.targetPath(), source,
                    JsonValues.parseLiteral(r.constantValue()), JsonValues.parseLiteral(r.defaultValue()),
                    converter, lookup, fieldHandler, r.fieldHandler(), r.required());
        }

        /**
         * A reference to {@code $.steps.X} must name an existing step that runs before {@code beforeOrder};
         * {@code self} (for success_expr) may also be referenced.
         */
        private void checkStepRefs(JsonPath path, Map<String, Integer> orders, int beforeOrder, String self, String where) {
            if (path == null || !"steps".equals(path.firstField()) || path.secondField() == null) {
                return;
            }
            String ref = path.secondField();
            Integer refOrder = orders.get(ref);
            if (refOrder == null) {
                errors.add(where + ": references unknown step '" + ref + "'");
            } else if (!ref.equals(self) && refOrder >= beforeOrder) {
                errors.add(where + ": references step '" + ref + "' which does not run before it");
            }
        }

        private Condition condition(String expr, String where, String column) {
            if (expr == null || expr.isBlank()) {
                return null;
            }
            try {
                return Condition.compile(expr);
            } catch (IllegalArgumentException e) {
                errors.add(where + ": " + column + ": " + e.getMessage());
                return null;
            }
        }

        private JsonPath path(String text, String where, String column) {
            try {
                return JsonPath.compile(text);
            } catch (IllegalArgumentException e) {
                errors.add(where + ": " + column + ": " + e.getMessage());
                return null;
            }
        }

        private CompiledSchema schema(String code, String where, String column) {
            if (code == null || code.isBlank()) {
                return null;
            }
            CompiledSchema s = schemas.get(code);
            if (s == null && rows.schemas().stream().noneMatch(r -> r.code().equals(code))) {
                errors.add(where + ": " + column + " references unknown schema '" + code + "'");
            }
            return s;
        }

        private <T> T handler(String name, Class<T> type, String where, String column) {
            if (name == null || name.isBlank()) {
                return null;
            }
            T bean = handlers.find(name, type);
            if (bean == null) {
                errors.add(where + ": " + column + " '" + name + "' is not a " + type.getSimpleName() + " bean");
            }
            return bean;
        }

        private HttpMethod method(String text, String where) {
            if (text == null || !METHODS.contains(text.toUpperCase(Locale.ROOT))) {
                errors.add(where + ": http_method '" + text + "' must be one of " + METHODS);
                return null;
            }
            return HttpMethod.valueOf(text.toUpperCase(Locale.ROOT));
        }

        private PathPattern pathPattern(String text, String where) {
            if (text == null || !text.startsWith("/")) {
                errors.add(where + ": path_pattern '" + text + "' must start with '/'");
                return null;
            }
            try {
                return PathPatternParser.defaultInstance.parse(text);
            } catch (PatternParseException e) {
                errors.add(where + ": path_pattern '" + text + "' is invalid: " + e.getMessage());
                return null;
            }
        }

        private Duration timeout(Integer ms, Duration fallback, String where) {
            if (ms == null) {
                return fallback;
            }
            if (ms <= 0) {
                errors.add(where + ": timeout_ms must be > 0");
                return fallback;
            }
            return Duration.ofMillis(ms);
        }

        private <E extends Enum<E>> E enumValue(Class<E> type, String text, String where, String column) {
            try {
                return Enum.valueOf(type, text.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException | NullPointerException e) {
                errors.add(where + ": " + column + " '" + text + "' is not one of "
                        + java.util.Arrays.toString(type.getEnumConstants()));
                return null;
            }
        }
    }
}
