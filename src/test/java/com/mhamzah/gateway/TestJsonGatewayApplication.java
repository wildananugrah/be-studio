package com.mhamzah.gateway;

import org.springframework.boot.SpringApplication;

/**
 * Local dev launcher: PostgreSQL in Docker, WireMock-stubbed downstreams, profile {@code dev} (demo flows).
 * Run with {@code ./mvnw spring-boot:test-run}, then e.g. {@code curl localhost:8080/api/v1/accounts/1001}.
 */
public class TestJsonGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.from(JsonGatewayApplication::main)
                .with(TestcontainersConfiguration.class, WireMockConfiguration.class)
                .withAdditionalProfiles("dev")
                .run(args);
    }
}
