package com.mhamzah.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.mapping.JsonValues;
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

/** The core's HTTP status passed through on success and failure (113-dev-demo-status-passthrough.xml). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, WireMockConfiguration.class})
class StatusPassthroughIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    private HttpResponse<String> profile(String accountNo) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/accounts/" + accountNo + "/profile"))
                .header("X-Correlation-Id", "status-it-" + accountNo).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> r) {
        return JsonValues.MAPPER.readTree(r.body());
    }

    @Test
    void successStatusesComeFromTheCore() throws Exception {
        HttpResponse<String> ok = profile("1001");
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo("{\"accountNo\":\"1001\",\"accountName\":\"BUDI SANTOSO\",\"segment\":\"PRIORITY\"}");

        HttpResponse<String> accepted = profile("2002");
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        assertThat(json(accepted).get("segment").asString()).isEqualTo("PENDING_VERIFICATION");
    }

    @Test
    void failuresKeepTheCoreStatusAndAreMapped() throws Exception {
        HttpResponse<String> notFound = profile("9999");
        assertThat(notFound.statusCode()).as(notFound.body()).isEqualTo(404);
        assertThat(notFound.body()).isEqualTo("{\"errorCode\":\"NF01\",\"errorMessage\":\"Account not found\","
                + "\"correlationId\":\"status-it-9999\"}");

        HttpResponse<String> blocked = profile("3003");
        assertThat(blocked.statusCode()).isEqualTo(409);
        assertThat(json(blocked).get("errorCode").asString()).isEqualTo("BLK01");

        // the lookup shows the core's 503 as 502
        HttpResponse<String> maintenance = profile("5005");
        assertThat(maintenance.statusCode()).isEqualTo(502);
        assertThat(json(maintenance).get("errorMessage").asString()).isEqualTo("Core system under maintenance");
    }
}
