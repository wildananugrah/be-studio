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

/**
 * The pass-through demos. ACCOUNT_DETAIL (116-dev-demo-pass-through.xml): COPY rules pass acctName and detail.openDate
 * through, the copy of detail.branch is overridden by the rule that renames it to detail.cabang, internalRef is left
 * out. LC_APPLICATIONS (117-dev-demo-lc-applications.xml): the whole list is passed through and each item's
 * applicationNo is renamed to referenceNumber; REMOVE rules (119-dev-demo-remove.xml) drop bniReferenceNo and actionable
 * from every item, and the X-Channel-Id header from the call (the WireMock stub matches only without it).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, WireMockConfiguration.class})
class PassThroughExampleIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Test
    void copiedFieldsKeepTheirPlaceAndRemappedOnesAreRenamed() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/accounts/1001/detail"))
                .header("X-Channel", "MOBILE").header("X-Correlation-Id", "pass-through-1").build(), HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"acctName\":\"BUDI SANTOSO\",\"detail\":{\"openDate\":\"20200101\",\"cabang\":\"JKT01\"},"
                + "\"account\":{\"number\":\"1001\"}}");
    }

    @Test
    void wholeListPassesThroughWithOneFieldRenamedInEveryItem() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/lc/applications"))
                .header("X-Channel", "MOBILE").header("X-Correlation-Id", "pass-through-lc").build(), HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        String item1 = "{\"lcNumber\":\"ELJPU07631S26-ADV001\",\"status\":\"PENDING_APPROVAL\",\"currentEvent\":\"LC_TRANSFER\","
                + "\"amount\":\"IDR 1,000,000.00\",\"applicantName\":\"PT JAYA ABADI\",\"createdDate\":\"2026-11-06T15:30:19\","
                + "\"beneficiaryName\":\"BRANTAS ABIPRAYA, PT\",\"submittedDate\":\"2026-06-26T00:00:00\",\"expiryDate\":\"2026-12-26\","
                + "\"openEventCount\":1,\"referenceNumber\":\"ELJPU07631S26\"}";
        String item2 = "{\"lcNumber\":null,\"status\":\"PENDING_APPROVAL\",\"currentEvent\":\"LC_EXP_CANCELLATION\","
                + "\"amount\":\"IDR 100,000,000.00\",\"applicantName\":\"PT Intisco Niagatama Semesta\",\"createdDate\":\"2026-10-07T16:28:09\","
                + "\"beneficiaryName\":\"PT Semen Indonesia\",\"submittedDate\":\"2026-10-07T16:28:09\",\"expiryDate\":\"2027-04-05\","
                + "\"openEventCount\":1,\"referenceNumber\":\"20261007014\"}";
        String item3 = "{\"lcNumber\":\"DUMMY/CANCEL/2026/0008\",\"status\":\"ADVISED\",\"currentEvent\":null,"
                + "\"amount\":\"USD 50,000.00\",\"applicantName\":\"Dummy Cancel Buyer Ltd\",\"createdDate\":\"2026-09-28T00:00:00\","
                + "\"beneficiaryName\":\"PT Nusantara Tekstil Jaya\",\"submittedDate\":\"2026-09-28T00:00:00\",\"expiryDate\":\"2027-06-30\","
                + "\"openEventCount\":null,\"referenceNumber\":\"DUMC-0008\"}";
        assertThat(r.body()).isEqualTo("{\"responseCode\":\"200E000\",\"responseMessage\":\"Successful\",\"code\":200,"
                + "\"message\":\"Success\",\"data\":[" + item1 + "," + item2 + "," + item3 + "],"
                + "\"meta\":{\"page\":1,\"size\":50,\"totalItems\":58,\"totalPages\":2},\"partnerReferenceNo\":\"REF-VALERIE\"}");
    }
}
