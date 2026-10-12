package com.mhamzah.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** The transform demo (110-dev-demo-transform.xml): account → summary (TRANSFORM) → notify, response = summary. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, WireMockConfiguration.class})
class TransformExampleIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theTransformResultFeedsTheNextStepAndTheResponse() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/accounts/1001/summary"))
                .header("X-Channel", "MOBILE").header("X-Correlation-Id", "transform-1").build(), HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"account\":{\"number\":\"1001\",\"holder\":\"BUDI SANTOSO\",\"status\":\"ACTIVE\"},"
                + "\"balance\":1500000.00,\"channel\":\"MOBILE\",\"source\":\"CORE_BANKING\",\"notified\":\"SUCCESS\"}");

        List<Map<String, Object>> steps = GatewayIntegrationTest.await(() -> jdbc.queryForList(
                "SELECT s.step_name, s.http_method, s.outcome, s.url, s.request_payload FROM gw_audit_step s"
                        + " JOIN gw_audit_transaction t ON t.id = s.transaction_id WHERE t.correlation_id = ? ORDER BY s.id",
                "transform-1"), l -> l.size() == 3);
        assertThat(steps).extracting(m -> m.get("step_name") + "=" + m.get("http_method") + "/" + m.get("outcome"))
                .containsExactly("account=GET/SUCCESS", "summary=TRANSFORM/SUCCESS", "notify=POST/SUCCESS");
        // nothing was called by the transform; notify sent its result
        assertThat(steps.get(1).get("url")).isNull();
        assertThat(String.valueOf(steps.get(2).get("request_payload"))).contains("\"event\":\"ACCOUNT_VIEWED\"", "\"holder\":\"BUDI SANTOSO\"");
    }
}
