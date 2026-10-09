package com.mhamzah.gateway.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.TestcontainersConfiguration;
import com.mhamzah.gateway.WireMockConfiguration;
import com.mhamzah.gateway.mapping.JsonValues;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Storages configured in the database through Studio (gw_storage): an S3 storage is added with placeholders, checked,
 * enabled, used by the upload flow, and the file lands in the bucket; no restart, no application.yml change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "gateway.admin.token=studio-token",
    "gateway.studio.enabled=true",
    "gateway.assistant.enabled=false",
    "TEST_S3_SECRET=test",
})
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, WireMockConfiguration.class})
class StudioStorageIntegrationTest {

    static final GenericContainer<?> S3 = new GenericContainer<>("adobe/s3mock:4.7.0")
            .withExposedPorts(9090).waitingFor(Wait.forHttp("/").forPort(9090).forStatusCodeMatching(c -> c < 500));
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    static Path dir;

    static String endpoint() {
        return "http://" + S3.getHost() + ":" + S3.getMappedPort(9090);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        S3.start();
        try (S3Client admin = client()) {
            admin.createBucket(b -> b.bucket("studio-bucket"));
        }
        r.add("gateway.storages.LOCAL_FILES.base-dir", () -> dir.toString());
        r.add("TEST_S3_ENDPOINT", StudioStorageIntegrationTest::endpoint);
    }

    static S3Client client() {
        return S3Client.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.US_EAST_1)
                .endpointOverride(URI.create(endpoint())).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
    }

    @AfterAll
    static void stop() {
        S3.stop();
    }

    @LocalServerPort
    int port;

    record Response(int status, String text) {
        JsonNode json() {
            return JsonValues.MAPPER.readTree(text);
        }
    }

    Response call(String method, String path, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JsonValues.MAPPER.writeValueAsString(body)))
                .header("Content-Type", "application/json").header("X-Admin-Token", "studio-token");
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), r.body());
    }

    private static ObjectNode storage(JsonNode config, String code) {
        for (JsonNode s : config.get("storages")) {
            if (code.equals(s.get("code").asString())) {
                return (ObjectNode) s;
            }
        }
        throw new AssertionError("no storage " + code);
    }

    private static ObjectNode save(String version, JsonNode config) {
        ObjectNode body = JsonValues.MAPPER.createObjectNode();
        body.put("baseVersion", version);
        body.set("config", config);
        return body;
    }

    @Test
    void anS3StorageIsConfiguredCheckedAndUsedWithoutARestart() throws Exception {
        ObjectNode original = (ObjectNode) call("GET", "/studio/api/config", null).json();
        // the dev data has S3_FILES, disabled and still pointing at ${S3_BUCKET}
        assertThat(storage(original, "S3_FILES").get("enabled").asBoolean()).isFalse();
        assertThat(storage(original, "S3_FILES").get("bucket").asString()).isEqualTo("${S3_BUCKET}");

        ObjectNode changed = original.deepCopy();
        ObjectNode s3 = storage(changed, "S3_FILES");
        s3.put("bucket", "studio-bucket").put("prefix", "studio/").put("region", "us-east-1")
                .put("endpoint", "${TEST_S3_ENDPOINT}").put("pathStyle", true)
                .put("accessKey", "test").put("secretKey", "${TEST_S3_SECRET}").put("enabled", true);

        JsonNode ok = call("POST", "/studio/api/storages/check", s3).json();
        assertThat(ok.get("ok").asBoolean()).as(ok.toString()).isTrue();
        assertThat(ok.get("message").asString()).contains("bucket studio-bucket is reachable");
        ObjectNode wrongBucket = s3.deepCopy().put("bucket", "nope");
        JsonNode bad = call("POST", "/studio/api/storages/check", wrongBucket).json();
        assertThat(bad.get("ok").asBoolean()).isFalse();

        // the upload flow now stores on S3_FILES
        for (JsonNode f : changed.get("flows")) {
            if ("FILE_UPLOAD".equals(f.get("code").asString())) {
                ((ObjectNode) f.get("steps").get(0)).put("targetSystem", "S3_FILES");
            }
        }
        Response saved = call("PUT", "/studio/api/config", save(original.get("version").asString(), changed));
        assertThat(saved.status()).as(saved.text()).isEqualTo(200);
        JsonNode reread = call("GET", "/studio/api/config", null).json();
        assertThat(storage(reread, "S3_FILES").get("secretKey").asString()).isEqualTo("${TEST_S3_SECRET}");

        String boundary = "----gwtest";
        String multipart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"note.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\nstored on s3\r\n--" + boundary + "--\r\n";
        HttpResponse<String> up = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/files"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(multipart)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(up.statusCode()).as(up.body()).isEqualTo(201);
        String key = JsonValues.MAPPER.readTree(up.body()).get("fileId").asString();
        assertThat(key).startsWith("studio/").endsWith("-note.txt");
        try (S3Client admin = client()) {
            assertThat(admin.getObjectAsBytes(b -> b.bucket("studio-bucket").key(key)).asString(StandardCharsets.UTF_8))
                    .isEqualTo("stored on s3");
        }

        // an enabled storage whose placeholder cannot be resolved is a validation error, nothing is written
        ObjectNode broken = (ObjectNode) reread.deepCopy();
        storage(broken, "S3_FILES").put("bucket", "${NO_SUCH_VARIABLE}");
        Response rejected = call("PUT", "/studio/api/config", save(reread.get("version").asString(), broken));
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(rejected.text()).contains("storage 'S3_FILES': bucket", "NO_SUCH_VARIABLE");

        // a new local storage can be added the same way
        ObjectNode withLocal = (ObjectNode) reread.deepCopy();
        ((ArrayNode) withLocal.get("storages")).addObject().put("code", "ARCHIVE").put("type", "LOCAL")
                .put("baseDir", dir.resolve("archive").toString()).put("maxSize", "1MB");
        Response added = call("PUT", "/studio/api/config", save(reread.get("version").asString(), withLocal));
        assertThat(added.status()).as(added.text()).isEqualTo(200);
        assertThat(call("GET", "/studio/api/config", null).json().get("storages").toString()).contains("ARCHIVE");

        // back to the dev data for the other tests in this JVM
        JsonNode now = call("GET", "/studio/api/config", null).json();
        assertThat(call("PUT", "/studio/api/config", save(now.get("version").asString(), original)).status()).isEqualTo(200);
    }
}
