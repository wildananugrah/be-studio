package com.mhamzah.gateway.storage;

import com.mhamzah.gateway.config.GatewayProperties;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * Objects in an S3 bucket (or an S3-compatible server via {@code endpoint}, e.g. MinIO with {@code path-style:
 * true}). The key is {@code prefix + key}; the object gets the content type, and user metadata {@code filename}
 * (the client's file name) and {@code sha256}. Credentials: {@code access-key} / {@code secret-key}, else the default
 * AWS chain (environment, profile, instance role).
 */
public final class S3FileStore implements FileStore {

    private final String name;
    private final String bucket;
    private final String prefix;
    private final S3Client s3;
    private final List<String> allowedTypes;
    private final long maxSize;

    public S3FileStore(String name, GatewayProperties.FileStorage cfg, long maxSize) {
        this.name = name;
        this.bucket = cfg.bucket().strip();
        String p = cfg.prefix() == null ? "" : cfg.prefix().strip();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        this.prefix = p.isEmpty() || p.endsWith("/") ? p : p + "/";
        this.allowedTypes = cfg.allowedTypes();
        this.maxSize = maxSize;
        var b = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(cfg.region() == null || cfg.region().isBlank() ? "us-east-1" : cfg.region().strip()))
                .forcePathStyle(cfg.pathStyle());
        if (cfg.endpoint() != null && !cfg.endpoint().isBlank()) {
            b.endpointOverride(URI.create(cfg.endpoint().strip()));
        }
        if (cfg.accessKey() != null && !cfg.accessKey().isBlank()) {
            b.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(cfg.accessKey().strip(), cfg.secretKey() == null ? "" : cfg.secretKey())));
        } else {
            b.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        this.s3 = b.build();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String type() {
        return "s3";
    }

    @Override
    public String location() {
        return "s3://" + bucket + "/" + prefix;
    }

    @Override
    public List<String> allowedTypes() {
        return allowedTypes;
    }

    @Override
    public long maxSize() {
        return maxSize;
    }

    @Override
    public Stored put(String key, byte[] content, String contentType, String filename, String sha256, Duration timeout) {
        String objectKey = prefix + key;
        try {
            PutObjectResponse r = s3.putObject(req -> req.bucket(bucket).key(objectKey).contentType(contentType)
                            .metadata(Map.of("filename", ascii(filename), "sha256", sha256))
                            .overrideConfiguration(o -> o.apiCallTimeout(timeout)),
                    RequestBody.fromBytes(content));
            String etag = r.eTag() == null ? null : r.eTag().replace("\"", "");
            return new Stored(objectKey, "s3://" + bucket + "/" + objectKey, etag);
        } catch (SdkException e) {
            throw new StorageException("S3 put of " + objectKey + " in " + name + " failed: " + e.getMessage(), e,
                    e instanceof ApiCallTimeoutException);
        }
    }

    @Override
    public String check(Duration timeout) {
        try {
            s3.headBucket(req -> req.bucket(bucket).overrideConfiguration(o -> o.apiCallTimeout(timeout)));
            return "bucket " + bucket + " is reachable with these credentials";
        } catch (SdkException e) {
            throw new StorageException("bucket " + bucket + ": " + e.getMessage(), e, e instanceof ApiCallTimeoutException);
        }
    }

    @Override
    public boolean delete(String key, Duration timeout) {
        String objectKey = prefix + key;
        try {
            s3.deleteObject(req -> req.bucket(bucket).key(objectKey).overrideConfiguration(o -> o.apiCallTimeout(timeout)));
            return true;
        } catch (SdkException e) {
            throw new StorageException("S3 delete of " + objectKey + " in " + name + " failed: " + e.getMessage(), e,
                    e instanceof ApiCallTimeoutException);
        }
    }

    /** S3 user metadata travels as HTTP headers: keep it ASCII. */
    private static String ascii(String s) {
        return s == null ? "" : s.replaceAll("[^\\x20-\\x7E]", "_");
    }

    @Override
    public void close() {
        s3.close();
    }
}
