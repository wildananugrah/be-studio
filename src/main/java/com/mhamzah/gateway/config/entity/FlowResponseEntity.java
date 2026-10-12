package com.mhamzah.gateway.config.entity;

import com.mhamzah.gateway.config.ConfigRows.ResponseRow;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** {@code gw_flow_response}; the physical table name comes from {@code gateway.db.tables.flow-response}. Read-only. */
@Entity
@Table(name = "flow_response")
public class FlowResponseEntity {

    @Id
    private Long id;
    private Long flowId;
    private String name;
    private Integer statusCode;
    private String description;

    protected FlowResponseEntity() {}

    public ResponseRow toRow() {
        return new ResponseRow(id, flowId, name, statusCode, description);
    }
}
