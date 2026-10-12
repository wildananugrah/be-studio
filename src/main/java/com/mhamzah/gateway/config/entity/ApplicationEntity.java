package com.mhamzah.gateway.config.entity;

import com.mhamzah.gateway.config.ConfigRows.ApplicationRow;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** {@code gw_application}; the physical table name comes from {@code gateway.db.tables.application}. Read-only. */
@Entity
@Table(name = "application")
public class ApplicationEntity {

    @Id
    private Long id;
    private String code;
    private String name;
    private String description;

    protected ApplicationEntity() {}

    public ApplicationRow toRow() {
        return new ApplicationRow(id, code, name, description);
    }
}
