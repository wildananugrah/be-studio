package com.mhamzah.gateway.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.TestcontainersConfiguration;
import com.mhamzah.gateway.WireMockConfiguration;
import com.mhamzah.gateway.mapping.JsonValues;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/** The database query step examples (103-dev-demo-sql-flows.xml) through the real gateway and database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, WireMockConfiguration.class})
class SqlStepIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    record Response(int status, JsonNode json, String text) {}

    Response get(String path) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), JsonValues.MAPPER.readTree(r.body()), r.body());
    }

    private static String q(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Test
    void detailMapsTheRowForTheClient() throws Exception {
        long id = get("/v1/sql/users?q=budi").json().get("items").get(0).get("id").asLong();

        Response r = get("/v1/sql/users/" + id);

        assertThat(r.status()).as(r.text()).isEqualTo(200);
        JsonNode user = r.json().get("user");
        assertThat(user.get("id").asLong()).isEqualTo(id);
        assertThat(user.get("fullName").asString()).isEqualTo("Budi Santoso");
        assertThat(user.get("username").asString()).isEqualTo("budi.santoso");
        assertThat(user.get("createdAt").asString()).matches("\\d{4}-\\d\\d-\\d\\dT.*");
    }

    @Test
    void noRowFailsTheSuccessExpression() throws Exception {
        Response r = get("/v1/sql/users/987654321");
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.json().get("errorCode").asString()).isEqualTo("GW-422-BUSINESS");
        assertThat(r.json().get("step").asString()).isEqualTo("user");
    }

    @Test
    void searchBindsQueryParametersWithConvertersAndDefaults() throws Exception {
        Response active = get("/v1/sql/users?status=active");
        assertThat(active.status()).as(active.text()).isEqualTo(200);
        assertThat(active.json().get("items")).allSatisfy(u -> assertThat(u.get("active").asString()).isEqualTo("true"));

        Response limited = get("/v1/sql/users?limit=1");
        assertThat(limited.json().get("count").asInt()).isEqualTo(1);
        assertThat(limited.json().get("items")).hasSize(1);

        Response siti = get("/v1/sql/users?q=" + q("aminah"));
        assertThat(siti.json().get("items").get(0).get("name").asString()).isEqualTo("Siti Aminah");
    }

    @Test
    void requestValuesAreBoundNeverPastedIntoTheSql() throws Exception {
        Response r = get("/v1/sql/users?q=" + q("' OR '1'='1") + "&status=" + q("x' OR 'a'='a"));
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(r.json().get("count").asInt()).isZero();

        Response notANumber = get("/v1/sql/users/" + q("1 OR 1=1"));
        assertThat(notANumber.status()).isEqualTo(400);
    }
}
