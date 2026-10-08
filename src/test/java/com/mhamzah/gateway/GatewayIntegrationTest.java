package com.mhamzah.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.mapping.JsonValues;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "gateway.admin.token=test-token",
    "gateway.docs.enabled=true",
})
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, WireMockConfiguration.class})
class GatewayIntegrationTest {

    /** Exact-decimal mapper, so assertions see the digits that were actually on the wire. */
    private static final JsonMapper MAPPER = JsonValues.MAPPER;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    WireMockServer wireMock;

    @Autowired
    DataSource dataSource;

    record Response(int status, Map<String, List<String>> headers, JsonNode body) {
        String text(String field) {
            JsonNode n = body.get(field);
            return n == null ? null : n.asString();
        }
    }

    Response call(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = r.body().isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(r.body());
        return new Response(r.statusCode(), r.headers().map(), json);
    }

    static <T> T await(Supplier<T> supplier, java.util.function.Predicate<T> done) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        T value = supplier.get();
        while (!done.test(value) && System.nanoTime() < end) {
            Thread.sleep(100);
            value = supplier.get();
        }
        return value;
    }

    // ---- demo flows over real HTTP ----

    @Test
    void accountInquiryMapsLooksUpAndConverts() throws Exception {
        Response r = call("GET", "/api/v1/accounts/1001", null);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().toString()).isEqualTo(
                "{\"accountNo\":\"1001\",\"accountName\":\"BUDI SANTOSO\",\"balance\":1500000.00,\"currency\":\"IDR\",\"status\":\"ACTIVE\"}");
        wireMock.verify(getRequestedFor(urlEqualTo("/core/accounts/1001"))
                .withHeader("X-Channel", equalTo("MOBILE"))
                .withHeader("X-Channel-Id", equalTo("GATEWAY")));
    }

    @Test
    void inboundHeaderOverridesDefault() throws Exception {
        call("GET", "/api/v1/accounts/2002", null, "X-Channel", "ATM");
        wireMock.verify(getRequestedFor(urlEqualTo("/core/accounts/2002")).withHeader("X-Channel", equalTo("ATM")));
    }

    @Test
    void historyMapsArraysWithQueryDefault() throws Exception {
        Response r = call("GET", "/api/v1/accounts/1001/transactions", null);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("transactions").toString()).isEqualTo(
                "[{\"date\":\"2026-10-01\",\"amount\":150000.00,\"type\":\"CREDIT\"},"
                        + "{\"date\":\"2026-10-03\",\"amount\":25000.50,\"type\":\"DEBIT\"}]");
        wireMock.verify(getRequestedFor(urlEqualTo("/core/accounts/1001/history?limit=10")));
        call("GET", "/api/v1/accounts/1001/transactions?limit=5", null);
        wireMock.verify(getRequestedFor(urlEqualTo("/core/accounts/1001/history?limit=5")));
    }

    @Test
    void transferRunsParallelLookupsDebitAndNotification() throws Exception {
        Response r = call("POST", "/api/v1/transfers",
                "{\"fromAccount\":\"1001\",\"toAccount\":\"2002\",\"amount\":1000}");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().toString()).isEqualTo(
                "{\"transactionId\":\"TRX20261008001\",\"status\":\"SUCCESS\",\"beneficiaryName\":\"SITI AMINAH\",\"notification\":\"SUCCESS\"}");
        wireMock.verify(postRequestedFor(urlEqualTo("/core/transfers"))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("000000000001000")))
                .withRequestBody(matchingJsonPath("$.remark", equalTo("TRANSFER")))
                .withRequestBody(matchingJsonPath("$.beneficiaryName", equalTo("SITI AMINAH"))));
        wireMock.verify(postRequestedFor(urlEqualTo("/notifications"))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("TRX20261008001"))));
    }

    @Test
    void transferToBlockedBeneficiarySkipsDebit() throws Exception {
        Response r = call("POST", "/api/v1/transfers",
                "{\"fromAccount\":\"1001\",\"toAccount\":\"3003\",\"amount\":1000}");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("status")).isEqualTo("SKIPPED");
        assertThat(r.body().has("transactionId")).isFalse();
    }

    @Test
    void insufficientFundsIsMappedByCoreBankingErrorHandler() throws Exception {
        Response r = call("POST", "/api/v1/transfers",
                "{\"fromAccount\":\"1001\",\"toAccount\":\"2002\",\"amount\":999999999}");

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.text("errorCode")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(r.text("errorMessage")).isEqualTo("Insufficient balance");
    }

    @Test
    void downstream404IsMappedToAccountNotFound() throws Exception {
        Response r = call("GET", "/api/v1/accounts/9999", null);
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.text("errorCode")).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void invalidTransferRequestIsRejectedBySchema() throws Exception {
        Response r = call("POST", "/api/v1/transfers", "{\"fromAccount\":\"1001\",\"amount\":-5}");

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.text("errorCode")).isEqualTo("GW-400-SCHEMA");
        assertThat(r.body().get("details").toString()).contains("toAccount").contains("amount");
    }

    @Test
    void slowDownstreamTimesOut() throws Exception {
        Response r = call("GET", "/api/v1/accounts/7777", null);
        assertThat(r.status()).isEqualTo(504);
        assertThat(r.text("errorCode")).isEqualTo("GW-504-DOWNSTREAM");
    }

    @Test
    void unknownRouteIs404() throws Exception {
        Response r = call("DELETE", "/api/v1/accounts/1001", null);
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.text("errorCode")).isEqualTo("GW-404-ROUTE");
    }

    @Test
    void correlationIdIsEchoedOrGenerated() throws Exception {
        Response given = call("GET", "/api/v1/accounts/1001", null, "X-Correlation-Id", "abc-123");
        assertThat(given.headers().get("x-correlation-id")).containsExactly("abc-123");
        wireMock.verify(getRequestedFor(urlEqualTo("/core/accounts/1001")).withHeader("X-Correlation-Id", equalTo("abc-123")));

        Response unsafe = call("GET", "/api/v1/accounts/1001", null, "X-Correlation-Id", "bad id with spaces");
        assertThat(unsafe.headers().get("x-correlation-id").getFirst()).isNotEqualTo("bad id with spaces").hasSize(36);
    }

    // ---- audit ----

    @Test
    void auditRowsAreWrittenWithMaskedPayloads() throws Exception {
        call("POST", "/api/v1/transfers",
                "{\"fromAccount\":\"1001\",\"toAccount\":\"2002\",\"amount\":1000,\"pin\":\"123456\"}",
                "X-Correlation-Id", "audit-it-1");

        List<Map<String, Object>> tx = await(
                () -> jdbc.queryForList("SELECT * FROM gw_audit_transaction WHERE correlation_id = ?", "audit-it-1"),
                rows -> !rows.isEmpty());
        assertThat(tx).hasSize(1);
        Map<String, Object> row = lower(tx.getFirst());
        assertThat(row.get("flow_code")).isEqualTo("TRANSFER");
        assertThat(((Number) row.get("client_status")).intValue()).isEqualTo(200);
        String requestPayload = jdbc.queryForObject(
                "SELECT request_payload FROM gw_audit_transaction WHERE correlation_id = ?", String.class, "audit-it-1");
        assertThat(requestPayload).contains("\"pin\":\"****\"").doesNotContain("123456");

        List<String> steps = jdbc.queryForList(
                "SELECT s.step_name FROM gw_audit_step s JOIN gw_audit_transaction t ON t.id = s.transaction_id"
                        + " WHERE t.correlation_id = ? ORDER BY s.id", String.class, "audit-it-1");
        assertThat(steps).containsExactlyInAnyOrder("beneficiary", "source", "debit", "notify");
    }

    @Test
    void failedRequestIsAuditedWithErrorType() throws Exception {
        call("GET", "/api/v1/accounts/9999", null, "X-Correlation-Id", "audit-it-2");
        List<Map<String, Object>> tx = await(
                () -> jdbc.queryForList("SELECT error_type, error_code FROM gw_audit_transaction WHERE correlation_id = ?",
                        "audit-it-2"),
                rows -> !rows.isEmpty());
        Map<String, Object> row = lower(tx.getFirst());
        assertThat(row.get("error_type")).isEqualTo("DOWNSTREAM_HTTP_ERROR");
        assertThat(row.get("error_code")).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    // ---- reload ----

    private String reload(String token) throws Exception {
        Response r = token == null
                ? call("POST", "/admin/config/reload", null)
                : call("POST", "/admin/config/reload", null, "X-Admin-Token", token);
        return r.status() + " " + r.body();
    }

    private void insertConstantFlow(String code, String path, String auditMode) {
        jdbc.update("INSERT INTO gw_flow (code, name, http_method, path_pattern, success_status, audit_mode, enabled)"
                + " VALUES (?, ?, 'GET', ?, 200, ?, ?)", code, code, path, auditMode, true);
        Long flowId = jdbc.queryForObject("SELECT id FROM gw_flow WHERE code = ?", Long.class, code);
        jdbc.update("INSERT INTO gw_mapping_rule (flow_id, phase, seq, target_type, target_path, constant_value, required)"
                + " VALUES (?, 'FLOW_RESPONSE', 1, 'BODY', '$.hello', 'world', ?)", flowId, false);
    }

    private void deleteFlow(String code) {
        jdbc.update("DELETE FROM gw_mapping_rule WHERE flow_id IN (SELECT id FROM gw_flow WHERE code = ?)", code);
        jdbc.update("DELETE FROM gw_flow WHERE code = ?", code);
    }

    @Test
    void reloadPicksUpNewFlowsAndRejectsInvalidConfig() throws Exception {
        assertThat(call("GET", "/api/it/reload", null).status()).isEqualTo(404);
        insertConstantFlow("IT_RELOAD", "/it/reload", "INHERIT");
        try {
            assertThat(reload("test-token")).startsWith("200");
            Response r = call("GET", "/api/it/reload", null);
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.body().toString()).isEqualTo("{\"hello\":\"world\"}");

            jdbc.update("UPDATE gw_flow SET http_method = 'FETCH' WHERE code = 'IT_RELOAD'");
            String rejected = reload("test-token");
            assertThat(rejected).startsWith("422").contains("FETCH");
            assertThat(call("GET", "/api/it/reload", null).status()).as("old config stays live").isEqualTo(200);
        } finally {
            deleteFlow("IT_RELOAD");
            assertThat(reload("test-token")).startsWith("200");
        }
        assertThat(call("GET", "/api/it/reload", null).status()).isEqualTo(404);
    }

    @Test
    void targetSystemAddressComesFromDatabaseAndIsReloadable() throws Exception {
        String seeded = jdbc.queryForObject(
                "SELECT base_url FROM gw_target_system WHERE code = 'CORE_BANKING'", String.class);
        assertThat(seeded).as("Liquibase must store the placeholder text unchanged")
                .isEqualTo("${CORE_BANKING_URL:http://localhost:8089}");
        assertThat(call("GET", "/api/v1/accounts/1001", null).status()).isEqualTo(200);

        jdbc.update("UPDATE gw_target_system SET base_url = 'http://127.0.0.1:1' WHERE code = 'CORE_BANKING'");
        try {
            assertThat(reload("test-token")).startsWith("200");
            Response moved = call("GET", "/api/v1/accounts/1001", null);
            assertThat(moved.status()).isEqualTo(502);
            assertThat(moved.text("errorCode")).isEqualTo("GW-502-CONNECTION");

            jdbc.update("UPDATE gw_target_system SET base_url = 'not a url' WHERE code = 'CORE_BANKING'");
            assertThat(reload("test-token")).startsWith("422").contains("target system 'CORE_BANKING'");
        } finally {
            jdbc.update("UPDATE gw_target_system SET base_url = ? WHERE code = 'CORE_BANKING'", seeded);
            assertThat(reload("test-token")).startsWith("200");
        }
        assertThat(call("GET", "/api/v1/accounts/1001", null).status()).isEqualTo(200);
    }

    @Test
    void customClassExampleFlowWorksEndToEnd() throws Exception {
        // the exact script from the documentation, on PostgreSQL or Oracle
        new ResourceDatabasePopulator(new FileSystemResource("docs/examples/custom-classes.sql")).execute(dataSource);
        try {
            assertThat(reload("test-token")).startsWith("200");

            // channelGuard (flow request handler) short-circuits: no downstream call
            Response blocked = call("GET", "/api/v3/accounts/1001", null);
            assertThat(blocked.status()).isEqualTo(403);
            assertThat(blocked.text("errorCode")).isEqualTo("CHANNEL_NOT_ALLOWED");

            // allowed channel: requestSigner + idrAmountFormatter
            Response ok = call("GET", "/api/v3/accounts/1001", null, "X-Channel", "MOBILE");
            assertThat(ok.status()).isEqualTo(200);
            assertThat(ok.body().toString()).isEqualTo("{\"responseCode\":\"00\",\"responseMessage\":\"Approved\","
                    + "\"accountNo\":\"1001\",\"accountName\":\"BUDI SANTOSO\",\"balance\":\"Rp1.500.000,00\"}");
            wireMock.verify(getRequestedFor(urlEqualTo("/core/accounts/1001"))
                    .withHeader("X-Timestamp", matching("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"))
                    .withHeader("X-Signature", matching("[A-Za-z0-9+/]{43}=")));
            // the partner must be able to verify the signature against what it actually received
            var received = wireMock.getAllServeEvents().stream().map(e -> e.getRequest())
                    .filter(r -> r.getUrl().equals("/core/accounts/1001") && r.containsHeader("X-Signature"))
                    .findFirst().orElseThrow();
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec("dev-signing-secret".getBytes(), "HmacSHA256"));
            String expected = java.util.Base64.getEncoder().encodeToString(
                    mac.doFinal((received.getHeader("X-Timestamp") + ":" + received.getBodyAsString()).getBytes()));
            assertThat(received.getHeader("X-Signature")).isEqualTo(expected);

            // partnerErrorHandler: core 404 with responseCode 14 -> HTTP 200, responseCode 14
            Response notFound = call("GET", "/api/v3/accounts/9999", null, "X-Channel", "ATM");
            assertThat(notFound.status()).isEqualTo(200);
            assertThat(notFound.text("responseCode")).isEqualTo("14");
            assertThat(notFound.text("responseMessage")).isEqualTo("Account not found");
        } finally {
            deleteFlow("PARTNER_ACCOUNT_INQUIRY"); // steps and rules go with the flow (ON DELETE CASCADE)
            assertThat(reload("test-token")).startsWith("200");
        }
    }

    @Test
    void reloadRequiresToken() throws Exception {
        assertThat(reload(null)).startsWith("401");
        assertThat(reload("wrong")).startsWith("401");
    }

    @Test
    void perFlowAuditOffIsHonored() throws Exception {
        insertConstantFlow("IT_NOAUDIT", "/it/noaudit", "OFF");
        try {
            assertThat(reload("test-token")).startsWith("200");
            call("GET", "/api/it/noaudit", null, "X-Correlation-Id", "noaudit-1");
            call("GET", "/api/v1/accounts/1001", null, "X-Correlation-Id", "audited-1");
            await(() -> jdbc.queryForObject(
                    "SELECT COUNT(*) FROM gw_audit_transaction WHERE correlation_id = 'audited-1'", Integer.class),
                    n -> n > 0);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM gw_audit_transaction WHERE correlation_id = 'noaudit-1'", Integer.class))
                    .isZero();
        } finally {
            deleteFlow("IT_NOAUDIT");
            reload("test-token");
        }
    }

    // ---- HikariCP ----

    @Test
    void dataSourceIsTheConfiguredHikariPool() throws Exception {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getPoolName()).isEqualTo("gateway-pool");
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(10);
        assertThat(hikari.getMinimumIdle()).isEqualTo(2);
        assertThat(hikari.getLeakDetectionThreshold()).isEqualTo(20000);

        Response metrics = call("GET", "/actuator/metrics/hikaricp.connections.active", null);
        assertThat(metrics.status()).isEqualTo(200);
        assertThat(metrics.body().toString()).contains("gateway-pool");
    }

    private static Map<String, Object> lower(Map<String, Object> row) {
        Map<String, Object> out = new java.util.HashMap<>();
        row.forEach((k, v) -> out.put(k.toLowerCase(java.util.Locale.ROOT), v));
        return out;
    }

    @Test
    void soapTargetSystemIsCalledInXmlAndAnsweredInJson() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/it/soap/bank")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/xml; charset=UTF-8")
                .withBody("<S:Envelope xmlns:S=\"http://schemas.xmlsoap.org/soap/envelope/\"><S:Body>"
                        + "<ns2:InquiryResponse xmlns:ns2=\"urn:bank\"><ns2:name>BUDI</ns2:name>"
                        + "<ns2:balance>100.10</ns2:balance></ns2:InquiryResponse></S:Body></S:Envelope>")));
        jdbc.update("INSERT INTO gw_target_system (code, base_url, body_codec, enabled) VALUES ('IT_SOAP', ?, 'soapCodec', ?)",
                wireMock.baseUrl(), true);
        jdbc.update("INSERT INTO gw_flow (code, name, http_method, path_pattern, success_status, audit_mode, enabled)"
                + " VALUES ('IT_SOAP', 'IT_SOAP', 'POST', '/it/soap', 200, 'INHERIT', ?)", true);
        Long flowId = jdbc.queryForObject("SELECT id FROM gw_flow WHERE code = 'IT_SOAP'", Long.class);
        jdbc.update("INSERT INTO gw_flow_step (flow_id, name, step_order, target_system, http_method, path_template,"
                + " on_failure, enabled) VALUES (?, 'inquiry', 1, 'IT_SOAP', 'POST', '/it/soap/bank', 'STOP', ?)", flowId, true);
        Long stepId = jdbc.queryForObject("SELECT id FROM gw_flow_step WHERE flow_id = ?", Long.class, flowId);
        String rule = "INSERT INTO gw_mapping_rule (flow_id, step_id, phase, seq, target_type, target_path, source_path,"
                + " constant_value, required) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbc.update(rule, flowId, stepId, "STEP_REQUEST", 1, "HEADER", "SOAPAction", null, "\"urn:bank/Inquiry\"", false);
        jdbc.update(rule, flowId, stepId, "STEP_REQUEST", 2, "BODY", "$['ns:InquiryRequest']['@xmlns:ns']", null,
                "\"urn:bank\"", false);
        jdbc.update(rule, flowId, stepId, "STEP_REQUEST", 3, "BODY", "$['ns:InquiryRequest']['ns:accountNo']",
                "$.request.body.accountNo", null, true);
        jdbc.update(rule, flowId, null, "FLOW_RESPONSE", 4, "BODY", "$.name", "$.steps.inquiry.body.InquiryResponse.name",
                null, false);
        jdbc.update(rule, flowId, null, "FLOW_RESPONSE", 5, "BODY", "$.balance",
                "$.steps.inquiry.body.InquiryResponse.balance", null, false);
        try {
            assertThat(reload("test-token")).startsWith("200");

            Response r = call("POST", "/api/it/soap", "{\"accountNo\":\"123\"}");

            assertThat(r.status()).isEqualTo(200);
            assertThat(r.body().toString()).isEqualTo("{\"name\":\"BUDI\",\"balance\":\"100.10\"}");
            wireMock.verify(postRequestedFor(urlEqualTo("/it/soap/bank"))
                    .withHeader("SOAPAction", equalTo("urn:bank/Inquiry"))
                    .withHeader("Content-Type", equalTo("text/xml; charset=UTF-8"))
                    .withRequestBody(containing("<soapenv:Body><ns:InquiryRequest xmlns:ns=\"urn:bank\">"
                            + "<ns:accountNo>123</ns:accountNo></ns:InquiryRequest></soapenv:Body>")));
        } finally {
            deleteFlow("IT_SOAP");
            jdbc.update("DELETE FROM gw_target_system WHERE code = 'IT_SOAP'");
            assertThat(reload("test-token")).startsWith("200");
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void swaggerUiIsServedAtDocs() throws Exception {
        HttpResponse<String> page = get("/docs");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        assertThat(page.body()).contains("/webjars/swagger-ui/swagger-ui-bundle.js").contains("/docs/openapi.json");
        assertThat(get("/webjars/swagger-ui/swagger-ui-bundle.js").statusCode()).isEqualTo(200);
        assertThat(get("/webjars/swagger-ui/swagger-ui.css").statusCode()).isEqualTo(200);
    }

    @Test
    void openApiDescribesTheConfiguredFlowsAndFollowsReloads() throws Exception {
        HttpResponse<String> r = get("/docs/openapi.json");

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode doc = MAPPER.readTree(r.body());
        assertThat(doc.get("openapi").asString()).isEqualTo("3.1.0");
        assertThat(doc.get("paths").has("/api/v1/accounts/{accountNo}")).isTrue();
        assertThat(doc.get("paths").get("/api/v1/transfers").has("post")).isTrue();
        assertThat(doc.get("paths").has("/api/it/docs")).isFalse();

        insertConstantFlow("IT_DOCS", "/it/docs", "INHERIT");
        try {
            assertThat(reload("test-token")).startsWith("200");
            JsonNode reloaded = MAPPER.readTree(get("/docs/openapi.json").body());
            assertThat(reloaded.get("paths").get("/api/it/docs").get("get").get("operationId").asString())
                    .isEqualTo("IT_DOCS");
        } finally {
            deleteFlow("IT_DOCS");
            assertThat(reload("test-token")).startsWith("200");
        }
    }
}
