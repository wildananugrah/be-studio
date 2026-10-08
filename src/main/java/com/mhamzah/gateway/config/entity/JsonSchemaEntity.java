package com.mhamzah.gateway.config.entity;

import com.mhamzah.gateway.config.ConfigRows.SchemaRow;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code gw_json_schema}; the physical table name comes from {@code gateway.db.tables.json-schema}. Read-only.
 * {@code schemaText} is a CLOB (Oracle) / text (PostgreSQL); both drivers read it with {@code getString}.
 */
@Entity
@Table(name = "json_schema")
public class JsonSchemaEntity {

    @Id
    private Long id;
    private String code;
    private String schemaText;

    protected JsonSchemaEntity() {}

    public SchemaRow toRow() {
        return new SchemaRow(id, code, schemaText);
    }
}
