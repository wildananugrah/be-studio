package com.mhamzah.gateway.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SqlTextTest {

    @Test
    void findsParametersButNotInsideLiteralsCommentsOrCasts() {
        SqlText sql = SqlText.parse("""
                -- :notThis
                SELECT id, ':nor_this' AS "x:y", created_at::date /* :neither */
                FROM t WHERE id = :id AND (name = :name OR alias = :name);
                """);
        assertThat(sql.parameterNames()).containsExactly("id", "name");
        assertThat(sql.firstKeyword()).isEqualTo("SELECT");
        assertThat(sql.isQuery()).isTrue();
        assertThat(sql.sql()).doesNotEndWith(";");
    }

    @Test
    void doubledQuotesStayInsideTheLiteral() {
        assertThat(SqlText.parse("SELECT 'it''s :x' FROM t WHERE a = :a").parameterNames()).containsExactly("a");
    }

    @Test
    void rejectsSeveralStatementsAndBrokenText() {
        assertThatThrownBy(() -> SqlText.parse("SELECT 1; DELETE FROM t")).hasMessageContaining("only one SQL statement");
        assertThatThrownBy(() -> SqlText.parse("SELECT 'open")).hasMessageContaining("unterminated string");
        assertThatThrownBy(() -> SqlText.parse("  ")).hasMessageContaining("empty");
        // a trailing ';' and a comment after it are fine
        assertThat(SqlText.parse("SELECT 1; -- done").sql()).isEqualTo("SELECT 1");
    }

    @Test
    void knowsWritesFromReads() {
        assertThat(SqlText.parse("WITH x AS (SELECT 1) SELECT * FROM x").isQuery()).isTrue();
        assertThat(SqlText.parse("/* c */ (SELECT 1)").isQuery()).isTrue();
        assertThat(SqlText.parse("UPDATE t SET a = :a").isQuery()).isFalse();
        assertThat(SqlText.parse("UPDATE t SET a = :a").firstKeyword()).isEqualTo("UPDATE");
    }
}
