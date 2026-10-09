package com.mhamzah.gateway.studio;

import com.mhamzah.gateway.config.ConfigRows;
import com.mhamzah.gateway.config.ConfigRows.FlowRow;
import com.mhamzah.gateway.config.ConfigRows.LookupRow;
import com.mhamzah.gateway.config.ConfigRows.RuleRow;
import com.mhamzah.gateway.config.ConfigRows.SchemaRow;
import com.mhamzah.gateway.config.ConfigRows.StepRow;
import com.mhamzah.gateway.config.ConfigRows.TargetHeaderRow;
import com.mhamzah.gateway.config.ConfigRows.TargetRow;
import com.mhamzah.gateway.config.GatewayProperties;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * Replaces the rows of the config tables with plain JDBC; JSON schemas are updated by code, so nothing about an
 * unchanged schema is touched. Table names
 * come from {@code gateway.db}, which {@code DbNames} has already validated as safe identifiers. The caller runs
 * {@link #replace} inside a transaction.
 */
class StudioWriter {

    private final JdbcClient jdbc;
    private final GatewayProperties.Db db;
    private final String keyColumn;

    StudioWriter(DataSource dataSource, GatewayProperties.Db db) {
        this.jdbc = JdbcClient.create(dataSource);
        this.db = db;
        this.keyColumn = generatedKeyColumn(dataSource);
    }

    /** {@code gw_json_schema.description} by code (not part of the config rows). */
    Map<String, String> schemaDescriptions() {
        Map<String, String> out = new HashMap<>();
        jdbc.sql("SELECT code, description FROM " + db.qualify(db.tables().jsonSchema()))
                .query(rs -> {
                    out.put(rs.getString(1), rs.getString(2));
                });
        return out;
    }

    /** {@code schemaDescriptions}: description by schema code, for the rows in {@code rows.schemas()}. */
    void replace(ConfigRows rows, Map<String, String> schemaDescriptions) {
        GatewayProperties.Tables t = db.tables();
        replaceSchemas(rows.schemas(), schemaDescriptions);
        // children first; the foreign keys cascade, but not every database honours that for bulk deletes
        for (String table : new String[] {t.mappingRule(), t.flowStep(), t.flow(), t.targetSystemHeader(),
                t.targetSystem(), t.lookupEntry()}) {
            jdbc.sql("DELETE FROM " + db.qualify(table)).update();
        }

        for (TargetRow r : rows.targets()) {
            jdbc.sql("INSERT INTO " + db.qualify(t.targetSystem())
                            + " (code, base_url, connect_timeout_ms, read_timeout_ms, body_codec, enabled, tls_mode,"
                            + " tls_trust_store, tls_trust_store_password, tls_key_store, tls_key_store_password)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(r.code(), r.baseUrl(), r.connectTimeoutMs(), r.readTimeoutMs(), r.bodyCodec(), r.enabled(),
                            r.tlsMode(), r.tlsTrustStore(), r.tlsTrustStorePassword(), r.tlsKeyStore(),
                            r.tlsKeyStorePassword())
                    .update();
        }
        for (TargetHeaderRow r : rows.targetHeaders()) {
            jdbc.sql("INSERT INTO " + db.qualify(t.targetSystemHeader())
                            + " (target_code, header_name, header_value) VALUES (?, ?, ?)")
                    .params(r.targetCode(), r.headerName(), r.headerValue())
                    .update();
        }
        for (LookupRow r : rows.lookups()) {
            jdbc.sql("INSERT INTO " + db.qualify(t.lookupEntry())
                            + " (lookup_code, source_value, target_value) VALUES (?, ?, ?)")
                    .params(r.lookupCode(), r.sourceValue(), r.targetValue())
                    .update();
        }

        Map<Long, Long> flowIds = new HashMap<>();
        for (FlowRow r : rows.flows()) {
            flowIds.put(r.id(), insert("INSERT INTO " + db.qualify(t.flow())
                            + " (code, name, http_method, path_pattern, request_schema_code, response_schema_code,"
                            + " request_handler, response_handler, error_handler, success_status, timeout_ms,"
                            + " audit_mode, enabled) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    r.code(), r.name(), r.httpMethod(), r.pathPattern(), r.requestSchemaCode(),
                    r.responseSchemaCode(), r.requestHandler(), r.responseHandler(), r.errorHandler(),
                    r.successStatus(), r.timeoutMs(), r.auditMode(), r.enabled()));
        }
        Map<Long, Long> stepIds = new HashMap<>();
        for (StepRow r : rows.steps()) {
            stepIds.put(r.id(), insert("INSERT INTO " + db.qualify(t.flowStep())
                            + " (flow_id, name, step_order, target_system, http_method, path_template, condition_expr,"
                            + " success_expr, on_failure, timeout_ms, response_schema_code, request_handler,"
                            + " response_handler, body_codec, enabled) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    flowIds.get(r.flowId()), r.name(), r.stepOrder(), r.targetSystem(), r.httpMethod(),
                    r.pathTemplate(), r.conditionExpr(), r.successExpr(), r.onFailure(), r.timeoutMs(),
                    r.responseSchemaCode(), r.requestHandler(), r.responseHandler(), r.bodyCodec(), r.enabled()));
        }
        for (RuleRow r : rows.rules()) {
            jdbc.sql("INSERT INTO " + db.qualify(t.mappingRule())
                            + " (flow_id, step_id, phase, seq, target_type, target_path, source_path, constant_value,"
                            + " default_value, converter, lookup_code, field_handler, required)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(flowIds.get(r.flowId()), r.stepId() == null ? null : stepIds.get(r.stepId()), r.phase(),
                            r.seq(), r.targetType(), r.targetPath(), r.sourcePath(), r.constantValue(),
                            r.defaultValue(), r.converter(), r.lookupCode(), r.fieldHandler(), r.required())
                    .update();
        }
    }

    private void replaceSchemas(List<SchemaRow> schemas, Map<String, String> descriptions) {
        String table = db.qualify(db.tables().jsonSchema());
        Set<String> existing = new HashSet<>(jdbc.sql("SELECT code FROM " + table).query(String.class).list());
        Set<String> wanted = new HashSet<>();
        for (SchemaRow r : schemas) {
            wanted.add(r.code());
            if (existing.contains(r.code())) {
                jdbc.sql("UPDATE " + table + " SET schema_text = ?, description = ? WHERE code = ?")
                        .params(r.schemaText(), descriptions.get(r.code()), r.code())
                        .update();
            } else {
                jdbc.sql("INSERT INTO " + table + " (code, schema_text, description) VALUES (?, ?, ?)")
                        .params(r.code(), r.schemaText(), descriptions.get(r.code()))
                        .update();
            }
        }
        for (String code : existing) {
            if (!wanted.contains(code)) {
                jdbc.sql("DELETE FROM " + table + " WHERE code = ?").param(code).update();
            }
        }
    }

    private long insert(String sql, Object... params) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(key, keyColumn);
        return key.getKeyAs(Number.class).longValue();
    }

    /** Oracle reports unquoted identifiers in upper case, PostgreSQL in lower case. */
    private static String generatedKeyColumn(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            return c.getMetaData().storesUpperCaseIdentifiers() ? "ID" : "id";
        } catch (SQLException e) {
            return "id";
        }
    }
}
