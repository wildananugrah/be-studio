package com.mhamzah.gateway.studio.audit;

import java.sql.Clob;
import java.sql.SQLException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** JDBC rows of the audit tables as JSON-friendly maps, the same on PostgreSQL and Oracle. */
public final class AuditRows {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private AuditRows() {}

    /** Lower-case column names (Oracle reports upper case); CLOBs and timestamps as text. */
    public static Map<String, Object> row(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), value(v)));
        return out;
    }

    static Object value(Object v) {
        try {
            if (v instanceof Clob clob) {
                return clob.getSubString(1, (int) Math.min(clob.length(), Integer.MAX_VALUE));
            }
        } catch (SQLException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
        if (v instanceof java.sql.Timestamp t) {
            return STAMP.format(t.toInstant());
        }
        if (v instanceof java.time.temporal.TemporalAccessor t && !(v instanceof java.time.LocalDate)) {
            return v.toString();
        }
        return v instanceof Number || v instanceof Boolean || v == null ? v : v.toString();
    }
}
