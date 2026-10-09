package com.mhamzah.gateway.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mhamzah.gateway.config.GatewayProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/** S3FileStore against an S3 API server (Adobe S3Mock in a container; it does not check credentials). */
class S3FileStoreTest {

    static final GenericContainer<?> S3 = new GenericContainer<>("adobe/s3mock:4.7.0")
            .withExposedPorts(9090).waitingFor(Wait.forHttp("/").forPort(9090).forStatusCodeMatching(c -> c < 500));
    static S3Client admin;

    static String endpoint() {
        return "http://" + S3.getHost() + ":" + S3.getMappedPort(9090);
    }

    @BeforeAll
    static void start() {
        S3.start();
        admin = S3Client.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.US_EAST_1)
                .endpointOverride(URI.create(endpoint())).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        admin.createBucket(b -> b.bucket("gateway-files"));
    }

    @AfterAll
    static void stop() {
        if (admin != null) {
            admin.close();
        }
        S3.stop();
    }

    private static S3FileStore store(String bucket) {
        return new S3FileStore("S3_FILES", new GatewayProperties.FileStorage("s3", null, bucket, "/uploads", "us-east-1",
                endpoint(), "test", "test", true, List.of("application/pdf"), null), 0);
    }

    @Test
    void storesUnderThePrefixWithTypeAndMetadataThenDeletes() {
        try (S3FileStore store = store("gateway-files")) {
            byte[] content = "%PDF test".getBytes(StandardCharsets.US_ASCII);

            FileStore.Stored stored = store.put("2026/10/a-report.pdf", content, "application/pdf", "report é.pdf",
                    "abc123", Duration.ofSeconds(10));

            assertThat(stored.key()).isEqualTo("uploads/2026/10/a-report.pdf");
            assertThat(stored.location()).isEqualTo("s3://gateway-files/uploads/2026/10/a-report.pdf");
            assertThat(stored.etag()).isNotBlank();
            ResponseBytes<GetObjectResponse> got = admin.getObjectAsBytes(b -> b.bucket("gateway-files").key(stored.key()));
            assertThat(got.asByteArray()).isEqualTo(content);
            assertThat(got.response().contentType()).isEqualTo("application/pdf");
            assertThat(got.response().metadata()).containsEntry("sha256", "abc123").containsEntry("filename", "report _.pdf");
            assertThat(store.location()).isEqualTo("s3://gateway-files/uploads/");
            assertThat(store.accepts("application/pdf")).isTrue();
            assertThat(store.accepts("image/png")).isFalse();

            assertThat(store.delete("2026/10/a-report.pdf", Duration.ofSeconds(10))).isTrue();
            assertThatThrownBy(() -> admin.getObject(b -> b.bucket("gateway-files").key(stored.key())))
                    .isInstanceOf(NoSuchKeyException.class);
        }
    }

    @Test
    void failuresAreStorageExceptions() {
        try (S3FileStore noBucket = store("no-such-bucket")) {
            assertThatThrownBy(() -> noBucket.put("x.pdf", new byte[1], "application/pdf", "x.pdf", "0",
                    Duration.ofSeconds(10)))
                    .isInstanceOf(FileStore.StorageException.class).hasMessageContaining("bucket does not exist");
        }
    }
}
