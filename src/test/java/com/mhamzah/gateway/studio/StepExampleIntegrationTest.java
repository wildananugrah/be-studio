package com.mhamzah.gateway.studio;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.TestcontainersConfiguration;
import com.mhamzah.gateway.WireMockConfiguration;
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

/**
 * Teaching Studio what a step's downstream answers: a pasted XML / SOAP example is decoded like the step's codec
 * will, and the last real response of a step comes from the audit trail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "gateway.admin.token=studio-token",
    "gateway.studio.enabled=true",
    "gateway.assistant.enabled=false",
})
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, WireMockConfiguration.class})
class StepExampleIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Admin-Token", "studio-token").header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aPastedSoapResponseIsDecodedLikeTheStepWill() throws Exception {
        String soap = "<soapenv:Envelope xmlns:soapenv=\\\"http://schemas.xmlsoap.org/soap/envelope/\\\"><soapenv:Body>"
                + "<q0:inquiryResponse xmlns:q0=\\\"urn:x\\\"><responseCode>00</responseCode><name>BUDI</name></q0:inquiryResponse>"
                + "</soapenv:Body></soapenv:Envelope>";
        HttpResponse<String> r = send("POST", "/studio/api/decode", "{\"text\":\"" + soap + "\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(JsonValues.MAPPER.readTree(r.body()).get("body").toString())
                .isEqualTo("{\"inquiryResponse\":{\"responseCode\":\"00\",\"name\":\"BUDI\"}}");

        assertThat(send("POST", "/studio/api/decode", "{\"text\":\"{\\\"a\\\":1}\"}").body()).contains("\"body\":{\"a\":1}");
        assertThat(send("POST", "/studio/api/decode", "{\"text\":\"<a><b>\",\"codec\":\"xmlCodec\"}").statusCode()).isEqualTo(400);
        assertThat(send("POST", "/studio/api/decode", "{\"text\":\"<a/>\",\"codec\":\"nope\"}").body()).contains("no BodyCodec bean 'nope'");
    }

    @Test
    void theLastRealResponseOfAStepComesFromTheAuditTrail() throws Exception {
        assertThat(send("GET", "/api/v1/soap/accounts/1001?option=8", null).statusCode()).isEqualTo(200);

        JsonNode row = null;
        for (int i = 0; i < 50 && row == null; i++) {
            HttpResponse<String> r = send("GET", "/studio/api/audit/last-response?flow=SOAP_ACCOUNT_INQUIRY&step=inquiry", null);
            if (r.statusCode() == 200) {
                row = JsonValues.MAPPER.readTree(r.body());
            } else {
                Thread.sleep(100);
            }
        }
        assertThat(row).isNotNull();
        // the SOAP that came back, as stored by the audit (a JSON string holding the XML)
        assertThat(JsonValues.MAPPER.readTree(row.get("response_payload").asString()).asString())
                .contains("<soapenv:Envelope", "BUDI SANTOSO");
        assertThat(send("GET", "/studio/api/audit/last-response?flow=SOAP_ACCOUNT_INQUIRY&step=nope", null).statusCode())
                .isEqualTo(404);
    }
}
