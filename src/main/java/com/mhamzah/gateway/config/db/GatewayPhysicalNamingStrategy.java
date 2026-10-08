package com.mhamzah.gateway.config.db;

import com.mhamzah.gateway.config.GatewayProperties;
import java.util.Map;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;

/**
 * Maps the logical table names used in {@code @Table} (e.g. {@code flow_step}) to the configured
 * {@code gateway.db.tables.*} names. Columns keep Spring Boot's default camelCase-to-snake_case mapping.
 */
public class GatewayPhysicalNamingStrategy extends CamelCaseToUnderscoresNamingStrategy {

    private final Map<String, String> tables;

    public GatewayPhysicalNamingStrategy(GatewayProperties.Tables tables) {
        this.tables = tables.byLogicalName();
    }

    @Override
    public Identifier toPhysicalTableName(Identifier logicalName, JdbcEnvironment env) {
        String configured = logicalName == null ? null : tables.get(logicalName.getText());
        return configured != null ? Identifier.toIdentifier(configured) : super.toPhysicalTableName(logicalName, env);
    }
}
