package com.mhamzah.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/** {@code gateway.masking.fields} in application.yml: built-in defaults, overridable by environment variable. */
class MaskingPropertiesTest {

    private static GatewayProperties.Masking bind(Map<String, Object> env) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("env", env));
        return Binder.get(environment).bind("gateway.masking", GatewayProperties.Masking.class).get();
    }

    @Test
    void defaultsApplyWithoutEnvironmentVariable() throws IOException {
        assertThat(bind(Map.of()).fields()).containsExactly("pin", "password", "cardNo", "cvv", "authorization", "x-admin-token");
    }

    @Test
    void environmentVariableReplacesTheList() throws IOException {
        assertThat(bind(Map.of("GATEWAY_MASKING_FIELDS", "pin,nik,motherMaidenName")).fields())
                .containsExactly("pin", "nik", "motherMaidenName");
    }
}
