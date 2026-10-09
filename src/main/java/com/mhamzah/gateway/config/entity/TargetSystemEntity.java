package com.mhamzah.gateway.config.entity;

import com.mhamzah.gateway.config.ConfigRows.TargetRow;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** {@code gw_target_system}; the physical table name comes from {@code gateway.db.tables.target-system}. Read-only. */
@Entity
@Table(name = "target_system")
public class TargetSystemEntity {

    @Id
    private Long id;
    private String code;
    private String baseUrl;
    private Integer connectTimeoutMs;
    private Integer readTimeoutMs;
    private String bodyCodec;
    private boolean enabled;
    private String tlsMode;
    private String tlsTrustStore;
    private String tlsTrustStorePassword;
    private String tlsKeyStore;
    private String tlsKeyStorePassword;

    protected TargetSystemEntity() {}

    public TargetRow toRow() {
        return new TargetRow(id, code, baseUrl, connectTimeoutMs, readTimeoutMs, bodyCodec, enabled, tlsMode,
                tlsTrustStore, tlsTrustStorePassword, tlsKeyStore, tlsKeyStorePassword);
    }
}
