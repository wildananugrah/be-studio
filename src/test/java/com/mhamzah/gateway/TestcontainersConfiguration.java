package com.mhamzah.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Database for integration tests and the dev launcher: PostgreSQL by default, Oracle Free with
 * {@code -Dit.db=oracle} (Maven profile {@code oracle-it}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Schema created in every test database for the custom-names test (PostgreSQL only; Oracle uses the app user). */
    public static final String CUSTOM_SCHEMA = "gwcustom";

    @Bean
    @ServiceConnection
    @ConditionalOnProperty(name = "it.db", havingValue = "postgres", matchIfMissing = true)
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                .withInitScript("db/init-postgres.sql");
    }

    @Bean
    @ServiceConnection
    @ConditionalOnProperty(name = "it.db", havingValue = "oracle")
    OracleContainer oracleFreeContainer() {
        return new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"));
    }

    public static boolean isOracle() {
        return "oracle".equals(System.getProperty("it.db"));
    }
}
