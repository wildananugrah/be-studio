package com.mhamzah.gateway.config.entity;

import com.mhamzah.gateway.config.ConfigRows.StorageRow;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** {@code gw_storage}; the physical table name comes from {@code gateway.db.tables.storage}. Read-only. */
@Entity
@Table(name = "storage")
public class StorageEntity {

    @Id
    private Long id;
    private String code;
    private String storageType;
    private String baseDir;
    private String bucket;
    private String keyPrefix;
    private String region;
    private String endpoint;
    private boolean pathStyle;
    private String accessKey;
    private String secretKey;
    private String allowedTypes;
    private String maxSize;
    private boolean enabled;

    protected StorageEntity() {}

    public StorageRow toRow() {
        return new StorageRow(id, code, storageType, baseDir, bucket, keyPrefix, region, endpoint, pathStyle, accessKey,
                secretKey, allowedTypes, maxSize, enabled);
    }
}
