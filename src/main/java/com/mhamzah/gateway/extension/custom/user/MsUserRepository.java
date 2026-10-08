package com.mhamzah.gateway.extension.custom.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC access to {@code tbl_ms_user} (or {@code custom.ms-user.table}). The SQL is standard, so it runs on
 * PostgreSQL and Oracle 12c+ alike; paging uses {@code OFFSET ... ROWS FETCH NEXT ... ROWS ONLY}.
 */
@Repository
public class MsUserRepository {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)?");
    private static final String COLUMNS = "id, username, full_name, email, phone, status, created_at, updated_at";

    /** Optional filters of {@link #page}: exact {@code status}, and {@code search} in username, name or email. */
    public record Filter(String status, String search) {}

    public record Page(List<MsUser> content, long totalElements) {}

    private final JdbcClient jdbc;
    private final String table;
    private final String keyColumn;

    public MsUserRepository(DataSource dataSource, @Value("${custom.ms-user.table:tbl_ms_user}") String table) {
        if (!IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("custom.ms-user.table '" + table + "' is not a valid table name");
        }
        this.jdbc = JdbcClient.create(dataSource);
        this.table = table;
        this.keyColumn = keyColumn(dataSource);
    }

    public MsUser create(String username, String fullName, String email, String phone, String status) {
        Timestamp now = Timestamp.from(Instant.now());
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO " + table + " (username, full_name, email, phone, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(username, fullName, email, phone, status, now, now)
                .update(key, keyColumn);
        return findById(key.getKeyAs(Number.class).longValue()).orElseThrow();
    }

    public Optional<MsUser> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + table + " WHERE id = ?").param(id)
                .query(MsUserRepository::map).optional();
    }

    /** {@code page} starts at 1. */
    public Page page(int page, int size, Filter filter) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (filter.status() != null) {
            where.append(" AND status = ?");
            params.add(filter.status());
        }
        if (filter.search() != null) {
            where.append(" AND (LOWER(username) LIKE ? OR LOWER(full_name) LIKE ? OR LOWER(email) LIKE ?)");
            String like = "%" + filter.search().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        long total = jdbc.sql("SELECT COUNT(*) FROM " + table + where).params(params)
                .query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add((long) (page - 1) * size);
        pageParams.add(size);
        List<MsUser> content = jdbc.sql("SELECT " + COLUMNS + " FROM " + table + where
                        + " ORDER BY id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY")
                .params(pageParams).query(MsUserRepository::map).list();
        return new Page(content, total);
    }

    /** Sets the given fields (null = keep); returns the updated user, or empty when it does not exist. */
    public Optional<MsUser> update(long id, String fullName, String email, String phone, String status) {
        int rows = jdbc.sql("UPDATE " + table + " SET full_name = COALESCE(?, full_name), email = COALESCE(?, email),"
                        + " phone = COALESCE(?, phone), status = COALESCE(?, status), updated_at = ? WHERE id = ?")
                .params(fullName, email, phone, status, Timestamp.from(Instant.now()), id)
                .update();
        return rows == 0 ? Optional.empty() : findById(id);
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM " + table + " WHERE id = ?").param(id).update() > 0;
    }

    private static MsUser map(ResultSet rs, int row) throws SQLException {
        return new MsUser(rs.getLong("id"), rs.getString("username"), rs.getString("full_name"),
                rs.getString("email"), rs.getString("phone"), rs.getString("status"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /** Oracle reports unquoted identifiers in upper case, PostgreSQL in lower case. */
    private static String keyColumn(DataSource dataSource) {
        try (var c = dataSource.getConnection()) {
            return c.getMetaData().storesUpperCaseIdentifiers() ? "ID" : "id";
        } catch (SQLException e) {
            return "id";
        }
    }
}
