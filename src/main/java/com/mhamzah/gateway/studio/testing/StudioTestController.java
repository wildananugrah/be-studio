package com.mhamzah.gateway.studio.testing;

import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.logging.SkipBodyLogging;
import com.mhamzah.gateway.studio.testing.TestModel.TestCase;
import com.mhamzah.gateway.studio.testing.TestModel.TestRun;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unit test runs in Gateway Studio, only when {@code gateway.studio.enabled=true}; every call needs
 * {@code X-Admin-Token}.
 *
 * <pre>
 * GET  /studio/api/tests/{flow}/cases          cases generated from the flow's operation in the API description
 * POST /studio/api/tests/{flow}/runs  {cases}  runs them against this gateway and returns the evidence (403 view-only)
 * GET  /studio/api/tests/runs/{id}/report?format=md|docx|pdf&amp;audit=true&amp;logs=true   the unit test document
 * GET  /studio/api/tests/spec?format=md|docx|pdf[&amp;flow=CODE]   the API specification of one flow or of all flows
 * </pre>
 */
@RestController
@SkipBodyLogging
@RequestMapping("/studio/api/tests")
@ConditionalOnBooleanProperty("gateway.studio.enabled")
public class StudioTestController {

    private static final ResponseEntity<Object> UNAUTHORIZED =
            ResponseEntity.status(401).body(Map.of("errors", List.of("unauthorized")));

    private final TestRunner runner;
    private final byte[] token;
    private final boolean viewOnly;

    public StudioTestController(TestRunner runner, GatewayProperties properties) {
        this.runner = runner;
        this.viewOnly = properties.studio().viewOnly();
        String configured = properties.admin().token();
        this.token = configured == null || configured.isBlank() ? null : configured.getBytes(StandardCharsets.UTF_8);
    }

    public record RunRequest(List<TestCase> cases) {}

    @GetMapping("/{flow}/cases")
    public ResponseEntity<Object> cases(@RequestHeader(name = "X-Admin-Token", required = false) String supplied,
            @PathVariable String flow) {
        if (!authorized(supplied)) {
            return UNAUTHORIZED;
        }
        try {
            return ResponseEntity.ok(Map.of("cases", runner.generate(flow)));
        } catch (TestRunner.NotLiveException | IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("errors", List.of(e.getMessage())));
        }
    }

    @PostMapping("/{flow}/runs")
    public ResponseEntity<Object> run(@RequestHeader(name = "X-Admin-Token", required = false) String supplied,
            @PathVariable String flow, @RequestBody RunRequest request) {
        if (!authorized(supplied)) {
            return UNAUTHORIZED;
        }
        if (viewOnly) {
            // a run sends real requests to the downstream systems: not for a view-only Studio
            return ResponseEntity.status(403).body(Map.of("errors", List.of(
                    "Gateway Studio is view-only (gateway.studio.mode=view-only): running tests is disabled")));
        }
        if (request.cases() == null || request.cases().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("errors", List.of("no test cases")));
        }
        try {
            return ResponseEntity.ok(runner.run(flow, request.cases()));
        } catch (TestRunner.NotLiveException e) {
            return ResponseEntity.status(409).body(Map.of("errors", List.of(e.getMessage())));
        }
    }

    @GetMapping("/runs/{id}/report")
    public ResponseEntity<Object> report(@RequestHeader(name = "X-Admin-Token", required = false) String supplied,
            @PathVariable String id, @RequestParam(defaultValue = "md") String format,
            @RequestParam(defaultValue = "true") boolean audit, @RequestParam(defaultValue = "true") boolean logs) {
        if (!authorized(supplied)) {
            return UNAUTHORIZED;
        }
        ReportRenderers.Format f;
        try {
            f = ReportRenderers.Format.valueOf(format.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("errors", List.of("format must be md, docx or pdf")));
        }
        TestRun run = runner.find(id);
        if (run == null) {
            return ResponseEntity.status(404).body(Map.of("errors", List.of("unknown or expired run '" + id + "'")));
        }
        byte[] bytes = ReportRenderers.render(TestReport.build(run, new TestReport.Options(audit, logs)), f);
        String name = "UT_" + run.flowCode() + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                + "." + f.extension;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, f.contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(bytes);
    }

    @GetMapping("/spec")
    public ResponseEntity<Object> spec(@RequestHeader(name = "X-Admin-Token", required = false) String supplied,
            @RequestParam(required = false) String flow, @RequestParam(defaultValue = "md") String format,
            jakarta.servlet.http.HttpServletRequest request) {
        if (!authorized(supplied)) {
            return UNAUTHORIZED;
        }
        ReportRenderers.Format f;
        try {
            f = ReportRenderers.Format.valueOf(format.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("errors", List.of("format must be md, docx or pdf")));
        }
        String code = flow == null || flow.isBlank() ? null : flow;
        List<TestReport.Block> blocks;
        try {
            blocks = runner.spec(code, baseUrl(request));
        } catch (TestRunner.NotLiveException e) {
            return ResponseEntity.status(409).body(Map.of("errors", List.of(e.getMessage())));
        }
        String name = "API_SPEC_" + (code == null ? "ALL" : code) + "_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + "." + f.extension;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, f.contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(ReportRenderers.render(blocks, f));
    }

    /** The address clients use: this request's scheme, host and port (a proxy's when it forwards them). */
    private static String baseUrl(jakarta.servlet.http.HttpServletRequest request) {
        return org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromContextPath(request)
                .replaceQuery(null).build().toUriString();
    }

    private boolean authorized(String supplied) {
        return token != null && supplied != null
                && MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8));
    }
}
