package com.mhamzah.gateway.config.entity;

import com.mhamzah.gateway.config.ConfigRows.LookupRow;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** {@code gw_lookup_entry}; the physical table name comes from {@code gateway.db.tables.lookup-entry}. Read-only. */
@Entity
@Table(name = "lookup_entry")
public class LookupEntryEntity {

    @Id
    private Long id;
    private String lookupCode;
    private String sourceValue;
    private String targetValue;

    protected LookupEntryEntity() {}

    public LookupRow toRow() {
        return new LookupRow(id, lookupCode, sourceValue, targetValue);
    }
}
