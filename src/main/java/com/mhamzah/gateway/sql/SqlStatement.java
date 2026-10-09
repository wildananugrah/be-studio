package com.mhamzah.gateway.sql;

import com.mhamzah.gateway.mapping.JsonValues;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.jdbc.core.StatementCreatorUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A compiled database query step: the parsed SQL and its datasource. {@link #execute} binds the parameters from
 * the step's mapped request body ({@code :name} reads its top-level field {@code name}) and returns the result as
 * JSON:
 *
 * <pre>
 * query (SELECT, WITH, ...): {"rows": [{"column": value, ...}, ...], "rowCount": n, "truncated": false}
 * anything else (INSERT, UPDATE, DELETE, ...): {"updated": n}
 * </pre>
 *
 * Parameter values: string, number, boolean and null as such; an array binds each element (for
 * {@code IN (:ids)}; an empty array binds one null); an object binds its JSON text. Column names are the labels
 * the database reports; labels in all upper case from a database that upper-cases identifiers (Oracle) are
 * lower-cased, so {@code SELECT full_name} reads as {@code full_name} on both databases. Use a quoted alias
 * ({@code AS "fullName"}) for any other name.
 */
public final class SqlStatement {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final SqlText text;
    private final SqlDatasources.Entry datasource;
    private final int maxRows;

    public SqlStatement(SqlText text, SqlDatasources.Entry datasource, int maxRows) {
        this.text = text;
        this.datasource = datasource;
        this.maxRows = maxRows;
    }

    public SqlText text() {
        return text;
    }

    public String datasourceName() {
        return datasource.name();
    }

    /** One line for logs and the audit trail: {@code DATASOURCE: SELECT ...}, at most 1000 characters. */
    public String describe() {
        String oneLine = text.sql().replaceAll("\\s+", " ");
        String s = datasource.name() + ": " + oneLine;
        return s.length() > 1000 ? s.substring(0, 997) + "..." : s;
    }

    /**
     * Runs the statement.
     *
     * @param params the step's mapped request body (an object; null = no values)
     * @param timeoutSeconds the query timeout, at least 1
     * @throws org.springframework.dao.DataAccessException when the database refuses or fails
     */
    public JsonNode execute(JsonNode params, int timeoutSeconds) {
        ConnectionCallback<JsonNode> work = con -> run(con, params, Math.max(1, timeoutSeconds));
        if (datasource.readOnlyTx() != null) {
            return datasource.readOnlyTx().execute(status -> datasource.jdbc().execute(work));
        }
        return datasource.jdbc().execute(work);
    }

    private JsonNode run(Connection con, JsonNode params, int timeoutSeconds) throws SQLException {
        StringBuilder sql = new StringBuilder();
        List<Object> values = new ArrayList<>();
        for (SqlText.Part part : text.parts()) {
            if (part instanceof SqlText.Text t) {
                sql.append(t.sql());
            } else if (part instanceof SqlText.Param p) {
                JsonNode v = params == null ? null : params.get(p.name());
                if (v != null && v.isArray()) {
                    if (v.isEmpty()) {
                        sql.append('?');
                        values.add(null);
                    } else {
                        for (int i = 0; i < v.size(); i++) {
                            sql.append(i == 0 ? "?" : ", ?");
                            values.add(value(v.get(i)));
                        }
                    }
                } else {
                    sql.append('?');
                    values.add(value(v));
                }
            }
        }
        try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
            for (int i = 0; i < values.size(); i++) {
                StatementCreatorUtils.setParameterValue(ps, i + 1, SqlTypeValue.TYPE_UNKNOWN, values.get(i));
            }
            ps.setQueryTimeout(timeoutSeconds);
            ps.setMaxRows(maxRows + 1);
            if (ps.execute()) {
                try (ResultSet rs = ps.getResultSet()) {
                    return rows(rs, con.getMetaData().storesUpperCaseIdentifiers());
                }
            }
            ObjectNode out = F.objectNode();
            out.put("updated", ps.getUpdateCount());
            return out;
        }
    }

    private static Object value(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isIntegralNumber() && v.canConvertToLong()) {
            return v.longValue();
        }
        if (v.isNumber()) {
            return v.decimalValue();
        }
        if (v.isString()) {
            return v.stringValue();
        }
        return JsonValues.MAPPER.writeValueAsString(v);
    }

    private ObjectNode rows(ResultSet rs, boolean upperCaseIds) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        String[] names = new String[cols];
        for (int c = 0; c < cols; c++) {
            String label = md.getColumnLabel(c + 1);
            names[c] = upperCaseIds && label.equals(label.toUpperCase(Locale.ROOT)) ? label.toLowerCase(Locale.ROOT) : label;
        }
        ObjectNode out = F.objectNode();
        ArrayNode rows = out.putArray("rows");
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() == maxRows) {
                truncated = true;
                break;
            }
            ObjectNode row = rows.addObject();
            for (int c = 0; c < cols; c++) {
                row.set(names[c], column(rs, md, c + 1));
            }
        }
        out.put("rowCount", rows.size());
        out.put("truncated", truncated);
        return out;
    }

    /** One column as JSON: numbers stay numbers, dates and times become ISO-8601 text, binary becomes Base64. */
    private static JsonNode column(ResultSet rs, ResultSetMetaData md, int c) throws SQLException {
        int type = md.getColumnType(c);
        JsonNode v = switch (type) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> {
                long l = rs.getLong(c);
                yield F.numberNode(l);
            }
            case Types.NUMERIC, Types.DECIMAL -> {
                BigDecimal d = rs.getBigDecimal(c);
                yield d == null ? null : number(d);
            }
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> F.numberNode(rs.getDouble(c));
            case Types.BOOLEAN, Types.BIT -> F.booleanNode(rs.getBoolean(c));
            case Types.DATE -> {
                LocalDate d = rs.getObject(c, LocalDate.class);
                yield d == null ? null : F.stringNode(d.toString());
            }
            case Types.TIME -> {
                LocalTime t = rs.getObject(c, LocalTime.class);
                yield t == null ? null : F.stringNode(t.toString());
            }
            case Types.TIMESTAMP -> {
                LocalDateTime t = rs.getObject(c, LocalDateTime.class);
                yield t == null ? null : F.stringNode(t.toString());
            }
            case Types.TIMESTAMP_WITH_TIMEZONE, Types.TIME_WITH_TIMEZONE -> {
                OffsetDateTime t = rs.getObject(c, OffsetDateTime.class);
                yield t == null ? null : F.stringNode(t.toString());
            }
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                byte[] b = rs.getBytes(c);
                yield b == null ? null : F.stringNode(Base64.getEncoder().encodeToString(b));
            }
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                    Types.CLOB, Types.NCLOB -> {
                String s = rs.getString(c);
                yield s == null ? null : F.stringNode(s);
            }
            default -> other(rs, md, c);
        };
        return v == null || rs.wasNull() ? F.nullNode() : v;
    }

    /** json / jsonb (PostgreSQL) as JSON; anything else as its text. */
    private static JsonNode other(ResultSet rs, ResultSetMetaData md, int c) throws SQLException {
        String s = rs.getString(c);
        if (s == null) {
            return null;
        }
        String typeName = md.getColumnTypeName(c);
        if (typeName != null && typeName.toLowerCase(Locale.ROOT).startsWith("json")) {
            try {
                return JsonValues.MAPPER.readTree(s);
            } catch (RuntimeException e) {
                return F.stringNode(s);
            }
        }
        return F.stringNode(s);
    }

    /** Whole numbers that fit a long as long (Oracle NUMBER ids), others as decimals. */
    private static JsonNode number(BigDecimal d) {
        BigDecimal stripped = d.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            BigInteger i = stripped.toBigIntegerExact();
            if (i.bitLength() < 64) {
                return F.numberNode(i.longValue());
            }
            return F.numberNode(i);
        }
        return F.numberNode(d);
    }
}
