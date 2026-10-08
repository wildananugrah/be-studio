package com.mhamzah.gateway.config.entity;

import com.mhamzah.gateway.config.ConfigRows.FlowRow;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** {@code gw_flow}; the physical table name comes from {@code gateway.db.tables.flow}. Read-only. */
@Entity
@Table(name = "flow")
public class FlowEntity {

    @Id
    private Long id;
    private String code;
    private String name;
    private String httpMethod;
    private String pathPattern;
    private String requestSchemaCode;
    private String responseSchemaCode;
    private String requestHandler;
    private String responseHandler;
    private String errorHandler;
    private Integer successStatus;
    private Integer timeoutMs;
    private String auditMode;
    private boolean enabled;

    protected FlowEntity() {}

    public FlowRow toRow() {
        return new FlowRow(id, code, name, httpMethod, pathPattern, requestSchemaCode, responseSchemaCode,
                requestHandler, responseHandler, errorHandler, successStatus, timeoutMs, auditMode, enabled);
    }
}
