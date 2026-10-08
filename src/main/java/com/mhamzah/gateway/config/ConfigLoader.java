package com.mhamzah.gateway.config;

import com.mhamzah.gateway.config.entity.FlowEntity;
import com.mhamzah.gateway.config.entity.FlowStepEntity;
import com.mhamzah.gateway.config.entity.JsonSchemaEntity;
import com.mhamzah.gateway.config.entity.LookupEntryEntity;
import com.mhamzah.gateway.config.entity.MappingRuleEntity;
import com.mhamzah.gateway.config.entity.TargetSystemEntity;
import com.mhamzah.gateway.config.entity.TargetSystemHeaderEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reads every config table in one read-only transaction. */
@Component
public class ConfigLoader {

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public ConfigRows load() {
        return new ConfigRows(
                all(FlowEntity.class, FlowEntity::toRow),
                all(FlowStepEntity.class, FlowStepEntity::toRow),
                all(MappingRuleEntity.class, MappingRuleEntity::toRow),
                all(LookupEntryEntity.class, LookupEntryEntity::toRow),
                all(JsonSchemaEntity.class, JsonSchemaEntity::toRow),
                all(TargetSystemEntity.class, TargetSystemEntity::toRow),
                all(TargetSystemHeaderEntity.class, TargetSystemHeaderEntity::toRow));
    }

    private <E, R> List<R> all(Class<E> type, Function<E, R> toRow) {
        return em.createQuery("select e from " + type.getSimpleName() + " e", type)
                .getResultList().stream().map(toRow).toList();
    }
}
