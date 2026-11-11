package com.mhamzah.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The request validation demo (118-dev-demo-request-validation.xml): LC_REQUEST checks purpose first, then validates the
 * body with the schema purpose picks (new / modify / delete), reporting only the first problem.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, WireMockConfiguration.class})
class RequestValidationExampleIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    private HttpResponse<String> post(String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/lc/requests"))
                .header("Content-Type", "application/json").header("X-Correlation-Id", "rv-1")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void purposeIsCheckedFirstAndPicksTheSchema() throws Exception {
        HttpResponse<String> ok = post("{\"purpose\": \"new\", \"applicantName\": \"PT JAYA ABADI\", \"amount\": 1000000}");
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo("{\"accepted\":true,\"purpose\":\"new\"}");

        HttpResponse<String> unknown = post("{\"purpose\": \"new2\", \"applicantName\": \"PT JAYA ABADI\"}");
        JsonNode u = JSON.readTree(unknown.body());
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(u.get("errorCode").asString()).isEqualTo("GW-400-VALIDATION");
        assertThat(u.get("details").toString()).isEqualTo("[\"purpose must be new, modify or delete\"]");
        assertThat(u.get("field").asString()).isEqualTo("purpose");
        assertThat(u.get("validationCode").asString()).isEqualTo("PURPOSE_INVALID");

        // modify picks LC_REQ_MODIFY: applicationNo and amount are missing, only the first is reported
        HttpResponse<String> modify = post("{\"purpose\": \"modify\"}");
        JsonNode m = JSON.readTree(modify.body());
        assertThat(modify.statusCode()).isEqualTo(400);
        assertThat(m.get("errorCode").asString()).isEqualTo("GW-400-SCHEMA");
        assertThat(m.get("details")).hasSize(1);

        HttpResponse<String> delete = post("{\"purpose\": \"delete\", \"applicationNo\": \"ELJPU07631S26\"}");
        assertThat(delete.statusCode()).as(delete.body()).isEqualTo(200);
    }
}
