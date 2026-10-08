package com.mhamzah.gateway.extension.custom.user;

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

/** The tbl_ms_user CRUD example (MsUserHandlers + flows in 102-dev-demo-ms-user.xml), through the real gateway. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, WireMockConfiguration.class})
class MsUserCrudIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    record Response(int status, HttpResponse<String> raw) {
        JsonNode json() {
            return raw.body().isBlank() ? JsonValues.MAPPER.createObjectNode() : JsonValues.MAPPER.readTree(raw.body());
        }
    }

    Response call(String method, String path, String body) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").build();
        HttpResponse<String> raw = HTTP.send(r, HttpResponse.BodyHandlers.ofString());
        return new Response(raw.statusCode(), raw);
    }

    @Test
    void createRetrieveUpdateDelete() throws Exception {
        Response created = call("POST", "/v1/users",
                "{\"username\":\"rina.k\",\"fullName\":\"Rina Kartika\",\"email\":\"rina@example.com\",\"phone\":\"+6281234\"}");
        assertThat(created.status()).as(created.raw().body()).isEqualTo(201);
        long id = created.json().get("id").asLong();
        assertThat(created.json().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(created.raw().headers().firstValue("Location")).hasValue("/api/v1/users/" + id);
        assertThat(created.raw().headers().firstValue("X-Correlation-Id")).isPresent();

        Response detail = call("GET", "/v1/users/" + id, null);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.json().get("fullName").asString()).isEqualTo("Rina Kartika");

        Response updated = call("PUT", "/v1/users/" + id, "{\"fullName\":\"Rina K. Sari\",\"status\":\"INACTIVE\"}");
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.json().get("fullName").asString()).isEqualTo("Rina K. Sari");
        assertThat(updated.json().get("email").asString()).isEqualTo("rina@example.com");
        assertThat(updated.json().get("status").asString()).isEqualTo("INACTIVE");

        Response deleted = call("DELETE", "/v1/users/" + id, null);
        assertThat(deleted.status()).isEqualTo(204);
        assertThat(deleted.raw().body()).isEmpty();
        assertThat(call("GET", "/v1/users/" + id, null).json().get("errorCode").asString()).isEqualTo("USER_NOT_FOUND");
        assertThat(call("DELETE", "/v1/users/" + id, null).status()).isEqualTo(404);
        assertThat(call("PUT", "/v1/users/" + id, "{\"phone\":\"0811111111\"}").status()).isEqualTo(404);
    }

    @Test
    void listsWithPaginationAndFilters() throws Exception {
        for (int i = 1; i <= 4; i++) {
            assertThat(call("POST", "/v1/users", "{\"username\":\"page.user" + i + "\",\"fullName\":\"Page User " + i + "\"}")
                    .status()).isEqualTo(201);
        }
        Response first = call("GET", "/v1/users?page=1&size=2&search=page.user", null);
        assertThat(first.status()).isEqualTo(200);
        JsonNode p1 = first.json();
        assertThat(p1.get("content")).hasSize(2);
        assertThat(p1.get("page").asInt()).isEqualTo(1);
        assertThat(p1.get("size").asInt()).isEqualTo(2);
        assertThat(p1.get("totalElements").asLong()).isEqualTo(4);
        assertThat(p1.get("totalPages").asLong()).isEqualTo(2);

        JsonNode p2 = call("GET", "/v1/users?page=2&size=2&search=page.user", null).json();
        assertThat(p2.get("content").get(0).get("username").asString()).isEqualTo("page.user3");
        assertThat(call("GET", "/v1/users?page=3&size=2&search=page.user", null).json().get("content")).isEmpty();

        JsonNode inactive = call("GET", "/v1/users?status=inactive", null).json();
        assertThat(inactive.get("content").toString()).contains("andi.wijaya").doesNotContain("budi.santoso");

        JsonNode defaults = call("GET", "/v1/users", null).json();
        assertThat(defaults.get("size").asInt()).isEqualTo(MsUserHandlers.DEFAULT_SIZE);

        Response bad = call("GET", "/v1/users?page=0&size=500", null);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.json().get("details").toString()).contains("page", "size");
    }

    @Test
    void rejectsInvalidInput() throws Exception {
        Response missing = call("POST", "/v1/users", "{\"username\":\"x1x\"}");
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.json().get("errorCode").asString()).isEqualTo("GW-400-SCHEMA");

        assertThat(call("POST", "/v1/users", "{\"username\":\"dupe.user\",\"fullName\":\"A\"}").status()).isEqualTo(201);
        Response dupe = call("POST", "/v1/users", "{\"username\":\"dupe.user\",\"fullName\":\"B\"}");
        assertThat(dupe.status()).isEqualTo(409);
        assertThat(dupe.json().get("errorCode").asString()).isEqualTo("USERNAME_TAKEN");

        Response rename = call("PUT", "/v1/users/1", "{\"username\":\"other\"}");
        assertThat(rename.status()).isEqualTo(400);

        Response badId = call("GET", "/v1/users/abc", null);
        assertThat(badId.status()).isEqualTo(400);
        assertThat(badId.json().get("errorCode").asString()).isEqualTo("INVALID_ID");
    }
}
