package com.mhamzah.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.mapping.JsonValues;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * The body template examples (105-dev-demo-body-templates.xml) against their WireMock stubs, which only answer
 * when the XML / JSON that arrives has the placeholders filled in (and escaped) correctly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, WireMockConfiguration.class})
class BodyTemplateExamplesIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    record Response(int status, JsonNode json, String text) {}

    private Response call(String method, String path, String body) throws Exception {
        return call(method, path, body, null);
    }

    private Response call(String method, String path, String body, String correlationId) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (correlationId != null) {
            b.header("X-Correlation-Id", correlationId);
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), JsonValues.MAPPER.readTree(r.body()), r.body());
    }

    @Test
    void soapInquiryFromAPathValue() throws Exception {
        Response r = call("GET", "/v1/soap/accounts/1001?option=8", null);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(r.text()).isEqualTo(
                "{\"accountNo\":\"1001\",\"name\":\"BUDI SANTOSO\",\"currency\":\"IDR\",\"balance\":1500000.50,\"status\":\"ACTIVE\"}");

        assertThat(call("GET", "/v1/soap/accounts/2002", null).json().get("status").asString()).isEqualTo("BLOCKED");

        Response missing = call("GET", "/v1/soap/accounts/5555", null);
        assertThat(missing.status()).isEqualTo(422);
        assertThat(missing.json().get("step").asString()).isEqualTo("inquiry");
    }

    @Test
    void soapTransferFromAJsonBodyWithAConverterAndEscaping() throws Exception {
        Response r = call("POST", "/v1/soap/transfers",
                "{\"fromAccount\":\"1001\",\"toAccount\":\"2002\",\"amount\":150000,\"remark\":\"Rent & utilities <October>\"}");
        assertThat(r.status()).as(r.text()).isEqualTo(201);
        assertThat(r.json().get("transferId").asString()).isEqualTo("TRF-20261009-0001");
        assertThat(r.json().get("status").asString()).isEqualTo("POSTED");
        assertThat(r.json().get("reference").asString()).isNotBlank();

        // the stub accepts any well-formed transfer (an unescaped remark would break the XML) with the amount at two
        // decimals (DECIMAL_SCALE:2) and two different accounts; the same account on both sides is rejected (51)
        Response plain = call("POST", "/v1/soap/transfers",
                "{\"fromAccount\":\"1001\",\"toAccount\":\"2002\",\"amount\":\"150000\",\"remark\":\"Unit test\"}");
        assertThat(plain.status()).as(plain.text()).isEqualTo(201);
        Response other = call("POST", "/v1/soap/transfers",
                "{\"fromAccount\":\"1001\",\"toAccount\":\"1001\",\"amount\":99,\"remark\":\"x\"}", "soap-audit-1");
        assertThat(other.status()).isEqualTo(422);

        // the audit keeps both bodies as they were on the wire: the SOAP sent and the SOAP that came back
        List<java.util.Map<String, Object>> steps = GatewayIntegrationTest.await(() -> jdbc.queryForList(
                "SELECT s.request_payload, s.response_payload FROM gw_audit_step s JOIN gw_audit_transaction t"
                        + " ON t.id = s.transaction_id WHERE t.correlation_id = ?", "soap-audit-1"), l -> !l.isEmpty());
        String sent = JsonValues.MAPPER.readTree(String.valueOf(steps.get(0).get("request_payload"))).asString();
        String received = JsonValues.MAPPER.readTree(String.valueOf(steps.get(0).get("response_payload"))).asString();
        assertThat(sent).contains("<soapenv:Envelope", "<amount>99.00</amount>");
        assertThat(received).contains("<soapenv:Envelope", "<responseCode>51</responseCode>", "Transfer rejected");
    }

    @Test
    void jsonTemplateWithDefaultsAndAnArray() throws Exception {
        Response r = call("POST", "/v1/template/notify",
                "{\"account\":\"1001\",\"amount\":150000,\"message\":\"Say \\\"hi\\\"\",\"recipients\":[\"0812000001\",\"0812000002\"]}");
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(r.text()).isEqualTo("{\"status\":\"SENT\",\"messageId\":\"MSG-0001\"}");
    }
}
