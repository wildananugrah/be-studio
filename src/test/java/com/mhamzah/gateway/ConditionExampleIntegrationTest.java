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

/** The condition demo (111-dev-demo-condition.xml): checks route to named responses or on to the debit. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, WireMockConfiguration.class})
class ConditionExampleIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    private HttpResponse<String> post(String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/transfers/checked"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void checksRouteToTheirResponsesOrOnToTheDebit() throws Exception {
        HttpResponse<String> ok = post("{\"fromAccount\":\"1001\",\"toAccount\":\"2002\",\"amount\":150000}");
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo("{\"refNo\":\"TRX20261008001\",\"status\":\"POSTED\"}");

        HttpResponse<String> zero = post("{\"fromAccount\":\"1001\",\"toAccount\":\"2002\",\"amount\":0}");
        assertThat(zero.statusCode()).isEqualTo(400);
        assertThat(zero.body()).isEqualTo("{\"error\":\"INVALID_TRANSFER\",\"reason\":\"amount must be positive\"}");

        HttpResponse<String> missing = post("{\"fromAccount\":\"1001\",\"toAccount\":\"2002\"}");
        assertThat(missing.statusCode()).isEqualTo(400);

        HttpResponse<String> same = post("{\"fromAccount\":\"1001\",\"toAccount\":\"1001\",\"amount\":10}");
        assertThat(same.statusCode()).isEqualTo(400);
        assertThat(same.body()).contains("\"reason\":\"same account\"");

        HttpResponse<String> large = post("{\"fromAccount\":\"1001\",\"toAccount\":\"2002\",\"amount\":60000000}");
        assertThat(large.statusCode()).isEqualTo(202);
        assertThat(large.body()).isEqualTo("{\"status\":\"PENDING_APPROVAL\",\"amount\":60000000}");
    }
}
