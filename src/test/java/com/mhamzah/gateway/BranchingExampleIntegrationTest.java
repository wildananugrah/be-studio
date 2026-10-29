package com.mhamzah.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.mapping.JsonValues;
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
import tools.jackson.databind.JsonNode;

/** The branching demo (107/109): debit ✓→ notify, ✗→ reversal → the named response "reversed" (422). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, WireMockConfiguration.class})
class BranchingExampleIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private HttpResponse<String> transfer(String amount, String correlationId) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/transfers/with-reversal"))
                .header("Content-Type", "application/json").header("X-Correlation-Id", correlationId)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"fromAccount\":\"1001\",\"toAccount\":\"2002\",\"amount\":" + amount + "}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private List<String> steps(String correlationId, int count) throws InterruptedException {
        List<Map<String, Object>> rows = GatewayIntegrationTest.await(() -> jdbc.queryForList(
                "SELECT s.step_name, s.outcome FROM gw_audit_step s JOIN gw_audit_transaction t ON t.id = s.transaction_id"
                        + " WHERE t.correlation_id = ? ORDER BY s.id", correlationId), l -> l.size() == count);
        return rows.stream().map(r -> r.get("step_name") + "=" + r.get("outcome")).toList();
    }

    @Test
    void successGoesToNotifyAndSkipsTheReversal() throws Exception {
        HttpResponse<String> r = transfer("150000", "branch-ok");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode body = JsonValues.MAPPER.readTree(r.body());
        assertThat(body.get("refNo").asString()).isEqualTo("TRX20261008001");
        assertThat(body.get("notified").asString()).isEqualTo("SUCCESS");
        assertThat(steps("branch-ok", 3)).containsExactlyInAnyOrder("debit=SUCCESS", "reversal=SKIPPED", "notify=SUCCESS");
    }

    @Test
    void failureRunsTheReversalThenAnswersWithTheReversedResponse() throws Exception {
        HttpResponse<String> r = transfer("999999999", "branch-fail");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(422);
        assertThat(r.body()).isEqualTo("{\"status\":\"REVERSED\",\"reason\":\"Insufficient funds\","
                + "\"reversalRef\":\"REV-0001\",\"correlationId\":\"branch-fail\"}");
        assertThat(steps("branch-fail", 3)).containsExactly("debit=FAILED", "reversal=SUCCESS", "notify=SKIPPED");
        // a 2xx whose success_expr is false: the audit says why the step failed
        Map<String, Object> debit = jdbc.queryForMap("SELECT s.http_status, s.error_message FROM gw_audit_step s"
                + " JOIN gw_audit_transaction t ON t.id = s.transaction_id WHERE t.correlation_id = ? AND s.step_name = 'debit'",
                "branch-fail");
        assertThat(((Number) debit.get("http_status")).intValue()).isBetween(200, 299);
        assertThat(debit.get("error_message")).isEqualTo("DOWNSTREAM_BUSINESS_ERROR: success_expr of step 'debit' is false");
    }
}
