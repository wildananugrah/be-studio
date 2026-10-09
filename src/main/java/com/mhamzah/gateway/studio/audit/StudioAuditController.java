package com.mhamzah.gateway.studio.audit;

import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.logging.SkipBodyLogging;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Studio's audit trail, read-only (also in view-only mode); needs {@code X-Admin-Token}.
 *
 * <pre>
 * GET /studio/api/audit/status                 {auditEnabled, storePayloads, zone}
 * GET /studio/api/audit?flow=&amp;status=2xx|4xx|5xx|errors&amp;q=&amp;from=&amp;to=&amp;page=1&amp;size=50
 *                                              newest first; q = correlation ID, or part of the path / error code
 * GET /studio/api/audit/{correlationId}        transaction + steps (with payloads) + buffered log lines
 * </pre>
 */
@RestController
@SkipBodyLogging
@RequestMapping("/studio/api/audit")
@ConditionalOnBooleanProperty("gateway.studio.enabled")
public class StudioAuditController {

    private static final ResponseEntity<Object> UNAUTHORIZED =
            ResponseEntity.status(401).body(Map.of("errors", List.of("unauthorized")));

    private final AuditQueries audit;
    private final byte[] token;

    public StudioAuditController(AuditQueries audit, GatewayProperties properties) {
        this.audit = audit;
        String configured = properties.admin().token();
        this.token = configured == null || configured.isBlank() ? null : configured.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/status")
    public ResponseEntity<Object> status(@RequestHeader(name = "X-Admin-Token", required = false) String supplied) {
        return authorized(supplied) ? ResponseEntity.ok(audit.status()) : UNAUTHORIZED;
    }

    @GetMapping
    public ResponseEntity<Object> list(@RequestHeader(name = "X-Admin-Token", required = false) String supplied,
            @RequestParam(required = false) String flow, @RequestParam(required = false) String status,
            @RequestParam(required = false) String q, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (!authorized(supplied)) {
            return UNAUTHORIZED;
        }
        try {
            AuditQueries.Filter filter = new AuditQueries.Filter(blank(flow), blank(status), blank(q), time(from), time(to));
            return ResponseEntity.ok(audit.list(filter, Math.max(1, page), Math.min(200, Math.max(1, size))));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("errors", List.of(e.getMessage())));
        }
    }

    @GetMapping("/{correlationId}")
    public ResponseEntity<Object> detail(@RequestHeader(name = "X-Admin-Token", required = false) String supplied,
            @PathVariable String correlationId) {
        if (!authorized(supplied)) {
            return UNAUTHORIZED;
        }
        Map<String, Object> detail = audit.detail(correlationId);
        return detail == null
                ? ResponseEntity.status(404).body(Map.of("errors", List.of("no audit row for correlation ID '" + correlationId + "'")))
                : ResponseEntity.ok(detail);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** {@code yyyy-MM-ddTHH:mm[:ss]} in the server's time zone (what a datetime-local input sends). */
    private static LocalDateTime time(String s) {
        return blank(s) == null ? null : LocalDateTime.parse(s.strip());
    }

    private boolean authorized(String supplied) {
        return token != null && supplied != null
                && MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8));
    }
}
