package com.mhamzah.gateway.admin;

import com.mhamzah.gateway.config.ConfigValidationException;
import com.mhamzah.gateway.config.FlowRegistry;
import com.mhamzah.gateway.config.FlowRegistryHolder;
import com.mhamzah.gateway.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /admin/config/reload} (spec Section 11), protected by the {@code X-Admin-Token} header. */
@RestController
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final FlowRegistryHolder holder;
    private final byte[] token;

    public AdminController(FlowRegistryHolder holder, GatewayProperties properties) {
        this.holder = holder;
        String configured = properties.admin().token();
        this.token = configured == null || configured.isBlank() ? null : configured.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/admin/config/reload")
    public ResponseEntity<Map<String, Object>> reload(
            @RequestHeader(name = "X-Admin-Token", required = false) String supplied) {
        if (!authorized(supplied)) {
            return ResponseEntity.status(401).body(Map.of("errors", List.of("unauthorized")));
        }
        try {
            FlowRegistry registry = holder.reload();
            return ResponseEntity.ok(Map.of("flows", registry.flows().size(), "loadedAt", registry.loadedAt().toString()));
        } catch (ConfigValidationException e) {
            log.warn("Config reload rejected; keeping current configuration. {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().body(Map.of("errors", e.errors()));
        } catch (DataAccessException | TransactionException e) {
            log.error("Config reload failed: database unavailable; keeping current configuration", e);
            return ResponseEntity.status(503).body(Map.of("errors", List.of("database unavailable")));
        }
    }

    private boolean authorized(String supplied) {
        return token != null && supplied != null
                && MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8));
    }
}
