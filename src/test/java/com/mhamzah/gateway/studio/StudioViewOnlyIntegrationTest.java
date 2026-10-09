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
import tools.jackson.databind.node.ObjectNode;

/** gateway.studio.mode=view-only: everything can be read, nothing saved, no test runs; enforced by the server. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "gateway.admin.token=view-token",
    "gateway.studio.enabled=true",
    "gateway.studio.mode=view-only",
    "gateway.assistant.enabled=false",
})
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, WireMockConfiguration.class})
class StudioViewOnlyIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    private HttpResponse<String> call(String method, String path, Object body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JsonValues.MAPPER.writeValueAsString(body)))
                .header("Content-Type", "application/json").header("X-Admin-Token", "view-token").build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void readsWorkButSavingAndTestRunsAreRefused() throws Exception {
        HttpResponse<String> config = call("GET", "/studio/api/config", null);
        assertThat(config.statusCode()).isEqualTo(200);
        JsonNode cfg = JsonValues.MAPPER.readTree(config.body());

        JsonNode catalog = JsonValues.MAPPER.readTree(call("GET", "/studio/api/catalog", null).body());
        assertThat(catalog.get("studioMode").asString()).isEqualTo("view-only");

        assertThat(call("POST", "/studio/api/validate", cfg).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/studio/api/tests/ACCOUNT_INQUIRY/cases", null).statusCode()).isEqualTo(200);

        ObjectNode save = JsonValues.MAPPER.createObjectNode();
        save.put("baseVersion", cfg.get("version").asString());
        save.set("config", cfg);
        HttpResponse<String> saved = call("PUT", "/studio/api/config", save);
        assertThat(saved.statusCode()).isEqualTo(403);
        assertThat(saved.body()).contains("view-only");
        assertThat(JsonValues.MAPPER.readTree(call("GET", "/studio/api/config", null).body()).get("version").asString())
                .isEqualTo(cfg.get("version").asString());

        HttpResponse<String> run = call("POST", "/studio/api/tests/ACCOUNT_INQUIRY/runs",
                JsonValues.MAPPER.readTree("{\"cases\":[{\"name\":\"x\",\"method\":\"GET\",\"path\":\"/v1/accounts/1001\"}]}"));
        assertThat(run.statusCode()).isEqualTo(403);
        assertThat(run.body()).contains("running tests is disabled");
    }
}
