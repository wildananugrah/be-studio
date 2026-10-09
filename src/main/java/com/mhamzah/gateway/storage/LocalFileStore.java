package com.mhamzah.gateway.storage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

/**
 * Files under one directory ({@code base-dir}). A key becomes a relative path under it; the resolved path is checked
 * to stay inside the directory. Writes go to a temporary file first and are moved into place, so a reader never
 * sees half a file; an existing file with the same key is replaced.
 */
public final class LocalFileStore implements FileStore {

    private final String name;
    private final Path baseDir;
    private final List<String> allowedTypes;
    private final long maxSize;

    public LocalFileStore(String name, Path baseDir, List<String> allowedTypes, long maxSize) {
        this.name = name;
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.allowedTypes = List.copyOf(allowedTypes);
        this.maxSize = maxSize;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String type() {
        return "local";
    }

    @Override
    public String location() {
        return baseDir.toUri().toString();
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
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                Files.write(tmp, content);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new StorageException("cannot write " + key + " in " + name + ": " + e.getMessage(), e, false);
        }
        return new Stored(key, target.toUri().toString(), null);
    }

    @Override
    public String check(Duration timeout) {
        if (Files.isDirectory(baseDir)) {
            if (!Files.isWritable(baseDir)) {
                throw new StorageException(baseDir + " exists but is not writable", null, false);
            }
            return baseDir + " exists and is writable";
        }
        Path p = baseDir.getParent();
        while (p != null && !Files.exists(p)) {
            p = p.getParent();
        }
        if (p == null || !Files.isDirectory(p) || !Files.isWritable(p)) {
            throw new StorageException(baseDir + " does not exist and cannot be created" + (p == null ? "" : " (" + p
                    + " is not a writable directory)"), null, false);
        }
        return baseDir + " does not exist yet; it is created by the first upload";
    }

    @Override
    public boolean delete(String key, Duration timeout) {
        try {
            return Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new StorageException("cannot delete " + key + " in " + name + ": " + e.getMessage(), e, false);
        }
    }

    private Path resolve(String key) {
        Path p = baseDir.resolve(key).normalize();
        if (!p.startsWith(baseDir) || p.equals(baseDir)) {
            throw new StorageException("key '" + key + "' is outside the storage directory", null, false);
        }
        return p;
    }
}
