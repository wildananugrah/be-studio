package com.mhamzah.gateway.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mhamzah.gateway.config.ConfigCompiler;
import com.mhamzah.gateway.config.ConfigValidationException;
import com.mhamzah.gateway.config.FlowRegistry;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.config.Rows;
import com.mhamzah.gateway.extension.DefaultErrorHandler;
import com.mhamzah.gateway.extension.GatewayResponse;
import com.mhamzah.gateway.extension.snap.SnapErrorHandler;
import com.mhamzah.gateway.extension.snap.SnapResponseHandler;
import com.mhamzah.gateway.invoke.DownstreamException;
import com.mhamzah.gateway.invoke.DownstreamResponse;
import com.mhamzah.gateway.mapping.MappingEngine;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/**
 * SNAP responses end to end through the engine: snapResponseHandler,
 * snapErrorHandler and their lookups.
 */
class SnapFlowTest {

    private final FakeDownstream downstream = new FakeDownstream();
    private final AtomicReference<FlowRegistry> registry = new AtomicReference<>();
    private final Map<String, Object> beans = Map.of(
            "defaultErrorHandler", new DefaultErrorHandler(),
            SnapErrorHandler.BEAN_NAME, new SnapErrorHandler(registry::get, new MappingEngine()),
            SnapResponseHandler.BEAN_NAME, new SnapResponseHandler(registry::get, new MappingEngine()));
    private final FlowExecutor executor = new FlowExecutor(downstream, new MappingEngine(), new DefaultErrorHandler());
    private final Rows rows = new Rows();
    private String servicePattern = ConfigCompiler.DEFAULT_SERVICE_CODE_PATTERN;

    @AfterEach
    void tearDown() {
        executor.close();
    }

    private FlowRegistry compile() {
        ConfigCompiler compiler = new ConfigCompiler(
                new ConfigCompiler.HandlerLookup() {
                    @Override
                    public <T> T find(String name, Class<T> type) {
                        Object b = beans.get(name);
                        return type.isInstance(b) ? type.cast(b) : null;
                    }
                },
                Map.of("CORE", new GatewayProperties.TargetSystem("http://core", 1000, 2000, Map.of(), null)),
                java.util.function.UnaryOperator.identity(),
                Duration.ofSeconds(5), Duration.ofSeconds(2)).serviceCodePattern(servicePattern);
        FlowRegistry reg = compiler.compile(rows.build());
        registry.set(reg);
        return reg;
    }

    private GatewayResponse post(String body) {
        FlowRegistry reg = compile();
        var match = reg.match(HttpMethod.POST, "/snap/transfers").orElseThrow();
        var in = new InboundRequest(HttpMethod.POST, "/snap/transfers", Map.of(), Map.of(), body);
        return executor.execute(reg, match, in, "corr-1").response();
    }

    /**
     * A SNAP flow (service code 24) with one CORE step and the CORE error table.
     */
    private com.mhamzah.gateway.config.ConfigRows.FlowRow snapFlow() {
        var flow = rows.flow("SNAP_TRANSFER", "POST", "/snap/transfers", f -> f
                .withHandlers(null, SnapResponseHandler.BEAN_NAME, SnapErrorHandler.BEAN_NAME).withServiceCode("24"));
        var debit = rows.step(flow, "debit", 1, s -> s.withPathTemplate("/core/transfers")
                .withSuccess("${steps.debit.body.responseCode} == '00'"));
        rows.stepRule(debit, "$.amount", "$.request.body.amount");
        rows.responseRule(flow, "$.referenceNo", "$.steps.debit.body.refNo");
        rows.lookup("SNAP_ERR_CORE", "@codePath", "[\"$.body.responseCode\", \"$.body.code\", \"$.body.errorCode\"]");
        rows.lookup("SNAP_ERR_CORE", "51", "403.14");
        rows.lookup("SNAP_ERR_CORE", "INSUFFICIENT_FUNDS",
                "{\"snap\": \"403.14\", \"message\": \"Saldo tidak cukup\"}");
        rows.lookup("SNAP_ERR_CORE", "30", "400.01");
        rows.lookup("SNAP_ERR_CORE", "409:30", "409.01");
        rows.lookup("SNAP_ERR_CORE", "HTTP_401", "401.00");
        rows.lookup("SNAP_RESPONSE_CODE", "403.14", "Insufficient Funds");
        rows.lookup("SNAP_RESPONSE_CODE", "409.01", "Duplicate partnerReferenceNo");
        rows.lookup("SNAP_RESPONSE_CODE", "401.00", "Unauthorized. [reason]");
        return flow;
    }

    private static String text(GatewayResponse r, String field) {
        JsonNode n = r.body().get(field);
        return n == null ? null : n.asString();
    }

    private void debitAnswers(int status, String json) {
        downstream.on("/core/transfers",
                r -> new DownstreamResponse(status, Map.of("content-type", "application/json"), json));
    }

    @Test
    void successGetsResponseCodeFirstAndNoAdditionalInfoUnlessConfigured() {
        snapFlow();
        debitAnswers(200, "{\"responseCode\":\"00\",\"refNo\":\"TRX1\"}");

        GatewayResponse r = post("{\"amount\":\"100\"}");

        assertThat(r.status()).isEqualTo(200);
        assertThat(text(r, "responseCode")).isEqualTo("2002400");
        assertThat(text(r, "responseMessage")).isEqualTo("Successful");
        assertThat(text(r, "referenceNo")).isEqualTo("TRX1");
        List<String> names = r.body().propertyNames().stream().toList();
        assertThat(names).containsExactly("responseCode", "responseMessage", "referenceNo");
    }

    @Test
    void successAdditionalInfoAlsoComesFromTheNamedResponse() {
        var flow = snapFlow();
        rows.responseRule(flow, "$.additionalInfo.deviceId", "$.request.body.deviceId");
        rows.response(flow, "additionalInfo", null);
        rows.responseRule(flow, "additionalInfo", "$.partnerReferenceNo", "$.request.body.ref", null);
        rows.responseRule(flow, "additionalInfo", "$.failedStep", "$.error.step", null);
        debitAnswers(200, "{\"responseCode\":\"00\",\"refNo\":\"TRX1\"}");

        GatewayResponse r = post("{\"amount\":\"100\",\"deviceId\":\"D-1\",\"ref\":\"P-1\"}");

        JsonNode info = r.body().get("additionalInfo");
        assertThat(info.get("deviceId").asString()).isEqualTo("D-1");
        assertThat(info.get("partnerReferenceNo").asString()).isEqualTo("P-1");
        assertThat(info.has("failedStep")).isFalse();
        assertThat(r.body().propertyNames().stream().toList().getLast()).isEqualTo("additionalInfo");
    }

    @Test
    void namedAdditionalInfoWithNothingToMapIsLeftOut() {
        var flow = snapFlow();
        rows.response(flow, "additionalInfo", null);
        rows.responseRule(flow, "additionalInfo", "$.failedStep", "$.error.step", null);
        debitAnswers(200, "{\"responseCode\":\"00\",\"refNo\":\"TRX1\"}");

        GatewayResponse r = post("{\"amount\":\"100\"}");

        assertThat(r.body().has("additionalInfo")).isFalse();
    }

    @Test
    void successKeepsAdditionalInfoKeysFromTheRules() {
        var flow = snapFlow();
        rows.responseRule(flow, "$.additionalInfo.deviceId", "$.request.body.deviceId");
        debitAnswers(200, "{\"responseCode\":\"00\",\"refNo\":\"TRX1\"}");

        GatewayResponse r = post("{\"amount\":\"100\",\"deviceId\":\"D-1\"}");

        assertThat(r.body().get("additionalInfo").get("deviceId").asString()).isEqualTo("D-1");
        assertThat(r.body().propertyNames().stream().toList().getLast()).isEqualTo("additionalInfo");
    }

    @Test
    void acceptedUsesItsOwnStatusAndMessage() {
        var flow = snapFlow();
        rows.statusRule(flow, null, null, "202", null);
        debitAnswers(200, "{\"responseCode\":\"00\",\"refNo\":\"TRX1\"}");

        GatewayResponse r = post("{\"amount\":\"100\"}");

        assertThat(r.status()).isEqualTo(202);
        assertThat(text(r, "responseCode")).isEqualTo("2022400");
        assertThat(text(r, "responseMessage")).isEqualTo("Request In Progress");
    }

    @Test
    void textCodeIsTranslatedWithTheRowsOwnMessage() {
        snapFlow();
        debitAnswers(422, "{\"responseCode\":\"INSUFFICIENT_FUNDS\",\"message\":\"raw downstream text\"}");

        GatewayResponse r = post("{\"amount\":\"100\"}");

        assertThat(r.status()).isEqualTo(403);
        assertThat(text(r, "responseCode")).isEqualTo("4032414");
        assertThat(text(r, "responseMessage")).isEqualTo("Saldo tidak cukup");
        assertThat(r.body().has("additionalInfo")).isFalse();
        assertThat(r.body().toString()).doesNotContain("raw downstream text");
    }

    @Test
    void businessErrorInA200UsesTheCatalogMessage() {
        snapFlow();
        debitAnswers(200, "{\"responseCode\":\"51\"}");

        GatewayResponse r = post("{\"amount\":\"100\"}");

        assertThat(r.status()).isEqualTo(403);
        assertThat(text(r, "responseCode")).isEqualTo("4032414");
        assertThat(text(r, "responseMessage")).isEqualTo("Insufficient Funds");
    }

    @Test
    void numericAndTextCodesMatchTheSameRow() {
        snapFlow();
        debitAnswers(400, "{\"code\":30}");
        GatewayResponse number = post("{\"amount\":\"100\"}");
        debitAnswers(400, "{\"code\":\"30\"}");
        GatewayResponse text = post("{\"amount\":\"100\"}");

        assertThat(text(number, "responseCode")).isEqualTo("4002401");
        assertThat(text(text, "responseCode")).isEqualTo("4002401");
    }

    @Test
    void statusAndCodeRowWinsOverTheCodeRow() {
        snapFlow();
        debitAnswers(409, "{\"code\":30}");

        GatewayResponse r = post("{\"amount\":\"100\"}");

        assertThat(r.status()).isEqualTo(409);
        assertThat(text(r, "responseCode")).isEqualTo("4092401");
        assertThat(text(r, "responseMessage")).isEqualTo("Duplicate partnerReferenceNo");
    }

    @Test
    void unknownCodeFallsBackToTheHttpStatusRowThenTheGatewayDefault() {
        snapFlow();
        debitAnswers(401, "{\"code\":\"TOKEN_EXPIRED\"}");
        GatewayResponse byStatus = post("{\"amount\":\"100\"}");
        debitAnswers(502, "{\"code\":\"SOMETHING_NEW\"}");
        GatewayResponse fallback = post("{\"amount\":\"100\"}");

        assertThat(text(byStatus, "responseCode")).isEqualTo("4012400");
        assertThat(text(byStatus, "responseMessage")).isEqualTo("Unauthorized.");
        assertThat(fallback.status()).isEqualTo(500);
        assertThat(text(fallback, "responseCode")).isEqualTo("5002402");
        assertThat(text(fallback, "responseMessage")).isEqualTo("External Server Error");
    }

    @Test
    void businessCodeInA200CanKeepHttp200AndUseTheDownstreamMessage() {
        snapFlow();
        rows.lookup("SNAP_ERR_CORE", "200:01",
                "{\"snap\": \"404.01\", \"message\": \"Tidak ada data\", \"messagePath\": \"$.body.message\", \"httpStatus\": 200}");
        debitAnswers(200, "{\"code\":\"01\",\"message\":\"tidak ada dataa\"}");
        GatewayResponse withMessage = post("{\"amount\":\"100\"}");
        debitAnswers(200, "{\"code\":\"01\"}");
        GatewayResponse withoutMessage = post("{\"amount\":\"100\"}");

        assertThat(withMessage.status()).isEqualTo(200);
        assertThat(text(withMessage, "responseCode")).isEqualTo("4042401");
        assertThat(text(withMessage, "responseMessage")).isEqualTo("tidak ada dataa");
        assertThat(withoutMessage.status()).isEqualTo(200);
        assertThat(text(withoutMessage, "responseMessage")).isEqualTo("Tidak ada data");
    }

    @Test
    void anInvalidHttpStatusInARowIsIgnored() {
        snapFlow();
        rows.lookup("SNAP_ERR_CORE", "BAD", "{\"snap\": \"403.14\", \"httpStatus\": \"abc\"}");
        debitAnswers(422, "{\"code\":\"BAD\"}");

        GatewayResponse r = post("{\"amount\":\"100\"}");

        assertThat(r.status()).isEqualTo(403);
        assertThat(text(r, "responseCode")).isEqualTo("4032414");
    }

    @Test
    void a204ErrorIsSentWithTheNoBodyStatusAndKeeps204InTheResponseCode() {
        snapFlow();
        rows.lookup("SNAP_ERR_CORE", "HTTP_204", "204.00");
        rows.lookup("SNAP_RESPONSE_CODE", "204.00", "Tidak ada data");
        debitAnswers(204, "{}");

        GatewayResponse r = post("{\"amount\":\"100\"}");

        assertThat(r.status()).isEqualTo(200);
        assertThat(text(r, "responseCode")).isEqualTo("2042400");
        assertThat(text(r, "responseMessage")).isEqualTo("Tidak ada data");
    }

    @Test
    void a204SuccessIsSentWithTheNoBodyStatus() {
        var flow = snapFlow();
        rows.statusRule(flow, null, null, "204", null);
        debitAnswers(200, "{\"responseCode\":\"00\",\"refNo\":\"TRX1\"}");

        GatewayResponse r = post("{\"amount\":\"100\"}");

        assertThat(r.status()).isEqualTo(200);
        assertThat(text(r, "responseCode")).isEqualTo("2042400");
        assertThat(text(r, "referenceNo")).isEqualTo("TRX1");
    }

    @Test
    void timeoutIs504() {
        snapFlow();
        downstream.on("/core/transfers", r -> {
            throw new DownstreamException(DownstreamException.Kind.TIMEOUT, "read timed out", null);
        });

        GatewayResponse r = post("{\"amount\":\"100\"}");

        assertThat(r.status()).isEqualTo(504);
        assertThat(text(r, "responseCode")).isEqualTo("5042400");
        assertThat(text(r, "responseMessage")).isEqualTo("Timeout");
    }

    @Test
    void missingMandatoryFieldNamesTheField() {
        Rows other = new Rows();
        other.flow("SNAP_TRANSFER", "POST", "/snap/transfers", f -> f.withSchemas("SNAP_REQ", null)
                .withHandlers(null, SnapResponseHandler.BEAN_NAME, SnapErrorHandler.BEAN_NAME).withServiceCode("24"));
        other.schema("SNAP_REQ", "{\"type\":\"object\",\"required\":[\"partnerReferenceNo\"]}");

        FlowRegistry reg = compileOther(other);
        var match = reg.match(HttpMethod.POST, "/snap/transfers").orElseThrow();
        GatewayResponse r = executor.execute(reg, match,
                new InboundRequest(HttpMethod.POST, "/snap/transfers", Map.of(), Map.of(), "{}"), "c").response();

        assertThat(r.status()).isEqualTo(400);
        assertThat(text(r, "responseCode")).isEqualTo("4002402");
        assertThat(text(r, "responseMessage")).isEqualTo("Invalid Mandatory Field partnerReferenceNo");
    }

    @Test
    void wrongTypeIsAnInvalidFieldFormat() {
        Rows other = new Rows();
        other.flow("SNAP_TRANSFER", "POST", "/snap/transfers", f -> f.withSchemas("SNAP_REQ", null)
                .withHandlers(null, SnapResponseHandler.BEAN_NAME, SnapErrorHandler.BEAN_NAME).withServiceCode("24"));
        other.schema("SNAP_REQ", "{\"type\":\"object\",\"properties\":{\"amount\":{\"type\":\"object\"}}}");

        FlowRegistry reg = compileOther(other);
        var match = reg.match(HttpMethod.POST, "/snap/transfers").orElseThrow();
        GatewayResponse r = executor.execute(reg, match,
                new InboundRequest(HttpMethod.POST, "/snap/transfers", Map.of(), Map.of(), "{\"amount\":5}"), "c")
                .response();

        assertThat(r.status()).isEqualTo(400);
        assertThat(text(r, "responseCode")).isEqualTo("4002401");
        assertThat(text(r, "responseMessage")).isEqualTo("Invalid Field Format amount");
    }

    @Test
    void errorAdditionalInfoComesFromTheNamedResponse() {
        var flow = snapFlow();
        rows.response(flow, "additionalInfo", null);
        rows.responseRule(flow, "additionalInfo", "$.failedStep", "$.error.step", null);
        rows.responseRule(flow, "additionalInfo", "$.downstreamCode", "$.steps.debit.body.responseCode", null);
        rows.responseRule(flow, "additionalInfo", "$.snapCode", "$.error.code", null);
        debitAnswers(200, "{\"responseCode\":\"51\"}");

        GatewayResponse r = post("{\"amount\":\"100\"}");

        JsonNode info = r.body().get("additionalInfo");
        assertThat(info.get("failedStep").asString()).isEqualTo("debit");
        assertThat(info.get("downstreamCode").asString()).isEqualTo("51");
        assertThat(info.get("snapCode").asString()).isEqualTo("4032414");
        assertThat(r.body().propertyNames().stream().toList())
                .containsExactly("responseCode", "responseMessage", "additionalInfo");
    }

    @Test
    void brokenAdditionalInfoRulesStillSendTheError() {
        var flow = snapFlow();
        rows.response(flow, "additionalInfo", null);
        rows.rule(new com.mhamzah.gateway.config.ConfigRows.RuleRow(9999, flow.id(), null, "FLOW_RESPONSE", 1, "BODY",
                "$.needed", "$.error.nothingHere", null, null, null, null, null, true, "additionalInfo"));
        debitAnswers(200, "{\"responseCode\":\"51\"}");

        GatewayResponse r = post("{\"amount\":\"100\"}");

        assertThat(r.status()).isEqualTo(403);
        assertThat(text(r, "responseCode")).isEqualTo("4032414");
        assertThat(r.body().has("additionalInfo")).isFalse();
    }

    @Test
    void downstreamHttpErrorCodeAndMessageGoToSnapAndAdditionalInfo() {
        var flow = snapFlow();
        rows.lookup("SNAP_ERR_CORE", "INVALID_CODE", "{\"snap\": \"404.01\", \"message\": \"Data Not Found\"}");
        rows.response(flow, "additionalInfo", null);
        rows.responseRule(flow, "additionalInfo", "$.message", "$.steps.debit.body.message", null);
        debitAnswers(404, "{\"errorCode\":\"INVALID_CODE\",\"message\":\"data tidak ditemukan\"}");
        GatewayResponse error = post("{\"amount\":\"100\"}");
        debitAnswers(200, "{\"responseCode\":\"00\",\"refNo\":\"TRX1\"}");
        GatewayResponse success = post("{\"amount\":\"100\"}");

        assertThat(error.status()).isEqualTo(404);
        assertThat(text(error, "responseCode")).isEqualTo("4042401");
        assertThat(text(error, "responseMessage")).isEqualTo("Data Not Found");
        assertThat(error.body().get("additionalInfo").get("message").asString()).isEqualTo("data tidak ditemukan");
        assertThat(success.status()).isEqualTo(200);
        assertThat(success.body().has("additionalInfo")).isFalse();
    }

    @Test
    void additionalInfoResponseTakesBodyRulesOnlyAndCannotBeRoutedTo() {
        var flow = snapFlow();
        rows.response(flow, "additionalInfo", null);
        rows.statusRule(flow, "additionalInfo", null, "400", null);
        rows.step(flow, "check", 2, s -> s.withNext(null, "RESPOND:additionalInfo"));

        assertThatThrownBy(this::compile).isInstanceOf(ConfigValidationException.class)
                .satisfies(e -> assertThat(((ConfigValidationException) e).errors())
                        .anyMatch(m -> m.contains("takes BODY and COPY rules only"))
                        .anyMatch(m -> m.contains("cannot be answered with")));
    }

    @Test
    void serviceCodeIsRequiredMatchesThePatternAndIsUniquePerApplication() {
        rows.application("A", "A", null);
        rows.application("B", "B", null);
        rows.flow("NO_CODE", "GET", "/a", f -> f.withServiceCode(null));
        rows.flow("LETTERS", "GET", "/b", f -> f.withServiceCode("4A"));
        rows.flow("FIRST", "GET", "/c", f -> f.withApplication("A").withServiceCode("11"));
        rows.flow("SECOND", "GET", "/d", f -> f.withApplication("A").withServiceCode("11"));
        rows.flow("OTHER_APP", "GET", "/e", f -> f.withApplication("B").withServiceCode("11"));

        assertThatThrownBy(this::compile).isInstanceOf(ConfigValidationException.class)
                .satisfies(e -> assertThat(((ConfigValidationException) e).errors())
                        .contains("flow 'NO_CODE': service_code is required",
                                "flow 'LETTERS': service_code '4A' must match [0-9]{2}([0-9]{2})?",
                                "flow 'SECOND': service_code '11' is already used by flow 'FIRST' in application 'A'")
                        .noneMatch(m -> m.contains("OTHER_APP")));
    }

    @Test
    void alphanumericServiceCodesWhenThePatternAllowsThem() {
        servicePattern = "[0-9A-Z]{2}";
        snapFlow();
        rows.flow("LETTERS", "GET", "/b", f -> f.withServiceCode("4A"));
        debitAnswers(200, "{\"responseCode\":\"00\",\"refNo\":\"TRX1\"}");

        assertThat(text(post("{\"amount\":\"100\"}"), "responseCode")).isEqualTo("2002400");
    }

    @Test
    void fourCharacterServiceCodesAreUniqueAsAWhole() {
        rows.flow("FIRST", "GET", "/a", f -> f.withServiceCode("2401"));
        rows.flow("SIBLING", "GET", "/b", f -> f.withServiceCode("2402"));
        rows.flow("SIBLING_TWIN", "GET", "/c", f -> f.withServiceCode("2402"));

        assertThatThrownBy(this::compile).isInstanceOf(ConfigValidationException.class)
                .satisfies(e -> assertThat(((ConfigValidationException) e).errors())
                        .containsExactly("flow 'SIBLING_TWIN': service_code '2402' is already used by flow 'SIBLING'"));
    }

    @Test
    void fourCharacterServiceCodeGivesASevenCharacterResponseCode() {
        Rows other = new Rows();
        other.flow("SNAP_TRANSFER", "POST", "/snap/transfers", f -> f.withSchemas("SNAP_REQ", null)
                .withHandlers(null, SnapResponseHandler.BEAN_NAME, SnapErrorHandler.BEAN_NAME).withServiceCode("2401"));
        other.schema("SNAP_REQ", "{\"type\":\"object\",\"properties\":{\"amount\":{\"type\":\"object\"}}}");
        FlowRegistry reg = compileOther(other);
        var match = reg.match(HttpMethod.POST, "/snap/transfers").orElseThrow();

        GatewayResponse ok = executor.execute(reg, match,
                new InboundRequest(HttpMethod.POST, "/snap/transfers", Map.of(), Map.of(), "{}"), "c").response();
        GatewayResponse bad = executor.execute(reg, match,
                new InboundRequest(HttpMethod.POST, "/snap/transfers", Map.of(), Map.of(), "{\"amount\":5}"), "c")
                .response();

        assertThat(text(ok, "responseCode")).isEqualTo("2002400");
        assertThat(text(bad, "responseCode")).isEqualTo("4002401");
    }

    private FlowRegistry compileOther(Rows other) {
        ConfigCompiler compiler = new ConfigCompiler(
                new ConfigCompiler.HandlerLookup() {
                    @Override
                    public <T> T find(String name, Class<T> type) {
                        Object b = beans.get(name);
                        return type.isInstance(b) ? type.cast(b) : null;
                    }
                },
                Map.of(), java.util.function.UnaryOperator.identity(), Duration.ofSeconds(5), Duration.ofSeconds(2));
        FlowRegistry reg = compiler.compile(other.build());
        registry.set(reg);
        return reg;
    }
}