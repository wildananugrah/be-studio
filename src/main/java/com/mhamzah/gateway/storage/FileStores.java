package com.mhamzah.gateway.storage;

import com.mhamzah.gateway.config.GatewayProperties;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File stores. Those of {@code gateway.storages} (application.yml) are built at startup; an S3 storage without a
 * bucket is skipped there. Storages from the database ({@code gw_storage}) are built by {@link #build} when the
 * configuration is compiled; a store is cached by name and settings, so a reload reuses it (and its S3 client)
 * unless its settings changed.
 */
public class FileStores implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FileStores.class);
    private static final FileStores NONE = new FileStores(Map.of());

    private record Key(String name, GatewayProperties.FileStorage config) {}

    private final Map<String, FileStore> configured;
    private final Map<Key, FileStore> built = new ConcurrentHashMap<>();

    public FileStores(Map<String, FileStore> configured) {
        this.configured = Map.copyOf(configured);
    }

    public static FileStores none() {
        return NONE;
    }

    /** @throws IllegalArgumentException for a storage that is configured wrongly (unknown type, no base-dir) */
    public static FileStores create(Map<String, GatewayProperties.FileStorage> config) {
        Map<String, FileStore> stores = new TreeMap<>();
        config.forEach((name, c) -> {
            if (isS3(c) && (c.bucket() == null || c.bucket().isBlank())) {
                log.info("gateway.storages.{}: no bucket set, storage not available", name);
                return;
            }
            stores.put(name, newStore("gateway.storages." + name, name, c));
        });
        stores.values().forEach(s -> log.info("file storage {} ({}) at {}", s.name(), s.type(), s.location()));
        return new FileStores(stores);
    }

    /**
     * A store for settings from the database (placeholders already resolved); the same name and settings give the
     * same store.
     *
     * @throws IllegalArgumentException when the settings are incomplete (no base dir, no bucket, unknown type)
     */
    public FileStore build(String name, GatewayProperties.FileStorage config) {
        Key key = new Key(name, config);
        FileStore existing = built.get(key);
        if (existing != null) {
            return existing;
        }
        if (isS3(config) && (config.bucket() == null || config.bucket().isBlank())) {
            throw new IllegalArgumentException("bucket is required for an S3 storage");
        }
        FileStore store = newStore("storage '" + name + "'", name, config);
        FileStore raced = built.putIfAbsent(key, store);
        if (raced != null) {
            store.close();
            return raced;
        }
        log.info("file storage {} ({}) at {} (database)", store.name(), store.type(), store.location());
        return store;
    }

    private static boolean isS3(GatewayProperties.FileStorage c) {
        return "s3".equals(type(c));
    }

    private static String type(GatewayProperties.FileStorage c) {
        return c.type() == null || c.type().isBlank() ? "local" : c.type().strip().toLowerCase(Locale.ROOT);
    }

    private static FileStore newStore(String where, String name, GatewayProperties.FileStorage c) {
        long max = c.maxSize() == null ? 0 : c.maxSize().toBytes();
        return switch (type(c)) {
            case "local" -> {
                if (c.baseDir() == null || c.baseDir().isBlank()) {
                    throw new IllegalArgumentException(where + ": base-dir is required for a local storage");
                }
                yield new LocalFileStore(name, Path.of(c.baseDir().strip()), c.allowedTypes(), max);
            }
            case "s3" -> new S3FileStore(name, c, max);
            default -> throw new IllegalArgumentException(where + ": type must be local or s3, not '" + c.type() + "'");
        };
    }

    /** A new, uncached store (for a connection check of unsaved settings); the caller closes it. */
    public static FileStore temporary(String name, GatewayProperties.FileStorage config) {
        if (isS3(config) && (config.bucket() == null || config.bucket().isBlank())) {
            throw new IllegalArgumentException("bucket is required for an S3 storage");
        }
        return newStore("storage '" + name + "'", name, config);
    }

    /** A storage of application.yml; null when there is none of that name. */
    public FileStore find(String name) {
        return name == null ? null : configured.get(name);
    }

    /** The storages of application.yml by name. */
    public Map<String, FileStore> configured() {
        return configured;
    }

    /** For Studio: name, type, location, limits of the application.yml storages; never credentials. */
    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        new TreeMap<>(configured).values().forEach(s -> out.add(describe(s)));
        return out;
    }

    public static Map<String, Object> describe(FileStore s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.name());
        m.put("type", s.type());
        m.put("location", s.location());
        m.put("allowedTypes", s.allowedTypes());
        m.put("maxSize", s.maxSize());
        return m;
    }

    @Override
    public void close() {
        configured.values().forEach(FileStore::close);
        built.values().forEach(FileStore::close);
    }
}
