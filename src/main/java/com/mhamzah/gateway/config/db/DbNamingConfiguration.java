package com.mhamzah.gateway.config.db;

import com.mhamzah.gateway.config.GatewayProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import liquibase.integration.spring.SpringLiquibase;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Applies {@code gateway.db.schema} and {@code gateway.db.tables.*} to Hibernate and Liquibase (spec Section 4.2).
 * The JDBC audit writer applies them itself. Names are validated before anything touches the database.
 */
@Configuration(proxyBeanMethods = false)
public class DbNamingConfiguration {

    @Bean
    PhysicalNamingStrategy gatewayPhysicalNamingStrategy(GatewayProperties properties) {
        return new GatewayPhysicalNamingStrategy(properties.db().tables());
    }

    @Bean
    HibernatePropertiesCustomizer gatewaySchemaCustomizer(GatewayProperties properties) {
        return hibernate -> {
            if (properties.db().hasSchema()) {
                hibernate.put("hibernate.default_schema", properties.db().schema());
            }
        };
    }

    /** Static so it is registered before Liquibase runs; binds the properties itself for the same reason. */
    @Bean
    static BeanPostProcessor gatewayLiquibaseNaming(Environment environment) {
        GatewayProperties.Db db = Binder.get(environment)
                .bindOrCreate("gateway", Bindable.of(GatewayProperties.class)).db();
        List<String> errors = DbNames.validate(db);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid gateway.db configuration:\n  - " + String.join("\n  - ", errors));
        }
        // keep any user-supplied spring.liquibase.parameters alongside the table names
        Map<String, String> userParameters = Binder.get(environment)
                .bind("spring.liquibase.parameters", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof SpringLiquibase liquibase) {
                    if (db.hasSchema()) {
                        liquibase.setDefaultSchema(db.schema());
                        liquibase.setLiquibaseSchema(db.schema());
                    }
                    liquibase.setDatabaseChangeLogTable(db.liquibaseTables().changelog());
                    liquibase.setDatabaseChangeLogLockTable(db.liquibaseTables().changelogLock());
                    Map<String, String> params = new HashMap<>(userParameters);
                    db.tables().byLogicalName().forEach((logical, table) -> params.put("tbl." + logical, table));
                    liquibase.setChangeLogParameters(params);
                }
                return bean;
            }
        };
    }
}
