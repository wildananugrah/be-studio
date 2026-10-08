package com.mhamzah.gateway;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Invalid {@code gateway.db.*} names stop the application before anything touches the database
 * (the datasource URL points nowhere, so reaching the DB would fail with a different error).
 */
class StartupValidationTest {

    private static void start(String... args) {
        new SpringApplicationBuilder(JsonGatewayApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }

    @Test
    void invalidTableNameFailsStartup() {
        assertThatThrownBy(() -> start(
                "--gateway.db.tables.flow=gw_flow; DROP TABLE x",
                "--spring.datasource.url=jdbc:postgresql://localhost:1/unreachable"))
                .hasStackTraceContaining("gateway.db.tables.flow")
                .hasStackTraceContaining("must start with a letter");
    }

    @Test
    void tooLongTableNameFailsStartup() {
        assertThatThrownBy(() -> start(
                "--gateway.db.tables.audit-step=" + "a".repeat(26),
                "--spring.datasource.url=jdbc:postgresql://localhost:1/unreachable"))
                .hasStackTraceContaining("at most 25 characters");
    }
}
