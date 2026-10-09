package com.mhamzah.gateway.storage;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Where a file storage step puts files: a local directory or an S3 bucket. Keys are relative ({@code 2026/10/a.pdf});
 * the store adds its own prefix. Failures are {@link StorageException}s.
 */
public interface FileStore extends AutoCloseable {

    /** What a store returns for a stored file. */
    record Stored(String key, String location, String etag) {}

    String name();

    /** {@code local} or {@code s3}. */
    String type();

    /** For Studio and the audit trail: {@code s3://bucket/prefix} or {@code file:/dir}; never credentials. */
    String location();

    /** Allowed content types ({@code image/*} style); empty = any. */
    List<String> allowedTypes();

    /** Bytes; 0 = no limit of its own. */
    long maxSize();

    Stored put(String key, byte[] content, String contentType, String filename, String sha256, Duration timeout);

    /** True when something was deleted (S3 cannot tell, so true). */
    boolean delete(String key, Duration timeout);

    /**
     * Checks that the store can be used: the directory is writable (or can be created), the bucket is reachable with
     * these credentials. Returns what was checked; throws {@link StorageException} with the reason otherwise.
     */
    String check(Duration timeout);

    @Override
    default void close() {}

    /** Whether {@code contentType} matches one of {@code allowedTypes} (empty = any; {@code image/*} matches subtypes). */
    default boolean accepts(String contentType) {
        if (allowedTypes().isEmpty()) {
            return true;
        }
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";")[0].strip();
        for (String allowed : allowedTypes()) {
            String a = allowed.toLowerCase(Locale.ROOT);
            if (a.equals(ct) || a.equals("*/*") || a.endsWith("/*") && ct.startsWith(a.substring(0, a.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    /** Thrown by stores; {@code timeout} when the operation ran out of time. */
    class StorageException extends RuntimeException {
        private final boolean timeout;

        public StorageException(String message, Throwable cause, boolean timeout) {
            super(message, cause);
            this.timeout = timeout;
        }

        public boolean timeout() {
            return timeout;
        }
    }
}
