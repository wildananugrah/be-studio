package com.mhamzah.gateway.sql;

import com.mhamzah.gateway.config.GatewayProperties;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The databases of {@code gateway.sql.datasources}, by name. A datasource with a blank URL is the gateway's own
 * database (its pool); every other one gets a small Hikari pool of its own, which connects on first use, so an
 * unreachable database fails its queries, not the startup.
 */
public class SqlDatasources implements AutoCloseable {

    /** One datasource; {@code readOnly} ones run each query in a read-only transaction. */
    public record Entry(String name, DataSource dataSource, JdbcTemplate jdbc, TransactionTemplate readOnlyTx,
            boolean readOnly, boolean gatewayDatabase) {}

    private static final SqlDatasources NONE = new SqlDatasources(Map.of(), List.of(), 0);

    private final Map<String, Entry> entries;
    private final List<HikariDataSource> ownPools;
    private final int maxRows;

    private SqlDatasources(Map<String, Entry> entries, List<HikariDataSource> ownPools, int maxRows) {
        this.entries = entries;
        this.ownPools = ownPools;
        this.maxRows = maxRows;
    }

    /** No datasources: every query step is a configuration error. */
    public static SqlDatasources none() {
        return NONE;
    }

    public static SqlDatasources create(GatewayProperties.Sql config, DataSource gatewayDataSource) {
        Map<String, Entry> entries = new TreeMap<>();
        List<HikariDataSource> pools = new ArrayList<>();
        config.datasources().forEach((name, d) -> {
            DataSource ds;
            if (d.gatewayDatabase()) {
                ds = gatewayDataSource;
            } else {
                HikariDataSource pool = new HikariDataSource();
                pool.setPoolName("sql-" + name);
                pool.setJdbcUrl(d.url());
                pool.setUsername(d.username());
                pool.setPassword(d.password());
                if (d.driverClassName() != null && !d.driverClassName().isBlank()) {
                    pool.setDriverClassName(d.driverClassName());
                }
                pool.setMaximumPoolSize(Math.max(1, d.maxPoolSize()));
                pool.setMinimumIdle(0);
                pool.setInitializationFailTimeout(-1); // connect lazily
                pools.add(pool);
                ds = pool;
            }
            TransactionTemplate tx = null;
            if (d.readOnly()) {
                DataSourceTransactionManager tm = new DataSourceTransactionManager(ds);
                tm.setEnforceReadOnly(true); // SET TRANSACTION READ ONLY: the database itself refuses writes
                tx = new TransactionTemplate(tm);
                tx.setReadOnly(true);
            }
            entries.put(name, new Entry(name, ds, new JdbcTemplate(ds), tx, d.readOnly(), d.gatewayDatabase()));
        });
        return new SqlDatasources(Map.copyOf(entries), List.copyOf(pools), Math.max(1, config.maxRows()));
    }

    /** Null when there is no datasource of that name. */
    public Entry find(String name) {
        return name == null ? null : entries.get(name);
    }

    public int maxRows() {
        return maxRows;
    }

    /** For Studio: name, readOnly and whether it is the gateway's own database; never the URL or credentials. */
    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        new TreeMap<>(entries).values().forEach(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.name());
            m.put("readOnly", e.readOnly());
            m.put("gatewayDatabase", e.gatewayDatabase());
            out.add(m);
        });
        return out;
    }

    @Override
    public void close() {
        ownPools.forEach(HikariDataSource::close);
    }
}
