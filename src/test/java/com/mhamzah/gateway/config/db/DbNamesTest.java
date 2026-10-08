package com.mhamzah.gateway.config.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.config.GatewayProperties.Db;
import com.mhamzah.gateway.config.GatewayProperties.LiquibaseTables;
import com.mhamzah.gateway.config.GatewayProperties.Tables;
import org.junit.jupiter.api.Test;

class DbNamesTest {

    private static Tables tables(String flow) {
        return new Tables(flow, "gw_flow_step", "gw_mapping_rule", "gw_lookup_entry", "gw_json_schema",
                "gw_audit_transaction", "gw_audit_step", "gw_target_system", "gw_target_system_header");
    }

    @Test
    void targetSystemTablesAreValidatedToo() {
        Tables t = new Tables("gw_flow", "gw_flow_step", "gw_mapping_rule", "gw_lookup_entry", "gw_json_schema",
                "gw_audit_transaction", "gw_audit_step", "gw_target_system", "gw_flow");
        assertThat(DbNames.validate(new Db(null, t, LB)))
                .anySatisfy(e -> assertThat(e).contains("gateway.db.tables.target-system-header").contains("distinct"));
    }

    private static final LiquibaseTables LB = new LiquibaseTables("gw_db_changelog", "gw_db_changelog_lock");

    @Test
    void defaultsAreValid() {
        assertThat(DbNames.validate(new Db(null, tables("gw_flow"), LB))).isEmpty();
        assertThat(DbNames.validate(new Db("GATEWAY", tables("MW_ROUTE"), LB))).isEmpty();
    }

    @Test
    void rejectsUnsafeCharacters() {
        assertThat(DbNames.validate(new Db("x; drop table y", tables("gw_flow"), LB)))
                .anySatisfy(e -> assertThat(e).contains("gateway.db.schema"));
        assertThat(DbNames.validate(new Db(null, tables("1flow"), LB)))
                .anySatisfy(e -> assertThat(e).contains("gateway.db.tables.flow"));
        assertThat(DbNames.validate(new Db(null, tables("\"quoted\""), LB))).isNotEmpty();
    }

    @Test
    void rejectsTableNamesLongerThan25() {
        assertThat(DbNames.validate(new Db(null, tables("a".repeat(26)), LB)))
                .anySatisfy(e -> assertThat(e).contains("25"));
        assertThat(DbNames.validate(new Db(null, tables("a".repeat(25)), LB))).isEmpty();
    }

    @Test
    void rejectsSchemaLongerThan30() {
        assertThat(DbNames.validate(new Db("s".repeat(31), tables("gw_flow"), LB))).isNotEmpty();
    }

    @Test
    void rejectsDuplicateNamesIgnoringCase() {
        assertThat(DbNames.validate(new Db(null, tables("GW_FLOW_STEP"), LB)))
                .anySatisfy(e -> assertThat(e).contains("distinct"));
    }

    @Test
    void validatesLiquibaseTables() {
        assertThat(DbNames.validate(new Db(null, tables("gw_flow"), new LiquibaseTables("bad-name", "ok_lock"))))
                .anySatisfy(e -> assertThat(e).contains("liquibase-tables.changelog"));
    }
}
