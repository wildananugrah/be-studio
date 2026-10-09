package com.mhamzah.gateway.studio;

import com.mhamzah.gateway.config.ConfigValidationException;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.logging.SkipBodyLogging;
import com.mhamzah.gateway.mapping.ConversionException;
import com.mhamzah.gateway.mapping.Converters;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * API behind Gateway Studio ({@code /studio}), only registered when {@code gateway.studio.enabled=true}. Every
 * call needs the {@code X-Admin-Token} header, like {@code POST /admin/config/reload}.
 *
 * <pre>
 * GET  /studio/api/config    the stored configuration plus a version
 * GET  /studio/api/catalog   handler/codec bean names, schema codes, converters, application.yml targets
 * POST /studio/api/validate  {config}                          -> {errors: [...]}
 * POST /studio/api/preview   {config, flow, step, sample}       -> per-rule results and the mapped message
 * PUT  /studio/api/config    {baseVersion, config}             -> 200 saved and reloaded | 409 | 422
 *                                                              | 403 when gateway.studio.mode=view-only
 * </pre>
 */
@RestController
@SkipBodyLogging
@RequestMapping("/studio/api")
@ConditionalOnBooleanProperty("gateway.studio.enabled")
public class StudioController {

    private static final Logger log = LoggerFactory.getLogger(StudioController.class);
    private static final ResponseEntity<Object> UNAUTHORIZED =
            ResponseEntity.status(401).body(Map.of("errors", List.of("unauthorized")));
    static final ResponseEntity<Object> VIEW_ONLY = ResponseEntity.status(403).body(Map.of("errors",
            List.of("Gateway Studio is view-only (gateway.studio.mode=view-only): changes cannot be saved here")));

    private final StudioService studio;
    private final byte[] token;
    private final boolean viewOnly;

    public StudioController(StudioService studio, GatewayProperties properties) {
        this.studio = studio;
        this.viewOnly = properties.studio().viewOnly();
        String configured = properties.admin().token();
        this.token = configured == null || configured.isBlank() ? null : configured.getBytes(StandardCharsets.UTF_8);
    }

    public record SaveRequest(String baseVersion, StudioConfig config) {}

    public record PreviewRequest(StudioConfig config, String flow, String step, JsonNode sample) {}

    /** A converter spec ({@code DATE_FORMAT:ddMMyyyy:yyyy-MM-dd}) and a value to run it on. */
    public record ConverterTry(String spec, JsonNode value) {}

    @GetMapping("/config")
    public ResponseEntity<Object> config(@RequestHeader(name = "X-Admin-Token", required = false) String supplied) {
        return authorized(supplied) ? ResponseEntity.ok(studio.load()) : UNAUTHORIZED;
    }

    @GetMapping("/catalog")
    public ResponseEntity<Object> catalog(@RequestHeader(name = "X-Admin-Token", required = false) String supplied) {
        return authorized(supplied) ? ResponseEntity.ok(studio.catalog()) : UNAUTHORIZED;
    }

    @PostMapping("/validate")
    public ResponseEntity<Object> validate(@RequestHeader(name = "X-Admin-Token", required = false) String supplied,
            @RequestBody StudioConfig config) {
        return authorized(supplied) ? ResponseEntity.ok(Map.of("errors", studio.validate(config))) : UNAUTHORIZED;
    }

    /**
     * Runs one converter on one value with the real {@link Converters}, for Studio's converter editor:
     * {@code {result}} or {@code {error}} (an invalid spec or a value it cannot convert). Changes nothing.
     */
    @PostMapping("/converters/try")
    public ResponseEntity<Object> tryConverter(@RequestHeader(name = "X-Admin-Token", required = false) String supplied,
            @RequestBody ConverterTry request) {
        if (!authorized(supplied)) {
            return UNAUTHORIZED;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            JsonNode value = request.value() == null ? JsonNodeFactory.instance.nullNode() : request.value();
            out.put("result", Converters.parse(request.spec()).apply(value));
        } catch (IllegalArgumentException | ConversionException e) {
            out.put("error", e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /** Tries a storage's settings ({@code {ok, location, message}}); writes nothing, also in view-only mode. */
    @PostMapping("/storages/check")
    public ResponseEntity<Object> checkStorage(@RequestHeader(name = "X-Admin-Token", required = false) String supplied,
            @RequestBody StudioConfig.Storage storage) {
        return authorized(supplied) ? ResponseEntity.ok(studio.checkStorage(storage)) : UNAUTHORIZED;
    }

    @PostMapping("/preview")
    public ResponseEntity<Object> preview(@RequestHeader(name = "X-Admin-Token", required = false) String supplied,
            @RequestBody PreviewRequest request) {
        if (!authorized(supplied)) {
            return UNAUTHORIZED;
        }
        return ResponseEntity.ok(studio.preview(request.config(), request.flow(), request.step(), request.sample()));
    }

    @PutMapping("/config")
    public ResponseEntity<Object> save(@RequestHeader(name = "X-Admin-Token", required = false) String supplied,
            @RequestBody SaveRequest request) {
        if (!authorized(supplied)) {
            return UNAUTHORIZED;
        }
        if (viewOnly) {
            return VIEW_ONLY;
        }
        try {
            return switch (studio.save(request.config(), request.baseVersion())) {
                case StudioService.SaveResult.Saved s -> {
                    log.info("Gateway Studio saved the configuration; {} flow(s) loaded", s.registry().flows().size());
                    yield ResponseEntity.ok(Map.of("flows", s.registry().flows().size(),
                            "loadedAt", s.registry().loadedAt().toString(), "version", s.version()));
                }
                case StudioService.SaveResult.Invalid i -> ResponseEntity.unprocessableEntity()
                        .body(Map.of("errors", i.errors()));
                case StudioService.SaveResult.Conflict c -> ResponseEntity.status(409).body(Map.of(
                        "errors", List.of("the configuration was changed by someone else since you loaded it"),
                        "version", c.currentVersion()));
            };
        } catch (ConfigValidationException e) {
            // saved, but the reload disagreed (e.g. an environment variable changed); the old snapshot stays live
            log.warn("Gateway Studio saved the configuration but the reload was rejected. {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().body(Map.of("errors", e.errors(), "saved", true));
        } catch (DataAccessException | TransactionException e) {
            log.error("Gateway Studio could not save the configuration", e);
            return ResponseEntity.status(503).body(Map.of("errors",
                    List.of("database error, nothing was saved: " + e.getMostSpecificCause().getMessage())));
        }
    }

    private boolean authorized(String supplied) {
        return token != null && supplied != null
                && MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8));
    }
}
