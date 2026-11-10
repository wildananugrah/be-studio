package com.mhamzah.gateway.audit;

import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.config.GatewayProperties.PartitionInterval;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Looks after the time partitions of the audit tables ({@code gateway.audit.partition.*}).
 * <ul>
 *   <li>PostgreSQL: creates the current partition and {@code premake} more ahead; a row that still finds no
 *   partition gets one on demand ({@link #ensureFor}).</li>
 *   <li>Oracle: INTERVAL partitioning creates partitions itself; the table's interval is set to the configured one.</li>
 *   <li>Both: with {@code retention} set, drops the partitions that end before now minus the retention.</li>
 * </ul>
 * Times are in the JVM zone, as the audit writer stores them. A table that is not partitioned (for example created
 * by a DBA without partitions) is left alone.
 */
public class AuditPartitions {

    private static final Logger log = LoggerFactory.getLogger(AuditPartitions.class);
    private static final DateTimeFormatter NAME_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern PG_BOUND = Pattern.compile("FROM \\('([^']+)'\\) TO \\('([^']+)'\\)");
    private static final Pattern ORACLE_BOUND = Pattern.compile("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");

    /** A partition and the period it covers, {@code [from, to)}. */
    record Range(String name, LocalDateTime from, LocalDateTime to) {}

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final GatewayProperties.Db db;
    private final GatewayProperties.Partition config;
    private final Clock clock;
    private volatile Boolean oracle;

    public AuditPartitions(DataSource dataSource, GatewayProperties.Db db, GatewayProperties.Partition config, Clock clock) {
        this.jdbc = JdbcClient.create(dataSource);
        this.dataSource = dataSource;
        this.db = db;
        this.config = config;
        this.clock = clock;
    }

    private List<String> tables() {
        return List.of(db.tables().auditTransaction(), db.tables().auditStep());
    }

    /** Creates what is missing, applies the interval and drops expired partitions. Never throws. */
    public synchronized void maintain() {
        for (String table : tables()) {
            try {
                if (isOracle()) {
                    if (oracleInterval(table) != null) {
                        oracleSetInterval(table);
                        dropExpired(table, oracleRanges(table));
                    }
                } else if (pgPartitioned(table)) {
                    LocalDateTime start = floor(now());
                    List<LocalDateTime> wanted = new ArrayList<>();
                    for (int i = 0; i <= Math.max(0, config.premake()); i++) {
                        wanted.add(start);
                        start = next(start);
                    }
                    pgEnsure(table, wanted);
                    dropExpired(table, pgRanges(table));
                }
            } catch (RuntimeException e) {
                log.error("Audit partition maintenance failed for {}: {}", table, e.getMessage());
            }
        }
    }

    /** PostgreSQL: makes sure partitions exist for these times (rows that found none). */
    public synchronized void ensureFor(Collection<LocalDateTime> times) {
        if (isOracle()) {
            return;
        }
        List<LocalDateTime> starts = times.stream().map(this::floor).distinct().toList();
        for (String table : tables()) {
            if (pgPartitioned(table)) {
                pgEnsure(table, starts);
            }
        }
    }

    /** True when {@code e} (or a cause) is PostgreSQL's "no partition of relation ... found for row". */
    public static boolean isMissingPartition(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && "23514".equals(s.getSQLState())
                    && String.valueOf(s.getMessage()).contains("no partition")) {
                return true;
            }
        }
        return false;
    }

    // ---------- periods ----------

    LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    LocalDateTime floor(LocalDateTime t) {
        LocalDate d = t.toLocalDate();
        return (config.interval() == PartitionInterval.DAY ? d : d.withDayOfMonth(1)).atStartOfDay();
    }

    LocalDateTime next(LocalDateTime start) {
        return config.interval() == PartitionInterval.DAY ? start.plusDays(1) : start.plusMonths(1);
    }

    private void dropExpired(String table, List<Range> ranges) {
        if (config.retention() == null || config.retention().isZero()) {
            return;
        }
        for (Range r : expired(ranges, now().minus(config.retention()))) {
            jdbc.sql(isOracle()
                    ? "ALTER TABLE " + db.qualify(table) + " DROP PARTITION " + r.name() + " UPDATE INDEXES"
                    : "DROP TABLE " + db.qualify(r.name())).update();
            log.info("Dropped audit partition {} of {} ({} to {}), older than the retention {}", r.name(), table,
                    r.from(), r.to(), config.retention());
        }
    }

    /** Partitions that end at or before the cutoff. */
    static List<Range> expired(List<Range> ranges, LocalDateTime cutoff) {
        return ranges.stream().filter(r -> !r.to().isAfter(cutoff)).toList();
    }

    /**
     * The periods to create: each wanted start not already covered, ending at the next existing partition if that
     * starts earlier (e.g. after switching from MONTH to DAY, or the other way round).
     */
    List<Range> missing(String table, List<Range> existing, List<LocalDateTime> starts) {
        List<Range> out = new ArrayList<>();
        List<Range> all = new ArrayList<>(existing);
        for (LocalDateTime from : new TreeSet<>(starts)) {
            if (all.stream().anyMatch(r -> !from.isBefore(r.from()) && from.isBefore(r.to()))) {
                continue;
            }
            LocalDateTime to = all.stream().map(Range::from).filter(f -> f.isAfter(from))
                    .min(Comparator.naturalOrder()).filter(f -> f.isBefore(next(from))).orElse(next(from));
            Range r = new Range(table.toLowerCase(Locale.ROOT) + "_p" + from.format(NAME_DATE), from, to);
            out.add(r);
            all.add(r);
        }
        return out;
    }

    // ---------- PostgreSQL ----------

    /**
     * The configured schema as a bind parameter (null: the session's current schema), for
     * {@code n.nspname = COALESCE(CAST(? AS name), current_schema())}: no configured value is ever part of the SQL text.
     */
    private String pgSchema() {
        return db.hasSchema() ? db.schema().toLowerCase(Locale.ROOT) : null;
    }

    private boolean pgPartitioned(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM pg_partitioned_table p JOIN pg_class c ON c.oid = p.partrelid"
                        + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE c.relname = ? AND n.nspname = COALESCE(CAST(? AS name), current_schema())")
                .params(table.toLowerCase(Locale.ROOT), pgSchema()).query(Long.class).single() > 0;
    }

    List<Range> pgRanges(String table) {
        List<Range> out = new ArrayList<>();
        jdbc.sql("SELECT c.relname, pg_get_expr(c.relpartbound, c.oid) FROM pg_inherits i"
                        + " JOIN pg_class c ON c.oid = i.inhrelid JOIN pg_class p ON p.oid = i.inhparent"
                        + " JOIN pg_namespace n ON n.oid = p.relnamespace"
                        + " WHERE p.relname = ? AND n.nspname = COALESCE(CAST(? AS name), current_schema())")
                .params(table.toLowerCase(Locale.ROOT), pgSchema())
                .query((rs, i) -> {
                    Matcher m = PG_BOUND.matcher(String.valueOf(rs.getString(2)));
                    if (m.find()) {
                        out.add(new Range(rs.getString(1), parse(m.group(1)), parse(m.group(2))));
                    }
                    return null;
                }).list();
        return out;
    }

    private void pgEnsure(String table, List<LocalDateTime> starts) {
        for (Range r : missing(table, pgRanges(table), starts)) {
            try {
                jdbc.sql("CREATE TABLE IF NOT EXISTS " + db.qualify(r.name()) + " PARTITION OF " + db.qualify(table)
                        + " FOR VALUES FROM ('" + r.from().format(SQL_TIME) + "') TO ('" + r.to().format(SQL_TIME) + "')")
                        .update();
                log.info("Created audit partition {} of {} ({} to {})", r.name(), table, r.from(), r.to());
            } catch (RuntimeException e) {
                // another instance created it first: fine when it now exists
                if (pgRanges(table).stream().noneMatch(x -> x.name().equals(r.name()))) {
                    throw e;
                }
            }
        }
    }

    private static LocalDateTime parse(String text) {
        return LocalDateTime.parse(text.length() > 19 ? text.substring(0, 19) : text, SQL_TIME);
    }

    // ---------- Oracle ----------

    /**
     * A dictionary view of the table's owner and the query's parameters: {@code all_<view>} filtered on the configured
     * schema (a bind parameter, never part of the SQL text), else {@code user_<view>}.
     */
    private record Dictionary(String from, List<Object> params) {}

    private Dictionary oracleDictionary(String view, String ownerColumn, String table) {
        String name = table.toUpperCase(Locale.ROOT);
        return db.hasSchema()
                ? new Dictionary("all_" + view + " WHERE " + ownerColumn + " = ? AND", List.of(db.schema().toUpperCase(Locale.ROOT), name))
                : new Dictionary("user_" + view + " WHERE", List.of(name));
    }

    /** The table's INTERVAL expression; null when it is not partitioned. */
    private String oracleInterval(String table) {
        Dictionary d = oracleDictionary("part_tables", "owner", table);
        return jdbc.sql("SELECT interval FROM " + d.from() + " table_name = ?")
                .params(d.params()).query(String.class).optional().orElse(null);
    }

    private void oracleSetInterval(String table) {
        String wanted = config.interval() == PartitionInterval.DAY
                ? "NUMTODSINTERVAL(1, 'DAY')" : "NUMTOYMINTERVAL(1, 'MONTH')";
        String current = oracleInterval(table);
        if (current != null && !normalize(current).equals(normalize(wanted))) {
            jdbc.sql("ALTER TABLE " + db.qualify(table) + " SET INTERVAL (" + wanted + ")").update();
            log.info("Audit table {} now gets one partition per {}", table, config.interval());
        }
    }

    private static String normalize(String interval) {
        return interval.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    }

    /** Interval partitions only; the first (range) partition can never be dropped. HIGH_VALUE is the upper bound. */
    List<Range> oracleRanges(String table) {
        Dictionary d = oracleDictionary("tab_partitions", "table_owner", table);
        List<Range> raw = new ArrayList<>();
        jdbc.sql("SELECT partition_name, interval, high_value FROM " + d.from() + " table_name = ? ORDER BY partition_position")
                .params(d.params())
                .query((rs, i) -> {
                    String name = rs.getString(1);
                    String interval = rs.getString(2);
                    Matcher m = ORACLE_BOUND.matcher(String.valueOf(rs.getString(3)));
                    if (m.find()) {
                        raw.add(new Range("YES".equals(interval) ? name : null, null, parse(m.group(1))));
                    }
                    return null;
                }).list();
        List<Range> out = new ArrayList<>();
        for (int i = 1; i < raw.size(); i++) {
            if (raw.get(i).name() != null) {
                out.add(new Range(raw.get(i).name(), raw.get(i - 1).to(), raw.get(i).to()));
            }
        }
        return out;
    }

    private boolean isOracle() {
        if (oracle == null) {
            try (Connection c = dataSource.getConnection()) {
                oracle = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("oracle");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return oracle;
    }
}
