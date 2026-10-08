package com.mhamzah.gateway;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Stubs the demo downstream systems (CORE_BANKING, NOTIFICATION) with WireMock, using the mappings under
 * {@code src/test/resources/wiremock/mappings}, and points the target systems at it through the
 * placeholders in their gw_target_system rows.
 */
@TestConfiguration(proxyBeanMethods = false)
public class WireMockConfiguration {

    @Bean(destroyMethod = "stop")
    WireMockServer wireMockServer() {
        WireMockServer server = new WireMockServer(options().dynamicPort().usingFilesUnderClasspath("wiremock"));
        server.start();
        return server;
    }

    /** The dev seed's gw_target_system rows use ${CORE_BANKING_URL:...} / ${NOTIFICATION_URL:...} as base_url. */
    @Bean
    DynamicPropertyRegistrar wireMockTargets(WireMockServer server) {
        return registry -> {
            registry.add("CORE_BANKING_URL", server::baseUrl);
            registry.add("NOTIFICATION_URL", server::baseUrl);
        };
    }
}
