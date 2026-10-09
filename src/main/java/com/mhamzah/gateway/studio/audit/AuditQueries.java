package com.mhamzah.gateway.studio.audit;

import com.mhamzah.gateway.config.GatewayProperties;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads {@code gw_audit_transaction} / {@code gw_audit_step} for Studio's audit trail. Standard SQL, so it runs on
 * PostgreSQL and Oracle 12c+; table names come from {@code gateway.db}, already validated as safe identifiers.
 */
public class AuditQueries {

    /** List filters; every field optional. {@code status}: 2xx, 4xx, 5xx or errors (4xx/5xx or an error type). */
    public record Filter(String flow, String status, String search, LocalDateTime from, LocalDateTime to) {}

    private static final String LIST_COLUMNS = "id, correlation_id, flow_code, http_method, path, client_status,"
            + " error_type, error_code, started_at, duration_ms";

    private final JdbcClient jdbc;
    private final String transactions;
    private final String steps;
    private final LogBuffer logs;
    private final GatewayProperties.Audit audit;

    public AuditQueries(DataSource dataSource, GatewayProperties properties, LogBuffer logs) {
        this.jdbc = JdbcClient.create(dataSource);
        GatewayProperties.Db db = properties.db();
        this.transactions = db.qualify(db.tables().auditTransaction());
        this.steps = db.qualify(db.tables().auditStep());
        this.logs = logs;
        this.audit = properties.audit();
    }

    /** Newest first; {@code page} from 1. */
    public Map<String, Object> list(Filter f, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (f.flow() != null) {
            where.append(" AND flow_code = ?");
            params.add(f.flow());
        }
        if (f.status() != null) {
            switch (f.status()) {
                case "2xx" -> where.append(" AND client_status BETWEEN 200 AND 299");
                case "4xx" -> where.append(" AND client_status BETWEEN 400 AND 499");
                case "5xx" -> where.append(" AND client_status >= 500");
                case "errors" -> where.append(" AND (client_status >= 400 OR error_type IS NOT NULL)");
                default -> throw new IllegalArgumentException("status must be 2xx, 4xx, 5xx or errors");
            }
        }
        if (f.search() != null) {
            where.append(" AND (correlation_id = ? OR LOWER(path) LIKE ? OR LOWER(error_code) LIKE ?)");
            String like = "%" + f.search().toLowerCase(Locale.ROOT) + "%";
            params.add(f.search());
            params.add(like);
            params.add(like);
        }
        if (f.from() != null) {
            where.append(" AND started_at >= ?");
            params.add(Timestamp.valueOf(f.from()));
        }
        if (f.to() != null) {
            where.append(" AND started_at <= ?");
            params.add(Timestamp.valueOf(f.to()));
        }
        long total = jdbc.sql("SELECT COUNT(*) FROM " + transactions + where).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add((long) (page - 1) * size);
        pageParams.add(size);
        List<Map<String, Object>> rows = jdbc.sql("SELECT " + LIST_COLUMNS + " FROM " + transactions + where
                        + " ORDER BY id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY")
                .params(pageParams).query().listOfRows().stream().map(AuditRows::row).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", rows);
        out.put("page", page);
        out.put("size", size);
        out.put("totalElements", total);
        out.put("totalPages", (total + size - 1) / size);
        return out;
    }

    /** The transaction, its steps (in call order) and the buffered log lines; null when there is no such row. */
    public Map<String, Object> detail(String correlationId) {
        List<Map<String, Object>> tx = jdbc.sql("SELECT * FROM " + transactions + " WHERE correlation_id = ? ORDER BY id DESC")
                .param(correlationId).query().listOfRows();
        if (tx.isEmpty()) {
            return null;
        }
        Map<String, Object> transaction = AuditRows.row(tx.getFirst());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transaction", transaction);
        out.put("steps", jdbc.sql("SELECT * FROM " + steps + " WHERE transaction_id = ? ORDER BY id")
                .param(transaction.get("id")).query().listOfRows().stream().map(AuditRows::row).toList());
        out.put("logs", logs.lines(correlationId));
        return out;
    }

    /** What the screen needs to explain empty results. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("auditEnabled", audit.enabled());
        out.put("storePayloads", audit.storePayloads());
        out.put("zone", ZoneId.systemDefault().getId());
        return out;
    }
}
