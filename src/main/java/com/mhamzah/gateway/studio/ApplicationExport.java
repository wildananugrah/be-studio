package com.mhamzah.gateway.studio;

import com.mhamzah.gateway.config.ConfigRows;
import com.mhamzah.gateway.config.ConfigRows.FlowRow;
import com.mhamzah.gateway.config.ConfigRows.RuleRow;
import com.mhamzah.gateway.config.ConfigRows.StepRow;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.mapping.JsonValues;
import com.mhamzah.gateway.studio.StudioConfig.Application;
import com.mhamzah.gateway.studio.StudioConfig.Flow;
import com.mhamzah.gateway.studio.StudioConfig.Header;
import com.mhamzah.gateway.studio.StudioConfig.Lookup;
import com.mhamzah.gateway.studio.StudioConfig.Rule;
import com.mhamzah.gateway.studio.StudioConfig.Schema;
import com.mhamzah.gateway.studio.StudioConfig.Step;
import com.mhamzah.gateway.studio.StudioConfig.Storage;
import com.mhamzah.gateway.studio.StudioConfig.Target;
import com.mhamzah.gateway.studio.StudioConfig.Tls;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One application's configuration, extracted for another environment: its flows (with steps and mapping rules) and
 * the shared target systems, lookups, JSON schemas and storages those flows use. Written as JSON (Studio imports it),
 * a SQL script or a Liquibase changelog. Applying one replaces the application and its flows; shared items are only
 * added when the target database does not have them yet, so an export never changes what other applications use.
 * Secrets stored as plain text (TLS passwords, storage keys, credential-like headers) are replaced by
 * {@code ${...}} placeholders, listed in {@link Bundle#replacedSecrets()}.
 */
final class ApplicationExport {

    /** The SQL dialect of the generated script (booleans and long text differ). */
    enum Dialect { POSTGRES, ORACLE }

    /** What gets exported. {@code requires} lists what must exist on the target gateway but is not exported. */
    record Bundle(Application application, List<Flow> flows, List<Target> targets, List<Lookup> lookups,
            List<Schema> schemas, List<Storage> storages, Map<String, List<String>> requires,
            List<String> replacedSecrets) {}

    private static final Pattern SECRET_HEADER = Pattern.compile("(?i).*(auth|token|secret|password|api[-_]?key|signature).*");

    private ApplicationExport() {}

    // ------------------------------------------------------------------ what belongs to the application

    /** @throws IllegalArgumentException when there is no application {@code code} */
    static Bundle bundle(StudioConfig config, String code) {
        Application app = config.applications().stream().filter(a -> code.equals(a.code())).findFirst()
                .orElseGet(() -> StudioRows.DEFAULT_APPLICATION.equals(code)
                        ? new Application(code, "Default application", null) : null);
        if (app == null) {
            throw new IllegalArgumentException("unknown application '" + code + "'");
        }
        List<Flow> flows = config.flows().stream()
                .filter(f -> code.equals(f.application() == null ? StudioRows.DEFAULT_APPLICATION : f.application()))
                .toList();

        Set<String> targetCodes = new TreeSet<>();
        Set<String> schemaCodes = new TreeSet<>();
        Set<String> lookupCodes = new TreeSet<>();
        Set<String> handlers = new TreeSet<>();
        Set<String> codecs = new TreeSet<>();
        for (Flow f : flows) {
            addIf(schemaCodes, f.requestSchema());
            addIf(schemaCodes, f.responseSchema());
            addIf(handlers, f.requestHandler());
            addIf(handlers, f.responseHandler());
            addIf(handlers, f.errorHandler());
            f.response().forEach(r -> rule(r, lookupCodes, handlers));
            f.responses().forEach(x -> x.rules().forEach(r -> rule(r, lookupCodes, handlers)));
            for (Step s : f.steps()) {
                if (!com.mhamzah.gateway.config.StepDefinition.TRANSFORM.equals(s.targetSystem())
                        && !com.mhamzah.gateway.config.StepDefinition.CONDITION.equals(s.targetSystem())) {
                    addIf(targetCodes, s.targetSystem()); // a transform step names no target system
                }
                addIf(schemaCodes, s.responseSchema());
                addIf(handlers, s.requestHandler());
                addIf(handlers, s.responseHandler());
                addIf(codecs, s.bodyCodec());
                s.rules().forEach(r -> rule(r, lookupCodes, handlers));
            }
        }
        List<String> secrets = new ArrayList<>();
        List<Target> targets = config.targets().stream().filter(t -> targetCodes.contains(t.code()))
                .map(t -> withoutSecrets(t, secrets)).toList();
        List<Storage> storages = config.storages().stream().filter(s -> targetCodes.contains(s.code()))
                .map(s -> withoutSecrets(s, secrets)).toList();
        List<Lookup> lookups = config.lookups().stream().filter(l -> lookupCodes.contains(l.code())).toList();
        List<Schema> schemas = config.schemas().stream().filter(s -> schemaCodes.contains(s.code())).toList();

        // used by the flows but not exported: they live in application.yml or in code on the target gateway
        Set<String> inDatabase = new LinkedHashSet<>();
        targets.forEach(t -> inDatabase.add(t.code()));
        storages.forEach(s -> inDatabase.add(s.code()));
        Set<String> elsewhere = new TreeSet<>(targetCodes);
        elsewhere.removeAll(inDatabase);
        Map<String, List<String>> requires = new LinkedHashMap<>();
        requires.put("targetSystemsOrDatasourcesFromApplicationYml", List.copyOf(elsewhere));
        requires.put("handlerBeans", List.copyOf(handlers));
        requires.put("bodyCodecBeans", List.copyOf(codecs));
        Set<String> missingSchemas = new TreeSet<>(schemaCodes);
        schemas.forEach(s -> missingSchemas.remove(s.code()));
        Set<String> missingLookups = new TreeSet<>(lookupCodes);
        lookups.forEach(l -> missingLookups.remove(l.code()));
        if (!missingSchemas.isEmpty()) {
            requires.put("schemasNotFound", List.copyOf(missingSchemas));
        }
        if (!missingLookups.isEmpty()) {
            requires.put("lookupsNotFound", List.copyOf(missingLookups));
        }
        return new Bundle(app, flows, targets, lookups, schemas, storages, requires, secrets);
    }

    private static void rule(Rule r, Set<String> lookups, Set<String> handlers) {
        addIf(lookups, r.lookup());
        addIf(handlers, r.fieldHandler());
    }

    private static void addIf(Set<String> set, String value) {
        if (value != null && !value.isBlank()) {
            set.add(value.strip());
        }
    }

    private static Target withoutSecrets(Target t, List<String> replaced) {
        Tls tls = t.tls();
        Tls clean = new Tls(tls.mode(), tls.trustStore(),
                secret(tls.trustStorePassword(), t.code() + "_TLS_TRUST_STORE_PASSWORD", replaced),
                tls.keyStore(), secret(tls.keyStorePassword(), t.code() + "_TLS_KEY_STORE_PASSWORD", replaced));
        List<Header> headers = t.headers().stream()
                .map(h -> SECRET_HEADER.matcher(h.name() == null ? "" : h.name()).matches()
                        ? new Header(h.name(), secret(h.value(), t.code() + "_HEADER_" + h.name(), replaced)) : h)
                .toList();
        return new Target(t.code(), t.baseUrl(), t.connectTimeoutMs(), t.readTimeoutMs(), t.bodyCodec(), t.enabled(),
                headers, clean);
    }

    private static Storage withoutSecrets(Storage s, List<String> replaced) {
        return new Storage(s.code(), s.type(), s.baseDir(), s.bucket(), s.prefix(), s.region(), s.endpoint(),
                s.pathStyle(), secret(s.accessKey(), s.code() + "_ACCESS_KEY", replaced),
                secret(s.secretKey(), s.code() + "_SECRET_KEY", replaced), s.allowedTypes(), s.maxSize(), s.enabled());
    }

    /** A value written as plain text becomes {@code ${NAME}}; placeholders and empty values stay as they are. */
    private static String secret(String value, String name, List<String> replaced) {
        if (value == null || value.isBlank() || value.contains("${")) {
            return value;
        }
        String variable = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        replaced.add(variable);
        return "${" + variable + "}";
    }

    /** The bundle as a config Studio can merge (flows, shared items) plus what it needs. */
    static StudioConfig asConfig(Bundle b) {
        return new StudioConfig(null, b.flows(), b.targets(), b.lookups(), b.schemas(), b.storages(),
                List.of(b.application()));
    }

    // ------------------------------------------------------------------ JSON

    static String json(Bundle b, String exportedFrom) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", "json-gateway-application");
        out.put("formatVersion", 1);
        out.put("exportedAt", OffsetDateTime.now().toString());
        out.put("exportedFrom", exportedFrom);
        out.put("application", b.application());
        out.put("flows", b.flows());
        out.put("targets", b.targets());
        out.put("lookups", b.lookups());
        out.put("schemas", b.schemas());
        out.put("storages", b.storages());
        out.put("requires", b.requires());
        out.put("replacedSecrets", b.replacedSecrets());
        return JsonValues.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out);
    }

    // ------------------------------------------------------------------ rows → statements

    /** A column value: a literal (text, number, boolean or null) or an SQL expression with {@code {table}} names. */
    private record Col(String name, Object value, boolean expression) {
        static Col v(String name, Object value) {
            return new Col(name, value, false);
        }

        static Col expr(String name, String sql) {
            return new Col(name, sql, true);
        }
    }

    /** An insert into a logical table ({@code flow}, {@code flow_step}, ...). */
    private record Insert(String table, List<Col> cols) {}

    /**
     * A shared item: its inserts, applied only when {@code missingCheck} (a COUNT(*) query, {@code {table}} names)
     * returns 0.
     */
    private record Shared(String id, String missingCheck, List<Insert> inserts) {}

    private record Plan(String app, List<String> flowCodes, List<Insert> application, List<Shared> shared,
            List<Insert> flows) {}

    private static Plan plan(Bundle b) {
        ConfigRows rows = StudioRows.toRows(asConfig(b));
        String app = b.application().code();
        List<Insert> application = List.of(new Insert("application", List.of(Col.v("code", app),
                Col.v("name", b.application().name()), Col.v("description", b.application().description()))));

        List<Shared> shared = new ArrayList<>();
        for (ConfigRows.TargetRow t : rows.targets()) {
            List<Insert> inserts = new ArrayList<>();
            inserts.add(new Insert("target_system", List.of(Col.v("code", t.code()), Col.v("base_url", t.baseUrl()),
                    Col.v("connect_timeout_ms", t.connectTimeoutMs()), Col.v("read_timeout_ms", t.readTimeoutMs()),
                    Col.v("body_codec", t.bodyCodec()), Col.v("enabled", t.enabled()), Col.v("tls_mode", t.tlsMode()),
                    Col.v("tls_trust_store", t.tlsTrustStore()),
                    Col.v("tls_trust_store_password", t.tlsTrustStorePassword()),
                    Col.v("tls_key_store", t.tlsKeyStore()), Col.v("tls_key_store_password", t.tlsKeyStorePassword()))));
            rows.targetHeaders().stream().filter(h -> h.targetCode().equals(t.code())).forEach(h -> inserts.add(
                    new Insert("target_system_header", List.of(Col.v("target_code", h.targetCode()),
                            Col.v("header_name", h.headerName()), Col.v("header_value", h.headerValue())))));
            shared.add(new Shared("target-" + t.code(),
                    "SELECT COUNT(*) FROM {target_system} WHERE code = " + quote(t.code()), inserts));
        }
        Map<String, List<ConfigRows.LookupRow>> lookups = rows.lookups().stream()
                .collect(Collectors.groupingBy(ConfigRows.LookupRow::lookupCode, LinkedHashMap::new, Collectors.toList()));
        lookups.forEach((code, entries) -> shared.add(new Shared("lookup-" + code,
                "SELECT COUNT(*) FROM {lookup_entry} WHERE lookup_code = " + quote(code),
                entries.stream().map(e -> new Insert("lookup_entry", List.of(Col.v("lookup_code", code),
                        Col.v("source_value", e.sourceValue()), Col.v("target_value", e.targetValue())))).toList())));
        for (Schema s : b.schemas()) {
            shared.add(new Shared("schema-" + s.code(), "SELECT COUNT(*) FROM {json_schema} WHERE code = " + quote(s.code()),
                    List.of(new Insert("json_schema", List.of(Col.v("code", s.code()), Col.v("schema_text", s.text()),
                            Col.v("description", s.description()))))));
        }
        for (ConfigRows.StorageRow s : rows.storages()) {
            shared.add(new Shared("storage-" + s.code(), "SELECT COUNT(*) FROM {storage} WHERE code = " + quote(s.code()),
                    List.of(new Insert("storage", List.of(Col.v("code", s.code()), Col.v("storage_type", s.storageType()),
                            Col.v("base_dir", s.baseDir()), Col.v("bucket", s.bucket()), Col.v("key_prefix", s.keyPrefix()),
                            Col.v("region", s.region()), Col.v("endpoint", s.endpoint()), Col.v("path_style", s.pathStyle()),
                            Col.v("access_key", s.accessKey()), Col.v("secret_key", s.secretKey()),
                            Col.v("allowed_types", s.allowedTypes()), Col.v("max_size", s.maxSize()),
                            Col.v("enabled", s.enabled()))))));
        }

        Map<Long, String> flowCode = new LinkedHashMap<>();
        Map<Long, StepRow> stepById = new LinkedHashMap<>();
        rows.flows().forEach(f -> flowCode.put(f.id(), f.code()));
        rows.steps().forEach(s -> stepById.put(s.id(), s));
        List<Insert> flows = new ArrayList<>();
        for (FlowRow f : rows.flows()) {
            flows.add(new Insert("flow", List.of(Col.v("code", f.code()), Col.v("name", f.name()),
                    Col.v("http_method", f.httpMethod()), Col.v("path_pattern", f.pathPattern()),
                    Col.v("request_schema_code", f.requestSchemaCode()), Col.v("response_schema_code", f.responseSchemaCode()),
                    Col.v("request_handler", f.requestHandler()), Col.v("response_handler", f.responseHandler()),
                    Col.v("error_handler", f.errorHandler()), Col.v("success_status", f.successStatus()),
                    Col.v("timeout_ms", f.timeoutMs()), Col.v("audit_mode", f.auditMode()), Col.v("enabled", f.enabled()),
                    Col.v("application_code", app))));
        }
        for (StepRow s : rows.steps()) {
            flows.add(new Insert("flow_step", List.of(Col.expr("flow_id", flowId(flowCode.get(s.flowId()))),
                    Col.v("name", s.name()), Col.v("step_order", s.stepOrder()), Col.v("target_system", s.targetSystem()),
                    Col.v("http_method", s.httpMethod()), Col.v("path_template", s.pathTemplate()),
                    Col.v("condition_expr", s.conditionExpr()), Col.v("success_expr", s.successExpr()),
                    Col.v("on_failure", s.onFailure()), Col.v("timeout_ms", s.timeoutMs()),
                    Col.v("response_schema_code", s.responseSchemaCode()), Col.v("request_handler", s.requestHandler()),
                    Col.v("response_handler", s.responseHandler()), Col.v("body_codec", s.bodyCodec()),
                    Col.v("enabled", s.enabled()), Col.v("sql_text", s.sqlText()), Col.v("body_template", s.bodyTemplate()),
                    Col.v("next_on_success", s.nextOnSuccess()), Col.v("next_on_failure", s.nextOnFailure()),
                    Col.v("response_example", s.responseExample()), Col.v("cases", s.cases()))));
        }
        for (ConfigRows.ResponseRow r : rows.responses()) {
            flows.add(new Insert("flow_response", List.of(Col.expr("flow_id", flowId(flowCode.get(r.flowId()))),
                    Col.v("name", r.name()), Col.v("status_code", r.status() == null ? 200 : r.status()),
                    Col.v("description", r.description()))));
        }
        for (RuleRow r : rows.rules()) {
            String code = flowCode.get(r.flowId());
            StepRow step = r.stepId() == null ? null : stepById.get(r.stepId());
            flows.add(new Insert("mapping_rule", List.of(Col.expr("flow_id", flowId(code)),
                    step == null ? Col.v("step_id", null)
                            : Col.expr("step_id", "(SELECT s.id FROM {flow_step} s JOIN {flow} f ON f.id = s.flow_id WHERE f.code = "
                                    + quote(code) + " AND s.name = " + quote(step.name()) + ")"),
                    Col.v("phase", r.phase()), Col.v("seq", r.seq()), Col.v("target_type", r.targetType()),
                    Col.v("target_path", r.targetPath()), Col.v("source_path", r.sourcePath()),
                    Col.v("constant_value", r.constantValue()), Col.v("default_value", r.defaultValue()),
                    Col.v("converter", r.converter()), Col.v("lookup_code", r.lookupCode()),
                    Col.v("field_handler", r.fieldHandler()), Col.v("required", r.required()),
                    Col.v("response_name", r.responseName()))));
        }
        return new Plan(app, rows.flows().stream().map(FlowRow::code).toList(), application, shared, flows);
    }

    private static String flowId(String code) {
        return "(SELECT id FROM {flow} WHERE code = " + quote(code) + ")";
    }

    /** The flows to replace: this application's, and any flow (of another application) with an exported code. */
    private static String flowsOfApp(Plan p) {
        return "application_code = " + quote(p.app()) + (p.flowCodes().isEmpty() ? ""
                : " OR code IN (" + p.flowCodes().stream().map(ApplicationExport::quote).collect(Collectors.joining(", ")) + ")");
    }

    static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    // ------------------------------------------------------------------ SQL script

    static String sql(Bundle b, Dialect dialect, GatewayProperties.Tables tables, String exportedFrom) {
        Plan p = plan(b);
        Map<String, String> t = tables.byLogicalName();
        StringBuilder out = new StringBuilder();
        out.append("-- Application ").append(p.app()).append(" (").append(Objects.toString(b.application().name(), ""))
                .append("), exported ").append(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .append(" from ").append(exportedFrom).append('\n')
                .append("-- For ").append(dialect == Dialect.ORACLE ? "Oracle" : "PostgreSQL")
                .append(". Run it in one transaction, then reload the gateway (POST /admin/config/reload).\n")
                .append("-- The application and its flows are replaced; shared target systems, lookups, schemas and\n")
                .append("-- storages are only inserted when missing (existing ones are left as they are).\n");
        notes(b, "-- ", out);
        out.append("\n-- 1. Remove the application and its flows\n");
        String flows = "(SELECT id FROM " + t.get("flow") + " WHERE " + flowsOfApp(p) + ")";
        out.append("DELETE FROM ").append(t.get("mapping_rule")).append(" WHERE flow_id IN ").append(flows).append(";\n");
        out.append("DELETE FROM ").append(t.get("flow_step")).append(" WHERE flow_id IN ").append(flows).append(";\n");
        out.append("DELETE FROM ").append(t.get("flow_response")).append(" WHERE flow_id IN ").append(flows).append(";\n");
        out.append("DELETE FROM ").append(t.get("flow")).append(" WHERE ").append(flowsOfApp(p)).append(";\n");
        out.append("DELETE FROM ").append(t.get("application")).append(" WHERE code = ").append(quote(p.app())).append(";\n");
        p.application().forEach(i -> out.append(insertSql(i, dialect, t)));

        out.append("\n-- 2. Shared items, only when missing\n");
        for (Shared s : p.shared()) {
            String check = names(s.missingCheck(), t);
            Map<String, List<Insert>> byTable = new LinkedHashMap<>();
            s.inserts().forEach(i -> byTable.computeIfAbsent(i.table(), k -> new ArrayList<>()).add(i));
            // dependent rows (a target's headers) first: the check still sees the database before the item itself
            List<List<Insert>> groups = new ArrayList<>(byTable.values());
            java.util.Collections.reverse(groups);
            for (List<Insert> group : groups) {
                if (group.size() == 1) {
                    out.append(guardedSql(group.getFirst(), check, p.app(), dialect, t));
                    continue;
                }
                // one statement per table, so its check sees the table as it was before the statement
                Insert first = group.getFirst();
                out.append("INSERT INTO ").append(t.get(first.table())).append(" (")
                        .append(first.cols().stream().map(Col::name).collect(Collectors.joining(", "))).append(")\n")
                        .append("SELECT * FROM (\n");
                for (int i = 0; i < group.size(); i++) {
                    out.append(i == 0 ? "  SELECT " : "  UNION ALL SELECT ")
                            .append(group.get(i).cols().stream().map(c -> value(c, dialect, t) + " " + c.name())
                                    .collect(Collectors.joining(", ")))
                            .append(" FROM ").append(t.get("application")).append(" WHERE code = ").append(quote(p.app()))
                            .append('\n');
                }
                out.append(") x WHERE (").append(check).append(") = 0;\n");
            }
        }
        out.append("\n-- 3. The flows, their steps and mapping rules\n");
        p.flows().forEach(i -> out.append(insertSql(i, dialect, t)));
        return out.toString();
    }

    private static String insertSql(Insert i, Dialect d, Map<String, String> t) {
        return "INSERT INTO " + t.get(i.table()) + " (" + i.cols().stream().map(Col::name).collect(Collectors.joining(", "))
                + ") VALUES (" + i.cols().stream().map(c -> value(c, d, t)).collect(Collectors.joining(", ")) + ");\n";
    }

    private static String guardedSql(Insert i, String check, String app, Dialect d, Map<String, String> t) {
        return "INSERT INTO " + t.get(i.table()) + " (" + i.cols().stream().map(Col::name).collect(Collectors.joining(", "))
                + ")\nSELECT " + i.cols().stream().map(c -> value(c, d, t)).collect(Collectors.joining(", "))
                + " FROM " + t.get("application") + " WHERE code = " + quote(app) + " AND (" + check + ") = 0;\n";
    }

    private static String value(Col c, Dialect d, Map<String, String> t) {
        Object v = c.value();
        if (c.expression()) {
            return names((String) v, t);
        }
        if (v == null) {
            return "NULL";
        }
        if (v instanceof Boolean bool) {
            return d == Dialect.ORACLE ? (bool ? "1" : "0") : (bool ? "TRUE" : "FALSE");
        }
        if (v instanceof Number) {
            return v.toString();
        }
        String s = v.toString();
        if (d == Dialect.ORACLE && s.length() > 1000) {
            // an Oracle string literal holds at most 4000 bytes: long text (SQL, schemas, templates) in pieces
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < s.length(); i += 1000) {
                parts.add("TO_CLOB(" + quote(s.substring(i, Math.min(s.length(), i + 1000))) + ")");
            }
            return String.join(" || ", parts);
        }
        return quote(s);
    }

    private static String names(String sql, Map<String, String> t) {
        String out = sql;
        for (Map.Entry<String, String> e : t.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    private static void notes(Bundle b, String prefix, StringBuilder out) {
        b.requires().forEach((what, items) -> {
            if (!items.isEmpty()) {
                out.append(prefix).append("Needs on the target gateway (").append(what).append("): ")
                        .append(String.join(", ", items)).append('\n');
            }
        });
        if (!b.replacedSecrets().isEmpty()) {
            out.append(prefix).append("Secrets replaced by placeholders, set these environment variables there: ")
                    .append(String.join(", ", b.replacedSecrets())).append('\n');
        }
    }

    // ------------------------------------------------------------------ Liquibase

    static String liquibase(Bundle b, String exportedFrom) {
        Plan p = plan(b);
        String stamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String id = "app-" + p.app().toLowerCase(Locale.ROOT) + "-" + stamp;
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<databaseChangeLog\n")
                .append("        xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"\n")
                .append("        xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
                .append("        xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog\n")
                .append("            http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd\">\n\n")
                .append("    <!--\n        Application ").append(xml(p.app())).append(" (")
                .append(xml(Objects.toString(b.application().name(), ""))).append("), exported ")
                .append(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append(" from ")
                .append(xml(exportedFrom)).append(".\n")
                .append("        Include it from db.changelog-master.xml (it uses the tbl.* table name properties).\n")
                .append("        Shared items are only inserted when missing; the application and its flows are replaced.\n");
        StringBuilder notes = new StringBuilder();
        notes(b, "        ", notes);
        out.append(notes.toString().replace("--", "- -")).append("    -->\n");

        for (Shared s : p.shared()) {
            out.append("    <changeSet id=\"").append(xml(id + "-" + s.id())).append("\" author=\"json-gateway-studio\">\n")
                    .append("        <preConditions onFail=\"MARK_RAN\">\n")
                    .append("            <sqlCheck expectedResult=\"0\">").append(xml(liquibaseNames(s.missingCheck())))
                    .append("</sqlCheck>\n        </preConditions>\n");
            s.inserts().forEach(i -> out.append(insertXml(i)));
            out.append("    </changeSet>\n\n");
        }
        String flows = "(SELECT id FROM ${tbl.flow} WHERE " + flowsOfApp(p) + ")";
        out.append("    <changeSet id=\"").append(xml(id + "-flows")).append("\" author=\"json-gateway-studio\">\n");
        out.append(deleteXml("mapping_rule", "flow_id IN " + flows));
        out.append(deleteXml("flow_step", "flow_id IN " + flows));
        out.append(deleteXml("flow_response", "flow_id IN " + flows));
        out.append(deleteXml("flow", flowsOfApp(p)));
        out.append(deleteXml("application", "code = " + quote(p.app())));
        p.application().forEach(i -> out.append(insertXml(i)));
        p.flows().forEach(i -> out.append(insertXml(i)));
        out.append("    </changeSet>\n</databaseChangeLog>\n");
        return out.toString();
    }

    private static String deleteXml(String table, String where) {
        return "        <delete tableName=\"${tbl." + table + "}\">\n            <where>" + xml(where) + "</where>\n        </delete>\n";
    }

    private static String insertXml(Insert i) {
        StringBuilder out = new StringBuilder("        <insert tableName=\"${tbl." + i.table() + "}\">\n");
        for (Col c : i.cols()) {
            Object v = c.value();
            if (v == null) {
                continue;
            }
            out.append("            <column name=\"").append(c.name()).append("\" ");
            if (c.expression()) {
                out.append("valueComputed=\"").append(xml(liquibaseNames((String) v))).append('"');
            } else if (v instanceof Boolean bool) {
                out.append("valueBoolean=\"").append(bool).append('"');
            } else if (v instanceof Number) {
                out.append("valueNumeric=\"").append(v).append('"');
            } else if (v.toString().contains("${")) {
                // Liquibase would fill ${...} from its properties and the environment: keep it as text
                out.append("valueComputed=\"").append(xml(quote(v.toString()).replace("${", "$' || '{"))).append('"');
            } else {
                out.append("value=\"").append(xml(v.toString())).append('"');
            }
            out.append("/>\n");
        }
        return out.append("        </insert>\n").toString();
    }

    private static String liquibaseNames(String sql) {
        return sql.replaceAll("\\{([a-z_]+)}", "\\${tbl.$1}");
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("\n", "&#10;").replace("\r", "&#13;").replace("\t", "&#9;");
    }
}
