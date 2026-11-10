package com.mhamzah.gateway.config.entity;

import com.mhamzah.gateway.config.ConfigRows.ErrorMapRow;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code gw_error_map}; the physical table name comes from {@code gateway.db.tables.error-map}. Read-only.
 * {@code entries} is a CLOB (Oracle) / text (PostgreSQL), read with {@code getString} like {@code schema_text}.
 */
@Entity
@Table(name = "error_map")
public class ErrorMapEntity {

    @Id
    private Long id;
    private String code;
    private String description;
    private String entries;

    protected ErrorMapEntity() {}

    public ErrorMapRow toRow() {
        return new ErrorMapRow(id, code, description, entries);
    }
}
