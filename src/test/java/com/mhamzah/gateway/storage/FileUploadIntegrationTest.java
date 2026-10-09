package com.mhamzah.gateway.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.TestcontainersConfiguration;
import com.mhamzah.gateway.WireMockConfiguration;
import com.mhamzah.gateway.mapping.JsonValues;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/** Uploads (multipart, raw body, form) through the real gateway into LOCAL_FILES (104-dev-demo-file-flows.xml). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "gateway.files.max-size=64KB",
    "gateway.files.max-request-size=80KB",
})
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, WireMockConfiguration.class})
class FileUploadIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry r) {
        r.add("gateway.storages.LOCAL_FILES.base-dir", () -> dir.toString());
    }

    @LocalServerPort
    int port;

    record Response(int status, JsonNode json, String text) {}

    private Response send(HttpRequest.Builder b) throws Exception {
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), r.body().isBlank() ? null : JsonValues.MAPPER.readTree(r.body()), r.body());
    }

    private HttpRequest.Builder to(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path));
    }

    /** A multipart body with one file part (field "file" unless null) and optional text fields. */
    private static HttpRequest.Builder multipart(HttpRequest.Builder b, String filename, String contentType, byte[] content,
            String... fields) {
        String boundary = "----gw" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i + 1 < fields.length; i += 2) {
            out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fields[i] + "\"\r\n\r\n"
                    + fields[i + 1] + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        if (filename != null) {
            out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                    + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.writeBytes(content);
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return b.header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
    }

    @Test
    void multipartUploadIsStoredAndDescribed() throws Exception {
        byte[] pdf = "%PDF-1.7 tiny test document".getBytes(StandardCharsets.US_ASCII);

        Response r = send(multipart(to("/v1/files"), "Q3 report.pdf", "application/pdf", pdf, "description", "quarterly"));

        assertThat(r.status()).as(r.text()).isEqualTo(201);
        String key = r.json().get("fileId").asString();
        assertThat(key).matches("\\d{4}/\\d{2}/[0-9a-f-]{36}-Q3_report\\.pdf");
        assertThat(r.json().get("filename").asString()).isEqualTo("Q3 report.pdf");
        assertThat(r.json().get("contentType").asString()).isEqualTo("application/pdf");
        assertThat(r.json().get("size").asInt()).isEqualTo(pdf.length);
        assertThat(r.json().get("description").asString()).isEqualTo("quarterly");
        assertThat(r.json().get("sha256").asString()).isEqualTo(
                HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(pdf)));
        assertThat(Files.readAllBytes(dir.resolve(key))).isEqualTo(pdf);

        Response deleted = send(to("/v1/files/" + key).DELETE());
        assertThat(deleted.status()).as(deleted.text()).isEqualTo(200);
        assertThat(deleted.json().get("deleted").asBoolean()).isTrue();
        assertThat(dir.resolve(key)).doesNotExist();
    }

    @Test
    void aClientFileNameCannotLeaveTheStorageDirectory() throws Exception {
        Response r = send(multipart(to("/v1/files"), "../../etc/passwd", "text/plain", "x".getBytes(StandardCharsets.UTF_8)));
        assertThat(r.status()).as(r.text()).isEqualTo(201);
        String key = r.json().get("fileId").asString();
        assertThat(key).endsWith("-passwd").doesNotContain("..");
        assertThat(dir.resolve(key).normalize()).startsWith(dir).exists();
    }

    @Test
    void rawBodyIsTheFile() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};

        Response r = send(to("/v1/documents").header("Content-Type", "image/png").header("X-File-Name", "logo.png")
                .POST(HttpRequest.BodyPublishers.ofByteArray(png)));

        assertThat(r.status()).as(r.text()).isEqualTo(201);
        assertThat(r.json().get("filename").asString()).isEqualTo("logo.png");
        assertThat(r.json().get("contentType").asString()).isEqualTo("image/png");
        assertThat(Files.readAllBytes(dir.resolve(r.json().get("fileId").asString()))).isEqualTo(png);
    }

    @Test
    void storageRefusesTypesItDoesNotAllowAndMissingFiles() throws Exception {
        Response exe = send(multipart(to("/v1/files"), "setup.exe", "application/x-msdownload", new byte[] {1, 2}));
        assertThat(exe.status()).isEqualTo(415);
        assertThat(exe.json().get("errorCode").asString()).isEqualTo("GW-415-FILE");

        Response none = send(multipart(to("/v1/files"), null, null, null, "description", "no file"));
        assertThat(none.status()).as(none.text()).isEqualTo(400);
        assertThat(none.json().get("errorCode").asString()).isEqualTo("GW-400-MAPPING");
    }

    @Test
    void tooLargeUploadsAreRefusedBeforeAnyFlowRuns() throws Exception {
        byte[] big = new byte[100 * 1024];
        Response multipartTooBig = send(multipart(to("/v1/files"), "big.bin", "application/octet-stream", big));
        assertThat(multipartTooBig.status()).as(multipartTooBig.text()).isEqualTo(413);
        assertThat(multipartTooBig.json().get("errorCode").asString()).isEqualTo("GW-413-FILE");

        Response rawTooBig = send(to("/v1/documents").header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(big)));
        assertThat(rawTooBig.status()).isEqualTo(413);
    }

    @Test
    void formFieldsAreTheBody() throws Exception {
        String suffix = Long.toString(System.nanoTime() % 1_000_000);
        Response r = send(to("/v1/users?ignored=1").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=form." + suffix + "&fullName=Form+User")));
        assertThat(r.status()).as(r.text()).isEqualTo(201);
        assertThat(r.json().get("fullName").asString()).isEqualTo("Form User");
    }
}
