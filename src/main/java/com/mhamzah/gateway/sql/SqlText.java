package com.mhamzah.gateway.sql;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The SQL of a database query step, split into text and {@code :name} parameters. Exactly one statement; a
 * trailing {@code ;} is dropped. Parameters inside string literals, quoted identifiers and comments are not
 * parameters, and {@code ::} (a PostgreSQL cast) is not one either. A parameter name is a letter or {@code _}
 * followed by letters, digits and {@code _}; the same name may appear several times.
 *
 * <p>Values are always bound ({@code ?}), never pasted into the SQL text, so request data cannot change the
 * statement.
 */
public final class SqlText {

    /** A piece of the statement: literal SQL text, or the name of a parameter. */
    sealed interface Part permits Text, Param {}

    record Text(String sql) implements Part {}

    record Param(String name) implements Part {}

    private final String sql;
    private final List<Part> parts;
    private final Set<String> parameterNames;
    private final String firstKeyword;
    private final List<String> orderedNames;

    private SqlText(String sql, List<Part> parts, Set<String> parameterNames, String firstKeyword) {
        this.sql = sql;
        this.parts = List.copyOf(parts);
        this.parameterNames = Set.copyOf(parameterNames);
        this.firstKeyword = firstKeyword;
        this.orderedNames = List.copyOf(parameterNames);
    }

    /** @throws IllegalArgumentException when blank, unterminated, or more than one statement */
    public static SqlText parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("sql_text is empty");
        }
        String sql = text.strip();
        List<Part> parts = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        StringBuilder chunk = new StringBuilder();
        String firstKeyword = null;
        int n = sql.length();
        int i = 0;
        int end = n;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int close = closing(sql, i, c);
                chunk.append(sql, i, close + 1);
                i = close + 1;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int nl = sql.indexOf('\n', i);
                int stop = nl < 0 ? n : nl;
                chunk.append(sql, i, stop);
                i = stop;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int close = sql.indexOf("*/", i + 2);
                if (close < 0) {
                    throw new IllegalArgumentException("unterminated /* comment");
                }
                chunk.append(sql, i, close + 2);
                i = close + 2;
            } else if (c == ':' && i + 1 < n && sql.charAt(i + 1) == ':') {
                chunk.append("::");
                i += 2;
            } else if (c == ':' && i + 1 < n && isNameStart(sql.charAt(i + 1))) {
                int j = i + 1;
                while (j < n && isNamePart(sql.charAt(j))) {
                    j++;
                }
                if (!chunk.isEmpty()) {
                    parts.add(new Text(chunk.toString()));
                    chunk.setLength(0);
                }
                String name = sql.substring(i + 1, j);
                parts.add(new Param(name));
                names.add(name);
                i = j;
            } else if (c == ';') {
                if (!sql.substring(i + 1).replaceAll("(?s)--[^\\n]*|/\\*.*?\\*/", "").isBlank()) {
                    throw new IllegalArgumentException("only one SQL statement is allowed (found ';' followed by more)");
                }
                end = i;
                break;
            } else {
                if (firstKeyword == null && Character.isLetter(c)) {
                    int j = i;
                    while (j < n && Character.isLetter(sql.charAt(j))) {
                        j++;
                    }
                    firstKeyword = sql.substring(i, j).toUpperCase(Locale.ROOT);
                    chunk.append(sql, i, j);
                    i = j;
                    continue;
                }
                chunk.append(c);
                i++;
            }
        }
        if (!chunk.isEmpty()) {
            parts.add(new Text(chunk.toString()));
        }
        return new SqlText(sql.substring(0, end).strip(), parts, names, firstKeyword);
    }

    private static int closing(String sql, int open, char quote) {
        int i = open + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2; // doubled quote: escaped
                    continue;
                }
                return i;
            }
            i++;
        }
        throw new IllegalArgumentException("unterminated " + (quote == '\'' ? "string literal" : "quoted identifier"));
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** The statement as written (without a trailing {@code ;}). */
    public String sql() {
        return sql;
    }

    /** The distinct parameter names, in order of first appearance. */
    public List<String> parameterNames() {
        return orderedNames;
    }

    public boolean hasParameter(String name) {
        return parameterNames.contains(name);
    }

    /** SELECT, WITH, INSERT, ... (upper case); null when the statement has no keyword. */
    public String firstKeyword() {
        return firstKeyword;
    }

    /** Only reads: SELECT, WITH (a common table expression) or VALUES. */
    public boolean isQuery() {
        return "SELECT".equals(firstKeyword) || "WITH".equals(firstKeyword) || "VALUES".equals(firstKeyword);
    }

    List<Part> parts() {
        return parts;
    }
}
