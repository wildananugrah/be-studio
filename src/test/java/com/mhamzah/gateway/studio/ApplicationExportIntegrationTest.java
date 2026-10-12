package com.mhamzah.gateway.studio;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.TestcontainersConfiguration;
import com.mhamzah.gateway.WireMockConfiguration;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.mapping.JsonValues;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * Exporting an application of the dev data: what it contains, and that the SQL script and the Liquibase changelog
 * apply cleanly (replacing the application, restoring a missing shared lookup) and leave a configuration the gateway
 * reloads and serves.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "gateway.admin.token=studio-token",
    "gateway.studio.enabled=true",
    "gateway.assistant.enabled=false",
})
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, WireMockConfiguration.class})
class ApplicationExportIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    GatewayProperties properties;

    @TempDir
    Path dir;

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Admin-Token", "studio-token").build(), HttpResponse.BodyHandlers.ofString());
    }

    private int reload() throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/config/reload"))
                .header("X-Admin-Token", "studio-token").POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private int transfer() throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/transfers/with-reversal"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"fromAccount\":\"1001\",\"toAccount\":\"2002\",\"amount\":150000}"))
                .build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void jsonHasTheFlowsAndTheSharedItemsTheyUse() throws Exception {
        HttpResponse<String> r = get("/studio/api/applications/CORE_BANKING_API/export?format=json");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Disposition").orElse("")).contains("APP_CORE_BANKING_API_", ".json");
        JsonNode d = JsonValues.MAPPER.readTree(r.body());
        assertThat(d.get("format").asString()).isEqualTo("json-gateway-application");
        assertThat(d.get("application").get("code").asString()).isEqualTo("CORE_BANKING_API");
        assertThat(d.get("flows").toString()).contains("ACCOUNT_INQUIRY", "TRANSFER_WITH_REVERSAL").doesNotContain("SOAP_TRANSFER");
        assertThat(d.get("targets").toString()).contains("CORE_BANKING");
        assertThat(d.get("lookups").toString()).contains("ACCOUNT_STATUS");
        assertThat(d.get("schemas").toString()).contains("TRANSFER_REQUEST");
        assertThat(d.get("requires").get("handlerBeans").toString()).contains("coreBankingErrorHandler");
        // ACCOUNT_SUMMARY's transform step needs no target system
        assertThat(d.get("flows").toString()).contains("ACCOUNT_SUMMARY");
        assertThat(d.get("requires").toString()).doesNotContain("TRANSFORM");
        // the named response of TRANSFER_WITH_REVERSAL travels with its flow
        assertThat(d.get("flows").toString()).contains("\"responses\":[{\"name\":\"reversed\",\"status\":422");

        assertThat(get("/studio/api/applications/NOPE/export?format=json").statusCode()).isEqualTo(400);
        assertThat(get("/studio/api/applications/CORE_BANKING_API/export?format=yaml").statusCode()).isEqualTo(400);
        assertThat(HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/studio/api/applications/CORE_BANKING_API/export")).build(), HttpResponse.BodyHandlers.ofString())
                .statusCode()).isEqualTo(401);
    }

    @Test
    void sqlScriptReplacesTheApplicationAndRestoresMissingSharedItems() throws Exception {
        String sql = get("/studio/api/applications/CORE_BANKING_API/export?format=sql").body();
        assertThat(sql).containsAnyOf("-- For PostgreSQL", "-- For Oracle");
        assertThat(sql).contains("DELETE FROM gw_flow WHERE application_code = 'CORE_BANKING_API'",
                "INSERT INTO gw_lookup_entry", "NOT", "INSERT INTO gw_flow_step");
        int flowsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM gw_flow", Integer.class);
        jdbc.update("DELETE FROM gw_lookup_entry WHERE lookup_code = 'ACCOUNT_STATUS'");

        // twice: applying an export again changes nothing
        for (int i = 0; i < 2; i++) {
            ScriptUtils.executeSqlScript(dataSource.getConnection(), new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gw_flow", Integer.class)).isEqualTo(flowsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gw_lookup_entry WHERE lookup_code = 'ACCOUNT_STATUS'",
                Integer.class)).isGreaterThan(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gw_target_system WHERE code = 'CORE_BANKING'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_on_failure FROM gw_flow_step s JOIN gw_flow f ON f.id = s.flow_id"
                + " WHERE f.code = 'TRANSFER_WITH_REVERSAL' AND s.name = 'debit'", String.class)).isEqualTo("reversal");
        assertThat(reload()).isEqualTo(200);
        assertThat(transfer()).isEqualTo(200);
    }

    @Test
    void liquibaseChangelogAppliesWithTheTableNameProperties() throws Exception {
        String xml = get("/studio/api/applications/CORE_BANKING_API/export?format=liquibase").body();
        assertThat(xml).contains("<sqlCheck expectedResult=\"0\">SELECT COUNT(*) FROM ${tbl.target_system} WHERE code = 'CORE_BANKING'",
                "tableName=\"${tbl.flow_step}\"", "valueComputed=\"(SELECT id FROM ${tbl.flow} WHERE code = 'TRANSFER')\"");
        // ${...} values are kept as text, not filled in by Liquibase
        assertThat(xml).contains("'$' || '{steps.debit.body.responseCode} == ''00'''");
        Path file = dir.resolve("app.xml");
        Files.writeString(file, xml);
        int flowsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM gw_flow", Integer.class);

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.setChangeLog("file:" + file);
        liquibase.setDatabaseChangeLogTable(properties.db().liquibaseTables().changelog());
        liquibase.setDatabaseChangeLogLockTable(properties.db().liquibaseTables().changelogLock());
        Map<String, String> params = new HashMap<>();
        properties.db().tables().byLogicalName().forEach((logical, table) -> params.put("tbl." + logical, table));
        liquibase.setChangeLogParameters(params);
        liquibase.afterPropertiesSet();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gw_flow", Integer.class)).isEqualTo(flowsBefore);
        assertThat(jdbc.queryForObject("SELECT success_expr FROM gw_flow_step s JOIN gw_flow f ON f.id = s.flow_id"
                + " WHERE f.code = 'TRANSFER_WITH_REVERSAL' AND s.name = 'debit'", String.class))
                .isEqualTo("${steps.debit.body.responseCode} == '00'");
        assertThat(reload()).isEqualTo(200);
        assertThat(transfer()).isEqualTo(200);
    }
}
