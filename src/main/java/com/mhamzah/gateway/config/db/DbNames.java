package com.mhamzah.gateway.config.db;

import com.mhamzah.gateway.config.GatewayProperties.Db;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates configured schema and table names (spec Section 4.2). Valid names are safe to embed in SQL.
 * Table names are limited to 25 characters so that derived object names ({@code <table>_IX1} etc.)
 * stay within Oracle's 30-character identifier limit.
 */
public final class DbNames {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");
    static final int MAX_TABLE = 25;
    static final int MAX_IDENTIFIER = 30;

    private DbNames() {}

    public static List<String> validate(Db db) {
        List<String> errors = new ArrayList<>();
        if (db.hasSchema()) {
            check("gateway.db.schema", db.schema(), MAX_IDENTIFIER, errors);
        }
        Map<String, String> names = new LinkedHashMap<>();
        names.put("gateway.db.tables.flow", db.tables().flow());
        names.put("gateway.db.tables.flow-step", db.tables().flowStep());
        names.put("gateway.db.tables.mapping-rule", db.tables().mappingRule());
        names.put("gateway.db.tables.lookup-entry", db.tables().lookupEntry());
        names.put("gateway.db.tables.json-schema", db.tables().jsonSchema());
        names.put("gateway.db.tables.audit-transaction", db.tables().auditTransaction());
        names.put("gateway.db.tables.audit-step", db.tables().auditStep());
        names.put("gateway.db.tables.target-system", db.tables().targetSystem());
        names.put("gateway.db.tables.target-system-header", db.tables().targetSystemHeader());
        names.put("gateway.db.tables.storage", db.tables().storage());
        names.forEach((key, value) -> check(key, value, MAX_TABLE, errors));
        check("gateway.db.liquibase-tables.changelog", db.liquibaseTables().changelog(), MAX_IDENTIFIER, errors);
        check("gateway.db.liquibase-tables.changelog-lock", db.liquibaseTables().changelogLock(), MAX_IDENTIFIER, errors);
        names.put("gateway.db.liquibase-tables.changelog", db.liquibaseTables().changelog());
        names.put("gateway.db.liquibase-tables.changelog-lock", db.liquibaseTables().changelogLock());

        Map<String, String> seen = new HashMap<>();
        names.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            String other = seen.putIfAbsent(value.toLowerCase(Locale.ROOT), key);
            if (other != null) {
                errors.add(key + " '" + value + "' must be distinct from " + other);
            }
        });
        return errors;
    }

    private static void check(String key, String value, int maxLength, List<String> errors) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            errors.add(key + " '" + value + "' must start with a letter and contain only letters, digits and '_'");
        } else if (value.length() > maxLength) {
            errors.add(key + " '" + value + "' must be at most " + maxLength + " characters");
        }
    }
}
