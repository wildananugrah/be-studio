package com.mhamzah.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Spec Section 4.2: a non-default schema and renamed tables work for Liquibase, JPA and the audit writer. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "gateway.admin.token=t",
    "gateway.db.tables.flow=MW_ROUTE",
    "gateway.db.tables.flow-step=MW_STEP",
    "gateway.db.tables.mapping-rule=MW_RULE",
    "gateway.db.tables.lookup-entry=MW_LOOKUP",
    "gateway.db.tables.json-schema=MW_SCHEMA",
    "gateway.db.tables.audit-transaction=MW_AUDIT_TX",
    "gateway.db.tables.audit-step=MW_AUDIT_STEP",
    "gateway.db.tables.target-system=MW_TARGET",
    "gateway.db.tables.target-system-header=MW_TARGET_HDR",
    "gateway.db.liquibase-tables.changelog=MW_DBCHANGELOG",
    "gateway.db.liquibase-tables.changelog-lock=MW_DBCHANGELOG_LOCK",
})
@Import(TestcontainersConfiguration.class)
class CustomDbNamesIntegrationTest {

    /** PostgreSQL: a separate schema created by the init script. Oracle: the container's application user. */
    static String schema() {
        return TestcontainersConfiguration.isOracle() ? "TEST" : TestcontainersConfiguration.CUSTOM_SCHEMA;
    }

    @DynamicPropertySource
    static void schemaProperty(DynamicPropertyRegistry registry) {
        registry.add("gateway.db.schema", CustomDbNamesIntegrationTest::schema);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    private List<String> tablesInSchema() throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            String schemaPattern = c.getMetaData().storesUpperCaseIdentifiers()
                    ? schema().toUpperCase(Locale.ROOT) : schema().toLowerCase(Locale.ROOT);
            try (ResultSet rs = c.getMetaData().getTables(null, schemaPattern, "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    names.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }

    @Test
    void liquibaseCreatesRenamedTablesInTheConfiguredSchema() throws Exception {
        assertThat(tablesInSchema()).contains("mw_route", "mw_step", "mw_rule", "mw_lookup", "mw_schema",
                "mw_audit_tx", "mw_audit_step", "mw_target", "mw_target_hdr", "mw_dbchangelog", "mw_dbchangelog_lock")
                .doesNotContain("gw_flow", "gw_audit_transaction");
    }

    @Test
    void configLoadsAndAuditIsWrittenThroughRenamedTables() throws Exception {
        String s = schema();
        jdbc.update("INSERT INTO " + s + ".MW_ROUTE (code, name, http_method, path_pattern, success_status, audit_mode, enabled)"
                + " VALUES ('PING', 'ping', 'GET', '/ping', 200, 'ON', ?)", true);
        Long flowId = jdbc.queryForObject("SELECT id FROM " + s + ".MW_ROUTE WHERE code = 'PING'", Long.class);
        jdbc.update("INSERT INTO " + s + ".MW_RULE (flow_id, phase, seq, target_type, target_path, constant_value, required)"
                + " VALUES (?, 'FLOW_RESPONSE', 1, 'BODY', '$.pong', 'true', ?)", flowId, false);

        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> reload = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/config/reload"))
                .header("X-Admin-Token", "t").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(reload.statusCode()).as(reload.body()).isEqualTo(200);

        HttpResponse<String> ping = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/ping"))
                .header("X-Correlation-Id", "custom-names-1").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(ping.statusCode()).isEqualTo(200);
        assertThat(ping.body()).isEqualTo("{\"pong\":true}");

        Integer audited = GatewayIntegrationTest.await(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + s + ".MW_AUDIT_TX WHERE correlation_id = 'custom-names-1'", Integer.class),
                n -> n > 0);
        assertThat(audited).isEqualTo(1);
    }

    @Test
    void apiDocsAreOffByDefault() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        for (String path : new String[] {"/docs", "/docs/openapi.json"}) {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(path).isEqualTo(404);
        }
    }
}
