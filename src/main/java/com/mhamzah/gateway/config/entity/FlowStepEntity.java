package com.mhamzah.gateway.config.entity;

import com.mhamzah.gateway.config.ConfigRows.StepRow;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** {@code gw_flow_step}; the physical table name comes from {@code gateway.db.tables.flow-step}. Read-only. */
@Entity
@Table(name = "flow_step")
public class FlowStepEntity {

    @Id
    private Long id;
    private Long flowId;
    private String name;
    private int stepOrder;
    private String targetSystem;
    private String httpMethod;
    private String pathTemplate;
    private String conditionExpr;
    private String successExpr;
    private String onFailure;
    private Integer timeoutMs;
    private String responseSchemaCode;
    private String requestHandler;
    private String responseHandler;
    private String bodyCodec;
    private boolean enabled;
    /** CLOB (Oracle) / text (PostgreSQL), read with {@code getString} like {@code schema_text}. */
    private String sqlText;

    protected FlowStepEntity() {}

    public StepRow toRow() {
        return new StepRow(id, flowId, name, stepOrder, targetSystem, httpMethod, pathTemplate, conditionExpr,
                successExpr, onFailure, timeoutMs, responseSchemaCode, requestHandler, responseHandler,
                bodyCodec, enabled, sqlText);
    }
}
