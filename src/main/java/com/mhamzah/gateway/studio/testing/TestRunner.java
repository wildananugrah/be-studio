package com.mhamzah.gateway.studio.testing;

import com.mhamzah.gateway.config.FlowDefinition;
import com.mhamzah.gateway.config.FlowRegistry;
import com.mhamzah.gateway.config.FlowRegistryHolder;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.docs.OpenApiGenerator;
import com.mhamzah.gateway.invoke.DownstreamRequest;
import com.mhamzah.gateway.logging.CorrelationId;
import com.mhamzah.gateway.mapping.JsonValues;
import com.mhamzah.gateway.masking.Masker;
import com.mhamzah.gateway.studio.audit.AuditRows;
import com.mhamzah.gateway.studio.testing.TestModel.AuditTrail;
import com.mhamzah.gateway.studio.testing.TestModel.DownstreamExchange;
import com.mhamzah.gateway.studio.testing.TestModel.Message;
import com.mhamzah.gateway.studio.testing.TestModel.TestCase;
import com.mhamzah.gateway.studio.testing.TestModel.TestResult;
import com.mhamzah.gateway.studio.testing.TestModel.TestRun;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Clob;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Runs Gateway Studio test cases against this gateway over HTTP (so the evidence is the real request, through every
 * filter and the request log) and collects, per call, the downstream exchanges, the audit rows and the log lines.
 * The last runs are kept in memory so their report can be downloaded.
 */
public class TestRunner {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final Set<String> SKIPPED_RESPONSE_HEADERS = Set.of(":status", "connection", "keep-alive",
            "transfer-encoding", "date");
    private static final int KEPT_RUNS = 20;
    private static final Duration AUDIT_WAIT = Duration.ofSeconds(5);

    private final FlowRegistryHolder holder;
    private final GatewayProperties properties;
    private final Environment environment;
    private final TestRecorder recorder;
    private final Masker masker;
    private final JdbcClient jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, TestRun> runs = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TestRun> eldest) {
            return size() > KEPT_RUNS;
        }
    };

    public TestRunner(FlowRegistryHolder holder, GatewayProperties properties, Environment environment,
            TestRecorder recorder, Masker masker, DataSource dataSource) {
        this.holder = holder;
        this.properties = properties;
        this.environment = environment;
        this.recorder = recorder;
        this.masker = masker;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** Thrown for a flow that is not live (unknown, disabled, or only changed in the Studio and not saved yet). */
    public static class NotLiveException extends RuntimeException {
        NotLiveException(String message) {
            super(message);
        }
    }

    private FlowDefinition live(FlowRegistry registry, String flowCode) {
        return registry.flows().stream().filter(f -> f.code().equals(flowCode)).findFirst()
                .orElseThrow(() -> new NotLiveException("flow '" + flowCode
                        + "' is not live: save & reload it first (and make sure it is enabled)"));
    }

    /** Cases from the flow's operation in the live API description. */
    public List<TestCase> generate(String flowCode) {
        FlowRegistry registry = holder.current();
        FlowDefinition flow = live(registry, flowCode);
        JsonNode openApi = OpenApiGenerator.generate(registry, properties.apiBasePath(), properties.docs().title());
        return TestCaseGenerator.generate(openApi, flow, properties.apiBasePath());
    }

    public synchronized TestRun run(String flowCode, List<TestCase> cases) {
        FlowRegistry registry = holder.current();
        FlowDefinition flow = live(registry, flowCode);
        String gateway = "http://localhost:" + environment.getProperty("local.server.port", "8080")
                + environment.getProperty("server.servlet.context-path", "");
        boolean audited = flow.auditMode().resolve(properties.audit().enabled());
        String executedAt = STAMP.format(Instant.now());

        List<TestResult> results = new ArrayList<>();
        for (TestCase c : cases) {
            results.add(runCase(flow, c, gateway, audited));
        }
        int passed = (int) results.stream().filter(r -> Boolean.TRUE.equals(r.passed())).count();
        int failed = (int) results.stream().filter(r -> Boolean.FALSE.equals(r.passed())).count();
        String runId = UUID.randomUUID().toString();
        TestRun run = new TestRun(runId, flow.code(), flowName(flowCode), flow.method().name(),
                properties.apiBasePath() + flow.pathPattern().getPatternString(), gateway, executedAt,
                STAMP.format(registry.loadedAt()), properties.masking().fields(), passed, failed,
                results.size() - passed - failed, results);
        runs.put(runId, run);
        return run;
    }

    public synchronized TestRun find(String runId) {
        return runs.get(runId);
    }

    private String flowName(String code) {
        try {
            return jdbc.sql("SELECT name FROM " + properties.db().qualify(properties.db().tables().flow())
                    + " WHERE code = ?").param(code).query(String.class).optional().orElse(code);
        } catch (RuntimeException e) {
            return code;
        }
    }

    private TestResult runCase(FlowDefinition flow, TestCase c, String gateway, boolean audited) {
        String correlationId = "UT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
        TestRecorder.Capture capture = recorder.start(correlationId);
        String startedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS).toString();
        long start = System.nanoTime();
        try {
            String url = gateway + properties.apiBasePath() + c.path() + queryString(c.query());
            String body = c.body() == null || c.body().isNull() ? null : JsonValues.MAPPER.writeValueAsString(c.body());
            Map<String, String> headers = new LinkedHashMap<>(c.headers());
            if (body != null) {
                headers.putIfAbsent("Content-Type", "application/json");
            }
            headers.put("Accept", "application/json");
            headers.put(CorrelationId.HEADER, correlationId);
            Message incoming = new Message(c.method(), url, null, masker.maskHeaders(headers), json(body));

            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(flow.timeout().plusSeconds(5))
                    .method(c.method(), body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            headers.forEach(b::header);
            Message outgoing;
            String error = null;
            Integer status = null;
            try {
                HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                status = r.statusCode();
                Map<String, String> rh = new TreeMap<>();
                r.headers().map().forEach((k, v) -> {
                    if (!SKIPPED_RESPONSE_HEADERS.contains(k.toLowerCase(Locale.ROOT))) {
                        rh.put(k, String.join(", ", v));
                    }
                });
                outgoing = new Message(null, null, status, masker.maskHeaders(rh), json(r.body()));
            } catch (java.io.IOException e) {
                error = "No response from the gateway: " + e.getMessage();
                outgoing = null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = "Interrupted";
                outgoing = null;
            }
            long duration = (System.nanoTime() - start) / 1_000_000;
            AuditTrail audit = audit(flow, correlationId, audited);
            Boolean passed = error != null ? Boolean.FALSE
                    : c.expectedStatus() == null ? null : c.expectedStatus().equals(status);
            List<DownstreamExchange> downstream = new ArrayList<>();
            synchronized (capture.calls) {
                capture.calls.forEach(call -> downstream.add(exchange(call)));
            }
            List<String> logs;
            synchronized (capture.logs) {
                logs = List.copyOf(capture.logs);
            }
            return new TestResult(c, correlationId, passed, error, startedAt, duration, incoming, downstream,
                    outgoing, audit, logs);
        } finally {
            recorder.stop(correlationId);
        }
    }

    private DownstreamExchange exchange(TestRecorder.Call call) {
        DownstreamRequest q = call.request();
        Message request = new Message(q.method().name(), q.url(), null, masker.maskHeaders(new TreeMap<>(q.headers())),
                json(q.body()));
        Message response = call.response() == null ? null : new Message(null, null, call.response().status(),
                masker.maskHeaders(new TreeMap<>(call.response().headers())), json(call.response().body()));
        return new DownstreamExchange(q.targetSystem(), request, response, call.durationMs(), call.error());
    }

    /** JSON text masked and pretty-printed; anything else (XML, plain text) as-is. */
    private String json(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        try {
            JsonNode node = JsonValues.MAPPER.readTree(text);
            return JsonValues.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(masker.mask(node));
        } catch (RuntimeException e) {
            return text;
        }
    }

    private static String queryString(Map<String, String> query) {
        if (query.isEmpty()) {
            return "";
        }
        return "?" + query.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private AuditTrail audit(FlowDefinition flow, String correlationId, boolean audited) {
        if (!audited) {
            return new AuditTrail(false, "Not recorded: the flow's audit_mode is " + flow.auditMode()
                    + " and gateway.audit.enabled is " + properties.audit().enabled()
                    + ". Set gateway.audit.enabled=true (GATEWAY_AUDIT_ENABLED) or the flow's audit_mode to ON"
                    + " to include the audit trail.", null, List.of());
        }
        GatewayProperties.Db db = properties.db();
        String tx = db.qualify(db.tables().auditTransaction());
        String step = db.qualify(db.tables().auditStep());
        long deadline = System.nanoTime() + AUDIT_WAIT.toNanos();
        List<Map<String, Object>> rows = List.of();
        while (System.nanoTime() < deadline) {
            rows = jdbc.sql("SELECT * FROM " + tx + " WHERE correlation_id = ?").param(correlationId).query().listOfRows();
            if (!rows.isEmpty()) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (rows.isEmpty()) {
            return new AuditTrail(true, "No audit row arrived within " + AUDIT_WAIT.toSeconds()
                    + " s (the audit writer is asynchronous; check the logs for audit errors).", null, List.of());
        }
        Map<String, Object> transaction = AuditRows.row(rows.getFirst());
        Object id = transaction.get("id");
        List<Map<String, Object>> steps = jdbc.sql("SELECT * FROM " + step + " WHERE transaction_id = ? ORDER BY id")
                .param(id).query().listOfRows().stream().map(AuditRows::row).toList();
        String note = properties.audit().storePayloads() ? null
                : "gateway.audit.store-payloads is false, so the audit rows carry no payloads.";
        return new AuditTrail(true, note, transaction, steps);
    }

}
