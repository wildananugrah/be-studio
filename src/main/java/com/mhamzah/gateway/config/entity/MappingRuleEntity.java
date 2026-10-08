package com.mhamzah.gateway.config.entity;

import com.mhamzah.gateway.config.ConfigRows.RuleRow;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** {@code gw_mapping_rule}; the physical table name comes from {@code gateway.db.tables.mapping-rule}. Read-only. */
@Entity
@Table(name = "mapping_rule")
public class MappingRuleEntity {

    @Id
    private Long id;
    private Long flowId;
    private Long stepId;
    private String phase;
    private int seq;
    private String targetType;
    private String targetPath;
    private String sourcePath;
    private String constantValue;
    private String defaultValue;
    private String converter;
    private String lookupCode;
    private String fieldHandler;
    private boolean required;

    protected MappingRuleEntity() {}

    public RuleRow toRow() {
        return new RuleRow(id, flowId, stepId, phase, seq, targetType, targetPath, sourcePath, constantValue,
                defaultValue, converter, lookupCode, fieldHandler, required);
    }
}
