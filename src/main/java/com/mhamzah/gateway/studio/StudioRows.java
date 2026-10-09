package com.mhamzah.gateway.studio;

import com.mhamzah.gateway.config.ConfigRows;
import com.mhamzah.gateway.config.ConfigRows.FlowRow;
import com.mhamzah.gateway.config.ConfigRows.LookupRow;
import com.mhamzah.gateway.config.ConfigRows.RuleRow;
import com.mhamzah.gateway.config.ConfigRows.SchemaRow;
import com.mhamzah.gateway.config.ConfigRows.StepRow;
import com.mhamzah.gateway.config.ConfigRows.TargetHeaderRow;
import com.mhamzah.gateway.config.ConfigRows.StorageRow;
import com.mhamzah.gateway.config.ConfigRows.TargetRow;
import com.mhamzah.gateway.mapping.LookupTable;
import com.mhamzah.gateway.studio.StudioConfig.Entry;
import com.mhamzah.gateway.studio.StudioConfig.Flow;
import com.mhamzah.gateway.studio.StudioConfig.Header;
import com.mhamzah.gateway.studio.StudioConfig.Lookup;
import com.mhamzah.gateway.studio.StudioConfig.Rule;
import com.mhamzah.gateway.studio.StudioConfig.Schema;
import com.mhamzah.gateway.studio.StudioConfig.Step;
import com.mhamzah.gateway.studio.StudioConfig.Target;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Converts between database rows and {@link StudioConfig}, and checks what the config compiler does not. */
final class StudioRows {

    static final String STEP_REQUEST = "STEP_REQUEST";
    static final String FLOW_RESPONSE = "FLOW_RESPONSE";

    private StudioRows() {}

    /**
     * Rows to document, in a stable order (flows, targets, lookups and schemas by code; steps by order, then name).
     * {@code schemaDescriptions} is {@code gw_json_schema.description} by code, which the config rows leave out.
     */
    static StudioConfig fromRows(ConfigRows rows, Map<String, String> schemaDescriptions) {
        Map<Long, List<StepRow>> stepsByFlow = rows.steps().stream().collect(Collectors.groupingBy(StepRow::flowId));
        Map<Long, List<RuleRow>> rulesByStep = rows.rules().stream()
                .filter(r -> r.stepId() != null && STEP_REQUEST.equals(r.phase()))
                .collect(Collectors.groupingBy(RuleRow::stepId));
        Map<Long, List<RuleRow>> responseByFlow = rows.rules().stream()
                .filter(r -> r.stepId() == null && FLOW_RESPONSE.equals(r.phase()))
                .collect(Collectors.groupingBy(RuleRow::flowId));

        List<Flow> flows = rows.flows().stream()
                .sorted(Comparator.comparing(FlowRow::code, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(f -> new Flow(f.code(), f.name(), f.httpMethod(), f.pathPattern(), f.requestSchemaCode(),
                        f.responseSchemaCode(), f.requestHandler(), f.responseHandler(), f.errorHandler(),
                        f.successStatus(), f.timeoutMs(), f.auditMode(), f.enabled(),
                        stepsByFlow.getOrDefault(f.id(), List.of()).stream()
                                .sorted(Comparator.comparingInt(StepRow::stepOrder).thenComparing(StepRow::name,
                                        Comparator.nullsFirst(Comparator.naturalOrder())))
                                .map(s -> new Step(s.name(), s.stepOrder(), s.targetSystem(), s.httpMethod(),
                                        s.pathTemplate(), s.conditionExpr(), s.successExpr(), s.onFailure(),
                                        s.timeoutMs(), s.responseSchemaCode(), s.requestHandler(),
                                        s.responseHandler(), s.bodyCodec(), s.enabled(),
                                        rules(rulesByStep.getOrDefault(s.id(), List.of())), s.sqlText(),
                                        s.bodyTemplate()))
                                .toList(),
                        rules(responseByFlow.getOrDefault(f.id(), List.of()))))
                .toList();

        Map<String, List<Header>> headers = new TreeMap<>();
        rows.targetHeaders().stream()
                .sorted(Comparator.comparing(TargetHeaderRow::headerName, Comparator.nullsFirst(Comparator.naturalOrder())))
                .forEach(h -> headers.computeIfAbsent(h.targetCode(), k -> new ArrayList<>())
                        .add(new Header(h.headerName(), h.headerValue())));
        List<Target> targets = rows.targets().stream()
                .sorted(Comparator.comparing(TargetRow::code, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(t -> new Target(t.code(), t.baseUrl(), t.connectTimeoutMs(), t.readTimeoutMs(), t.bodyCodec(),
                        t.enabled(), headers.getOrDefault(t.code(), List.of()),
                        new StudioConfig.Tls(t.tlsMode(), t.tlsTrustStore(), t.tlsTrustStorePassword(),
                                t.tlsKeyStore(), t.tlsKeyStorePassword())))
                .toList();

        Map<String, List<Entry>> entries = new TreeMap<>();
        rows.lookups().stream()
                .sorted(Comparator.comparing((LookupRow l) -> LookupTable.FALLBACK_KEY.equals(l.sourceValue()))
                        .thenComparing(LookupRow::sourceValue, Comparator.nullsFirst(Comparator.naturalOrder())))
                .forEach(l -> entries.computeIfAbsent(l.lookupCode(), k -> new ArrayList<>())
                        .add(new Entry(l.sourceValue(), l.targetValue())));
        List<Lookup> lookups = entries.entrySet().stream().map(e -> new Lookup(e.getKey(), e.getValue())).toList();

        List<Schema> schemas = rows.schemas().stream()
                .sorted(Comparator.comparing(SchemaRow::code, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(x -> new Schema(x.code(), schemaDescriptions.get(x.code()), x.schemaText()))
                .toList();

        List<StudioConfig.Storage> storages = rows.storages().stream()
                .sorted(Comparator.comparing(StorageRow::code, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(s -> new StudioConfig.Storage(s.code(), s.storageType(), s.baseDir(), s.bucket(), s.keyPrefix(),
                        s.region(), s.endpoint(), s.pathStyle(), s.accessKey(), s.secretKey(), s.allowedTypes(),
                        s.maxSize(), s.enabled()))
                .toList();
        return new StudioConfig(null, flows, targets, lookups, schemas, storages);
    }

    private static List<Rule> rules(List<RuleRow> rows) {
        return rows.stream()
                .sorted(Comparator.comparingInt(RuleRow::seq))
                .map(r -> new Rule(r.targetType(), r.targetPath(), r.sourcePath(), r.constantValue(),
                        r.defaultValue(), r.lookupCode(), r.converter(), r.fieldHandler(), r.required()))
                .toList();
    }

    /**
     * Document to rows, with ids numbered from 1 per table, blank text as null and the database defaults filled in
     * ({@code success_status} 200, {@code audit_mode} INHERIT, {@code on_failure} STOP, {@code target_type} BODY).
     * The ids only link the rows to each other; the database assigns its own when they are inserted. A rule's id is
     * its position in its list, so compiler messages ("mapping rule 3 of flow 'X' step 'y'") match the Studio.
     */
    static ConfigRows toRows(StudioConfig config) {
        List<FlowRow> flows = new ArrayList<>();
        List<StepRow> steps = new ArrayList<>();
        List<RuleRow> rules = new ArrayList<>();
        long flowId = 0;
        long stepId = 0;
        for (Flow f : config.flows()) {
            flowId++;
            flows.add(new FlowRow(flowId, text(f.code()), text(f.name()), text(f.method()), text(f.path()),
                    text(f.requestSchema()), text(f.responseSchema()), text(f.requestHandler()),
                    text(f.responseHandler()), text(f.errorHandler()),
                    f.successStatus() == null ? 200 : f.successStatus(), f.timeoutMs(),
                    or(f.auditMode(), "INHERIT"), f.enabled()));
            for (Step s : f.steps()) {
                stepId++;
                // a database query step has no HTTP method, path or wire format
                boolean sql = text(s.sql()) != null;
                steps.add(new StepRow(stepId, flowId, text(s.name()), s.order(), text(s.targetSystem()),
                        sql ? null : text(s.method()), sql ? null : text(s.path()), text(s.condition()),
                        text(s.success()), or(s.onFailure(), "STOP"), s.timeoutMs(), text(s.responseSchema()),
                        text(s.requestHandler()), text(s.responseHandler()), sql ? null : text(s.bodyCodec()),
                        s.enabled(), sql ? s.sql().strip() : null, sql || s.bodyTemplate() == null || s.bodyTemplate().isBlank() ? null : s.bodyTemplate()));
                addRules(rules, s.rules(), flowId, stepId, STEP_REQUEST);
            }
            addRules(rules, f.response(), flowId, null, FLOW_RESPONSE);
        }

        List<TargetRow> targets = new ArrayList<>();
        List<TargetHeaderRow> headers = new ArrayList<>();
        long targetId = 0;
        long headerId = 0;
        for (Target t : config.targets()) {
            StudioConfig.Tls tls = t.tls();
            String mode = text(tls.mode()) == null || "VERIFY".equalsIgnoreCase(tls.mode().strip()) ? null
                    : tls.mode().strip().toUpperCase(java.util.Locale.ROOT);
            targets.add(new TargetRow(++targetId, text(t.code()), text(t.baseUrl()), t.connectTimeoutMs(),
                    t.readTimeoutMs(), text(t.bodyCodec()), t.enabled(), mode, text(tls.trustStore()),
                    text(tls.trustStorePassword()), text(tls.keyStore()), text(tls.keyStorePassword())));
            for (Header h : t.headers()) {
                headers.add(new TargetHeaderRow(++headerId, text(t.code()), text(h.name()),
                        h.value() == null ? "" : h.value()));
            }
        }

        List<LookupRow> lookups = new ArrayList<>();
        long lookupId = 0;
        for (Lookup l : config.lookups()) {
            for (Entry e : l.entries()) {
                lookups.add(new LookupRow(++lookupId, text(l.code()), e.source(), e.target()));
            }
        }
        List<SchemaRow> schemas = new ArrayList<>();
        long schemaId = 0;
        for (Schema x : config.schemas()) {
            schemas.add(new SchemaRow(++schemaId, text(x.code()), x.text()));
        }
        List<StorageRow> storages = new ArrayList<>();
        long storageId = 0;
        for (StudioConfig.Storage s : config.storages()) {
            String type = text(s.type()) == null ? "LOCAL" : s.type().strip().toUpperCase(java.util.Locale.ROOT);
            boolean s3 = "S3".equals(type);
            storages.add(new StorageRow(++storageId, text(s.code()), type, s3 ? null : text(s.baseDir()),
                    s3 ? text(s.bucket()) : null, s3 ? text(s.prefix()) : null, s3 ? text(s.region()) : null,
                    s3 ? text(s.endpoint()) : null, s3 && s.pathStyle(), s3 ? text(s.accessKey()) : null,
                    s3 ? text(s.secretKey()) : null, text(s.allowedTypes()), text(s.maxSize()), s.enabled()));
        }
        return new ConfigRows(flows, steps, rules, lookups, schemas, targets, headers, storages);
    }

    private static void addRules(List<RuleRow> out, List<Rule> rules, long flowId, Long stepId, String phase) {
        int seq = 0;
        for (Rule r : rules) {
            seq++;
            out.add(new RuleRow(seq, flowId, stepId, phase, seq, or(r.type(), "BODY"), text(r.target()),
                    text(r.source()), text(r.constant()), text(r.defaultValue()), text(r.converter()),
                    text(r.lookup()), text(r.fieldHandler()), r.required()));
        }
    }

    /**
     * Problems the config compiler does not report because it skips disabled rows, but that the database would
     * reject on insert (NOT NULL and unique constraints).
     */
    static List<String> problems(ConfigRows rows) {
        List<String> errors = new ArrayList<>();
        Map<Long, String> flowCodes = new TreeMap<>();
        for (FlowRow f : rows.flows()) {
            flowCodes.put(f.id(), f.code());
            String where = "flow '" + f.code() + "'";
            if (f.httpMethod() == null) {
                errors.add(where + ": http_method is required");
            }
            if (f.pathPattern() == null) {
                errors.add(where + ": path_pattern is required");
            }
        }
        Set<String> stepNames = new HashSet<>();
        Map<Long, String> stepWhere = new TreeMap<>();
        for (StepRow s : rows.steps()) {
            stepWhere.put(s.id(), "flow '" + flowCodes.get(s.flowId()) + "' step '" + s.name() + "'");
            String where = "flow '" + flowCodes.get(s.flowId()) + "' step '" + s.name() + "'";
            if (s.name() == null) {
                errors.add("flow '" + flowCodes.get(s.flowId()) + "': every step needs a name");
            } else if (!stepNames.add(s.flowId() + "/" + s.name())) {
                errors.add(where + ": duplicate step name (unique per flow)");
            }
            if (s.targetSystem() == null) {
                errors.add(where + ": target_system is required");
            }
            if (s.httpMethod() == null && !s.isSql()) {
                errors.add(where + ": http_method is required");
            }
            if (s.pathTemplate() == null && !s.isSql()) {
                errors.add(where + ": path_template is required");
            }
        }
        for (RuleRow r : rows.rules()) {
            if (r.targetPath() == null) {
                errors.add("mapping rule " + r.seq() + " of " + (r.stepId() == null
                        ? "flow '" + flowCodes.get(r.flowId()) + "'" : stepWhere.get(r.stepId()))
                        + ": target_path is required");
            }
        }
        for (StorageRow s : rows.storages()) {
            if (s.code() == null) {
                errors.add("storage '': every storage needs a code");
            } else if (rows.targets().stream().anyMatch(t -> s.code().equals(t.code()))) {
                errors.add("storage '" + s.code() + "': a target system has the same code");
            }
        }
        Set<String> targetCodes = new HashSet<>();
        for (TargetRow t : rows.targets()) {
            String where = "target system '" + t.code() + "'";
            if (t.code() != null && !targetCodes.add(t.code())) {
                errors.add(where + ": duplicate code");
            }
            if (t.baseUrl() == null) {
                errors.add(where + ": base_url is required");
            }
        }
        Set<String> headerNames = new HashSet<>();
        for (TargetHeaderRow h : rows.targetHeaders()) {
            String where = "target system '" + h.targetCode() + "' header '" + h.headerName() + "'";
            if (h.headerName() == null) {
                errors.add("target system '" + h.targetCode() + "': every header needs a name");
            } else if (!headerNames.add(h.targetCode() + "/" + h.headerName())) {
                errors.add(where + ": duplicate header name");
            }
        }
        Set<String> lookupKeys = new HashSet<>();
        for (LookupRow l : rows.lookups()) {
            String where = "lookup '" + l.lookupCode() + "'";
            if (l.lookupCode() == null) {
                errors.add("every lookup needs a lookup_code");
            } else if (l.sourceValue() == null || l.sourceValue().isEmpty()) {
                errors.add(where + ": source_value must not be empty");
            } else if (!lookupKeys.add(l.lookupCode() + "/" + l.sourceValue())) {
                errors.add(where + ": duplicate source_value '" + l.sourceValue() + "' (unique lookup_code, source_value)");
            }
            if (l.targetValue() == null || l.targetValue().isEmpty()) {
                errors.add(where + " source_value '" + l.sourceValue() + "': target_value must not be empty");
            }
        }
        Set<String> schemaCodes = new HashSet<>();
        for (SchemaRow x : rows.schemas()) {
            if (x.code() == null) {
                errors.add("every json schema needs a code");
            } else if (!schemaCodes.add(x.code())) {
                errors.add("json schema '" + x.code() + "': duplicate code");
            }
            if (x.schemaText() == null || x.schemaText().isBlank()) {
                errors.add("json schema '" + x.code() + "': schema_text must not be empty");
            }
        }
        return errors;
    }

    /** Blank text is stored as NULL (Oracle cannot tell them apart anyway). */
    private static String text(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String or(String s, String fallback) {
        String t = text(s);
        return t == null ? fallback : t;
    }
}
