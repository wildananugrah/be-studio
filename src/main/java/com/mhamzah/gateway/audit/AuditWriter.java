package com.mhamzah.gateway.audit;

import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.masking.Masker;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Inserts audit rows with plain JDBC. SQL is built once from the configured schema and table names,
 * which {@code DbNames} has already validated as safe identifiers.
 */
public class AuditWriter implements AuditService.BatchWriter {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Masker masker;
    private final boolean storePayloads;
    private final String insertTransaction;
    private final String insertStep;
    private final String keyColumn;

    public AuditWriter(DataSource dataSource, PlatformTransactionManager txManager, GatewayProperties.Db db,
            Masker masker, boolean storePayloads) {
        this.jdbc = JdbcClient.create(dataSource);
        this.tx = new TransactionTemplate(txManager);
        this.masker = masker;
        this.storePayloads = storePayloads;
        this.insertTransaction = "INSERT INTO " + db.qualify(db.tables().auditTransaction())
                + " (correlation_id, flow_code, http_method, path, client_status, error_type, error_code,"
                + " request_payload, response_payload, started_at, duration_ms)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        this.insertStep = "INSERT INTO " + db.qualify(db.tables().auditStep())
                + " (transaction_id, step_name, target_system, http_method, url, http_status, outcome,"
                + " request_payload, response_payload, started_at, duration_ms)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        this.keyColumn = generatedKeyColumn(dataSource);
    }

    /** Writes a batch in one transaction (one pooled connection). */
    @Override
    public void write(List<AuditRecord> records) {
        tx.executeWithoutResult(status -> records.forEach(this::insert));
    }

    private void insert(AuditRecord r) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.sql(insertTransaction)
                .params(r.correlationId(), r.flowCode(), r.httpMethod(), truncate(r.path(), 1000), r.clientStatus(),
                        r.errorType(), r.errorCode(), payload(r.requestPayload()), payload(r.responsePayload()),
                        Timestamp.from(r.startedAt()), r.durationMs())
                .update(key, keyColumn);
        long id = key.getKeyAs(Number.class).longValue();
        for (AuditRecord.StepRecord s : r.steps()) {
            jdbc.sql(insertStep)
                    .params(id, s.stepName(), s.targetSystem(), s.httpMethod(), truncate(s.url(), 2000), s.httpStatus(),
                            s.outcome(), payload(s.requestPayload()), payload(s.responsePayload()),
                            s.startedAt() == null ? null : Timestamp.from(s.startedAt()), s.durationMs())
                    .update();
        }
    }

    private String payload(JsonNode node) {
        return storePayloads && node != null ? masker.mask(node).toString() : null;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
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
