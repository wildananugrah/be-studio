package com.mhamzah.gateway.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.TestcontainersConfiguration;
import com.mhamzah.gateway.WireMockConfiguration;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.mhamzah.gateway.mapping.JsonValues;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/** Form data in, JSON out (112-dev-demo-document-forward.xml): fields, a JSON part and the file as Base64. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, WireMockConfiguration.class})
class DocumentForwardIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    WireMockServer wireMock;

    @Test
    void anUploadIsForwardedAsOneJsonDocument() throws Exception {
        String boundary = "----fwd";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"note.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\nhello document\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"metadata\"\r\n"
                + "Content-Type: application/json\r\n\r\n{\"customer\":{\"id\":\"1001\"},\"tags\":[\"contract\",\"2026\"]}\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"description\"\r\n\r\nSigned \"final\" copy\r\n"
                + "--" + boundary + "--\r\n";
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/documents/forward"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(r.body()).isEqualTo("{\"documentId\":\"DOC-0001\",\"filename\":\"note.txt\",\"size\":14,\"customerId\":\"1001\"}");

        // what the core system received: plain JSON, nested metadata, the file as Base64
        JsonNode sent = JsonValues.MAPPER.readTree(wireMock.findAll(
                com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                        com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/core/documents"))).getLast().getBodyAsString());
        assertThat(sent.get("document").get("content").asString())
                .isEqualTo(Base64.getEncoder().encodeToString("hello document".getBytes()));
        assertThat(sent.get("document").get("contentType").asString()).startsWith("text/plain");
        assertThat(sent.get("tags").toString()).isEqualTo("[\"contract\",\"2026\"]");
        assertThat(sent.get("description").asString()).isEqualTo("Signed \"final\" copy");
    }
}
