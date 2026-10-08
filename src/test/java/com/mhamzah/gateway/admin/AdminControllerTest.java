package com.mhamzah.gateway.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.config.ConfigValidationException;
import com.mhamzah.gateway.config.FlowRegistry;
import com.mhamzah.gateway.config.FlowRegistryHolder;
import com.mhamzah.gateway.config.GatewayProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

class AdminControllerTest {

    private static GatewayProperties properties(String token) {
        Map<String, String> map = token == null ? Map.of() : Map.of("gateway.admin.token", token);
        return new Binder(new MapConfigurationPropertySource(map))
                .bindOrCreate("gateway", Bindable.of(GatewayProperties.class));
    }

    private static FlowRegistryHolder holder(Supplier<FlowRegistry> reload) {
        return new FlowRegistryHolder(null, null) {
            @Override
            public synchronized FlowRegistry reload() {
                return reload.get();
            }
        };
    }

    private static final FlowRegistry EMPTY = new FlowRegistry(List.of(), Map.of(), Map.of(), Instant.parse("2026-10-08T00:00:00Z"));

    @Test
    void validTokenReloads() {
        var r = new AdminController(holder(() -> EMPTY), properties("secret")).reload("secret");
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).containsEntry("flows", 0).containsEntry("loadedAt", "2026-10-08T00:00:00Z");
    }

    @Test
    void missingOrWrongTokenIs401() {
        var controller = new AdminController(holder(() -> EMPTY), properties("secret"));
        assertThat(controller.reload(null).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.reload("nope").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void unconfiguredTokenRejectsEverything() {
        var controller = new AdminController(holder(() -> EMPTY), properties(null));
        assertThat(controller.reload("").getStatusCode().value()).isEqualTo(401);
        assertThat(controller.reload(null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void invalidConfigIs422WithErrors() {
        var controller = new AdminController(holder(() -> {
            throw new ConfigValidationException(List.of("flow 'A': bad"));
        }), properties("s"));
        var r = controller.reload("s");
        assertThat(r.getStatusCode().value()).isEqualTo(422);
        assertThat(r.getBody()).containsEntry("errors", List.of("flow 'A': bad"));
    }

    @Test
    void databaseUnavailableIs503() {
        var dataAccess = new AdminController(holder(() -> {
            throw new DataAccessResourceFailureException("down");
        }), properties("s"));
        assertThat(dataAccess.reload("s").getStatusCode().value()).isEqualTo(503);

        var noTransaction = new AdminController(holder(() -> {
            throw new CannotCreateTransactionException("no connection");
        }), properties("s"));
        assertThat(noTransaction.reload("s").getStatusCode().value()).isEqualTo(503);
    }
}
