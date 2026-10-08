package com.mhamzah.gateway.config.entity;

import com.mhamzah.gateway.config.ConfigRows.TargetHeaderRow;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code gw_target_system_header}; the physical table name comes from
 * {@code gateway.db.tables.target-system-header}. Read-only.
 */
@Entity
@Table(name = "target_system_header")
public class TargetSystemHeaderEntity {

    @Id
    private Long id;
    private String targetCode;
    private String headerName;
    private String headerValue;

    protected TargetSystemHeaderEntity() {}

    public TargetHeaderRow toRow() {
        return new TargetHeaderRow(id, targetCode, headerName, headerValue);
    }
}
