package com.mhamzah.gateway.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.codec.SoapCodec;
import com.mhamzah.gateway.codec.XmlCodec;
import com.mhamzah.gateway.config.ConfigCompiler;
import com.mhamzah.gateway.config.ConfigRows.RuleRow;
import com.mhamzah.gateway.config.FlowRegistry;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.config.Rows;
import com.mhamzah.gateway.extension.DefaultErrorHandler;
import com.mhamzah.gateway.extension.ErrorHandler;
import com.mhamzah.gateway.extension.GatewayResponse;
import com.mhamzah.gateway.extension.MessageHandler;
import com.mhamzah.gateway.extension.custom.CoreBankingErrorHandler;
import com.mhamzah.gateway.invoke.DownstreamException;
import com.mhamzah.gateway.invoke.DownstreamResponse;
import com.mhamzah.gateway.mapping.MappingEngine;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

class FlowExecutorTest {

    private final FakeDownstream downstream = new FakeDownstream();
    private final Map<String, Object> beans = new HashMap<>(Map.of(
            "defaultErrorHandler", new DefaultErrorHandler(),
            "coreBankingErrorHandler", new CoreBankingErrorHandler()));
    private final FlowExecutor executor = new FlowExecutor(downstream, new MappingEngine(), new DefaultErrorHandler());
    private final Rows rows = new Rows();

    @AfterEach
    void tearDown() {
        executor.close();
    }

    private FlowRegistry registry() {
        ConfigCompiler compiler = new ConfigCompiler(
                new ConfigCompiler.HandlerLookup() {
                    @Override
                    public <T> T find(String name, Class<T> type) {
                        Object b = beans.get(name);
                        return type.isInstance(b) ? type.cast(b) : null;
                    }
                },
                Map.of("CORE", new GatewayProperties.TargetSystem("http://core", 1000, 2000,
                        Map.of("X-Channel-Id", "GATEWAY"), null)),
                java.util.function.UnaryOperator.identity(),
                Duration.ofSeconds(5), Duration.ofSeconds(2));
        return compiler.compile(rows.build());
    }

    private ExecutionResult run(String method, String path, String body) {
        FlowRegistry reg = registry();
        var match = reg.match(HttpMethod.valueOf(method), path).orElseThrow();
        var inbound = new InboundRequest(HttpMethod.valueOf(method), path, Map.of("x-user", "u1"), Map.of("lang", "id"), body);
        return executor.execute(reg, match, inbound, "corr-1");
    }

    private static String text(GatewayResponse r, String field) {
        JsonNode n = r.body().get(field);
        return n == null ? null : n.asString();
    }

    @Test
    void singleStepTransformsRequestAndResponse() {
        var flow = rows.flow("INQ", "POST", "/accounts/{no}/inquiry");
        var step = rows.step(flow, "core", 1, s -> s.withPathTemplate("/acct/{acc}"));
        rows.rule(flow.id(), step.id(), "STEP_REQUEST", "PATH", "acc", "$.request.path.no", null);
        rows.rule(flow.id(), step.id(), "STEP_REQUEST", "HEADER", "X-User", "$.request.headers.x-user", null);
        rows.rule(flow.id(), step.id(), "STEP_REQUEST", "QUERY", "lang", "$.request.query.lang", null);
        rows.stepRule(step, "$.nominal", "$.request.body.amount");
        rows.responseRule(flow, "$.accountName", "$.steps.core.body.name");
        rows.rule(flow.id(), null, "FLOW_RESPONSE", "HEADER", "X-Source", null, "CORE");
        downstream.ok("/acct/12%2F3", "{\"name\":\"BUDI\"}");

        var result = run("POST", "/accounts/12%2F3/inquiry", "{\"amount\":10}");

        assertThat(result.response().status()).isEqualTo(200);
        assertThat(text(result.response(), "accountName")).isEqualTo("BUDI");
        assertThat(result.response().headers()).containsEntry("X-Source", "CORE").containsEntry("X-Correlation-Id", "corr-1");
        var sent = downstream.last("/acct/");
        assertThat(sent.url()).isEqualTo("http://core/acct/12%2F3?lang=id");
        assertThat(sent.headers()).containsEntry("X-User", "u1").containsEntry("X-Channel-Id", "GATEWAY")
                .containsEntry("X-Correlation-Id", "corr-1");
        assertThat(sent.body().toString()).isEqualTo("{\"nominal\":10}");
    }

    @Test
    void decimalAmountsPassThroughExactly() {
        var flow = rows.flow("F", "POST", "/f");
        var step = rows.step(flow, "s", 1);
        rows.stepRule(step, "$.amt", "$.request.body.amount");
        rows.responseRule(flow, "$.balance", "$.steps.s.body.balance");
        downstream.ok("/s", "{\"balance\":100.10}");

        var result = run("POST", "/f", "{\"amount\":12500.50}");

        assertThat(downstream.last("/s").body().toString()).isEqualTo("{\"amt\":12500.50}");
        assertThat(result.response().body().toString()).isEqualTo("{\"balance\":100.10}");
    }

    @Test
    void laterStepsUseEarlierResults() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "one", 1);
        var two = rows.step(flow, "two", 2);
        rows.stepRule(two, "$.fromOne", "$.steps.one.body.v");
        downstream.ok("/one", "{\"v\":\"A\"}").ok("/two", "{}");

        run("POST", "/f", "{}");

        assertThat(downstream.last("/two").body()).isEqualTo("{\"fromOne\":\"A\"}");
    }

    @Test
    void stepsInTheSameGroupRunInParallel() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "a", 1);
        rows.step(flow, "b", 1);
        rows.responseRule(flow, "$.a", "$.steps.a.body.v");
        rows.responseRule(flow, "$.b", "$.steps.b.body.v");
        downstream.slow("/a", Duration.ofMillis(400), "{\"v\":1}").slow("/b", Duration.ofMillis(400), "{\"v\":2}");

        long start = System.nanoTime();
        var result = run("POST", "/f", "{}");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.response().body().toString()).isEqualTo("{\"a\":1,\"b\":2}");
        assertThat(elapsedMs).isLessThan(750);
    }

    @Test
    void falseConditionSkipsStep() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "check", 1);
        rows.step(flow, "debit", 2, s -> s.withCondition("${steps.check.body.status} == 'ACTIVE'"));
        rows.responseRule(flow, "$.debit", "$.steps.debit.outcome");
        downstream.ok("/check", "{\"status\":\"BLOCKED\"}").ok("/debit", "{}");

        var result = run("POST", "/f", "{}");

        assertThat(text(result.response(), "debit")).isEqualTo("SKIPPED");
        assertThat(downstream.requests).noneMatch(r -> r.path().startsWith("/debit"));
        assertThat(result.audit().steps()).anySatisfy(s -> {
            assertThat(s.stepName()).isEqualTo("debit");
            assertThat(s.outcome()).isEqualTo("SKIPPED");
        });
    }

    @Test
    void stopFailureCancelsSiblingsAndUsesErrorHandler() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "bad", 1);
        rows.step(flow, "slow", 1);
        downstream.on("/bad", r -> new DownstreamResponse(500, Map.of(), "{\"err\":1}"))
                .slow("/slow", Duration.ofSeconds(3), "{}");

        long start = System.nanoTime();
        var result = run("POST", "/f", "{}");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.response().status()).isEqualTo(502);
        assertThat(text(result.response(), "errorCode")).isEqualTo("GW-502-DOWNSTREAM");
        assertThat(text(result.response(), "step")).isEqualTo("bad");
        assertThat(elapsedMs).isLessThan(1500);
        assertThat(downstream.completed).doesNotContain("/slow");
        assertThat(result.audit().errorType()).isEqualTo("DOWNSTREAM_HTTP_ERROR");
    }

    @Test
    void continueFailureIsRecordedAndFlowSucceeds() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "notify", 1, s -> new com.mhamzah.gateway.config.ConfigRows.StepRow(s.id(), s.flowId(), s.name(),
                s.stepOrder(), s.targetSystem(), s.httpMethod(), s.pathTemplate(), null, null, "CONTINUE", null, null,
                null, null, null, true));
        rows.responseRule(flow, "$.notify", "$.steps.notify.outcome");
        rows.responseRule(flow, "$.status", "$.steps.notify.status");
        downstream.on("/notify", r -> new DownstreamResponse(503, Map.of(), ""));

        var result = run("POST", "/f", "{}");

        assertThat(result.response().status()).isEqualTo(200);
        assertThat(result.response().body().toString()).isEqualTo("{\"notify\":\"FAILED\",\"status\":503}");
    }

    @Test
    void downstreamTimeoutIs504() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "s", 1);
        downstream.on("/s", r -> {
            throw new DownstreamException(DownstreamException.Kind.TIMEOUT, "read timed out", null);
        });
        var result = run("POST", "/f", "{}");
        assertThat(result.response().status()).isEqualTo(504);
        assertThat(text(result.response(), "errorCode")).isEqualTo("GW-504-DOWNSTREAM");
        assertThat(result.audit().steps().getFirst().outcome()).isEqualTo("TIMEOUT");
    }

    @Test
    void flowTimeoutIs504() {
        var flow = rows.flow("F", "POST", "/f", f -> new com.mhamzah.gateway.config.ConfigRows.FlowRow(f.id(), f.code(),
                f.name(), f.httpMethod(), f.pathPattern(), null, null, null, null, null, null, 300, "INHERIT", true));
        rows.step(flow, "s", 1);
        downstream.slow("/s", Duration.ofSeconds(2), "{}");
        long start = System.nanoTime();
        var result = run("POST", "/f", "{}");
        assertThat(result.response().status()).isEqualTo(504);
        assertThat(text(result.response(), "errorCode")).isEqualTo("GW-504-FLOW");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1500);
    }

    @Test
    void connectionFailureIs502() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "s", 1);
        downstream.on("/s", r -> {
            throw new DownstreamException(DownstreamException.Kind.CONNECTION, "refused", null);
        });
        var result = run("POST", "/f", "{}");
        assertThat(text(result.response(), "errorCode")).isEqualTo("GW-502-CONNECTION");
    }

    @Test
    void invalidDownstreamJsonIs502() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "s", 1);
        downstream.on("/s", r -> new DownstreamResponse(200, Map.of(), "<html>"));
        var result = run("POST", "/f", "{}");
        assertThat(text(result.response(), "errorCode")).isEqualTo("GW-502-INVALID-RESPONSE");
    }

    @Test
    void invalidRequestJsonIs400() {
        rows.flow("F", "POST", "/f");
        var result = run("POST", "/f", "{oops");
        assertThat(result.response().status()).isEqualTo(400);
        assertThat(text(result.response(), "errorCode")).isEqualTo("GW-400-JSON");
    }

    @Test
    void emptyRequestBodyIsEmptyObject() {
        var flow = rows.flow("F", "GET", "/f");
        rows.responseRule(flow, "$.body", "$.request.body");
        var result = run("GET", "/f", "");
        assertThat(result.response().body().toString()).isEqualTo("{\"body\":{}}");
    }

    @Test
    void requestSchemaViolationIs400WithDetails() {
        rows.schema("REQ", "{\"type\":\"object\",\"required\":[\"amount\"]}");
        rows.flow("F", "POST", "/f", f -> f.withSchemas("REQ", null));
        var result = run("POST", "/f", "{}");
        assertThat(result.response().status()).isEqualTo(400);
        assertThat(text(result.response(), "errorCode")).isEqualTo("GW-400-SCHEMA");
        assertThat(result.response().body().get("details").get(0).stringValue()).contains("amount");
    }

    @Test
    void responseSchemaViolationIs500() {
        rows.schema("RES", "{\"type\":\"object\",\"required\":[\"id\"]}");
        rows.flow("F", "POST", "/f", f -> f.withSchemas(null, "RES"));
        var result = run("POST", "/f", "{}");
        assertThat(result.response().status()).isEqualTo(500);
        assertThat(text(result.response(), "errorCode")).isEqualTo("GW-500-RESPONSE-SCHEMA");
    }

    @Test
    void businessErrorIsMappedThroughLookupErrorHandler() {
        var flow = rows.flow("F", "POST", "/f", f -> f.withHandlers(null, null, "coreBankingErrorHandler"));
        rows.step(flow, "debit", 1, s -> s.withSuccess("${steps.debit.body.responseCode} == '00'"));
        rows.lookup("CORE_BANKING_ERRORS", "51",
                "{\"status\":422,\"errorCode\":\"INSUFFICIENT_FUNDS\",\"errorMessage\":\"Insufficient balance\"}");
        downstream.ok("/debit", "{\"responseCode\":\"51\"}");

        var result = run("POST", "/f", "{}");

        assertThat(result.response().status()).isEqualTo(422);
        assertThat(text(result.response(), "errorCode")).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void flowRequestHandlerCanShortCircuit() {
        beans.put("blocker", (MessageHandler) (m, c) ->
                GatewayResponse.of(403, JsonNodeFactory.instance.objectNode().put("blocked", true)));
        var flow = rows.flow("F", "POST", "/f", f -> f.withHandlers("blocker", null, null));
        rows.step(flow, "s", 1);

        var result = run("POST", "/f", "{}");

        assertThat(result.response().status()).isEqualTo(403);
        assertThat(downstream.requests).isEmpty();
    }

    @Test
    void stepHandlersCanModifyRequestAndResponse() {
        beans.put("signer", (MessageHandler) (m, c) -> {
            m.headers().put("X-Signature", "sig");
            return null;
        });
        beans.put("upper", (MessageHandler) (m, c) -> {
            m.setBody(JsonNodeFactory.instance.objectNode().put("name", m.body().get("name").stringValue().toUpperCase()));
            return null;
        });
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "s", 1, s -> new com.mhamzah.gateway.config.ConfigRows.StepRow(s.id(), s.flowId(), s.name(),
                s.stepOrder(), s.targetSystem(), s.httpMethod(), s.pathTemplate(), null, null, "STOP", null, null,
                "signer", "upper", null, true));
        rows.responseRule(flow, "$.name", "$.steps.s.body.name");
        downstream.ok("/s", "{\"name\":\"budi\"}");

        var result = run("POST", "/f", "{}");

        assertThat(downstream.last("/s").headers()).containsEntry("X-Signature", "sig");
        assertThat(text(result.response(), "name")).isEqualTo("BUDI");
    }

    @Test
    void stepRequestHandlerSeesExactlyTheBodyThatIsSent() {
        java.util.List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        beans.put("capture", (MessageHandler) (m, c) -> {
            seen.add(String.valueOf(m.body()));
            return null;
        });
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "get", 1, s -> new com.mhamzah.gateway.config.ConfigRows.StepRow(s.id(), s.flowId(), s.name(),
                s.stepOrder(), s.targetSystem(), "GET", s.pathTemplate(), null, null, "STOP", null, null,
                "capture", null, null, true));
        var post = rows.step(flow, "post", 2, s -> new com.mhamzah.gateway.config.ConfigRows.StepRow(s.id(), s.flowId(),
                s.name(), s.stepOrder(), s.targetSystem(), "POST", s.pathTemplate(), null, null, "STOP", null, null,
                "capture", null, null, true));
        rows.stepRule(post, "$.a", "$.request.body.a");
        downstream.ok("/get", "{}").ok("/post", "{}");

        run("POST", "/f", "{\"a\":1}");

        assertThat(seen).containsExactly("null", "{\"a\":1}");   // GET sends no body, so the handler sees none
        assertThat(downstream.last("/get").body()).isNull();
        assertThat(downstream.last("/post").body().toString()).isEqualTo("{\"a\":1}");
    }

    @Test
    void throwingErrorHandlerFallsBackToDefault() {
        beans.put("broken", (ErrorHandler) (e, c) -> {
            throw new IllegalStateException("boom");
        });
        var flow = rows.flow("F", "POST", "/f", f -> f.withHandlers(null, null, "broken"));
        rows.step(flow, "s", 1);
        downstream.on("/s", r -> new DownstreamResponse(500, Map.of(), "{}"));

        var result = run("POST", "/f", "{}");

        assertThat(result.response().status()).isEqualTo(502);
        assertThat(text(result.response(), "errorCode")).isEqualTo("GW-502-DOWNSTREAM");
    }

    @Test
    void requiredRequestFieldMissingIs400() {
        var flow = rows.flow("F", "POST", "/f");
        var step = rows.step(flow, "s", 1);
        rows.rule(new RuleRow(500, flow.id(), step.id(), "STEP_REQUEST", 1, "BODY", "$.acc",
                "$.request.body.account", null, null, null, null, null, true));
        var result = run("POST", "/f", "{}");
        assertThat(result.response().status()).isEqualTo(400);
        assertThat(text(result.response(), "errorCode")).isEqualTo("GW-400-MAPPING");
    }

    @Test
    void auditRecordCapturesTransactionAndSteps() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "s", 1);
        rows.responseRule(flow, "$.ok", "$.steps.s.body.ok");
        downstream.ok("/s", "{\"ok\":true}");

        var audit = run("POST", "/f", "{\"pin\":\"1\"}").audit();

        assertThat(audit.correlationId()).isEqualTo("corr-1");
        assertThat(audit.flowCode()).isEqualTo("F");
        assertThat(audit.clientStatus()).isEqualTo(200);
        assertThat(audit.requestPayload().toString()).isEqualTo("{\"pin\":\"1\"}");
        assertThat(audit.responsePayload().toString()).isEqualTo("{\"ok\":true}");
        assertThat(audit.steps()).singleElement().satisfies(s -> {
            assertThat(s.outcome()).isEqualTo("SUCCESS");
            assertThat(s.httpStatus()).isEqualTo(200);
            assertThat(s.url()).isEqualTo("http://core/s");
            assertThat(s.targetSystem()).isEqualTo("CORE");
        });
    }

    @Test
    void jsonStepSendsJsonContentTypeAndAccept() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "s", 1);
        downstream.ok("/s", "{}");

        run("POST", "/f", "{}");

        assertThat(downstream.last("/s").headers()).containsEntry("Content-Type", "application/json")
                .containsEntry("Accept", "application/json");
    }

    @Test
    void xmlStepSendsXmlAndMapsTheXmlResponse() {
        beans.put("xmlCodec", new XmlCodec());
        var flow = rows.flow("F", "POST", "/f");
        var step = rows.step(flow, "inq", 1, s -> s.withBodyCodec("xmlCodec"));
        rows.stepRule(step, "$.InquiryRequest.accountNo", "$.request.body.account");
        rows.responseRule(flow, "$.name", "$.steps.inq.body.InquiryResponse.name");
        downstream.on("/inq", r -> new DownstreamResponse(200, Map.of("content-type", "application/xml"),
                "<InquiryResponse><name>BUDI</name></InquiryResponse>"));

        var result = run("POST", "/f", "{\"account\":\"123\"}");

        assertThat(text(result.response(), "name")).isEqualTo("BUDI");
        var sent = downstream.last("/inq");
        assertThat(sent.body()).isEqualTo(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><InquiryRequest><accountNo>123</accountNo></InquiryRequest>");
        assertThat(sent.headers()).containsEntry("Content-Type", "application/xml; charset=UTF-8")
                .containsEntry("Accept", "application/xml, text/xml");
        // the audit trail keeps the JSON form, so field masking still applies
        assertThat(result.audit().steps()).singleElement().satisfies(s -> {
            assertThat(s.requestPayload().toString()).isEqualTo("{\"InquiryRequest\":{\"accountNo\":\"123\"}}");
            assertThat(s.responsePayload().toString()).isEqualTo("{\"InquiryResponse\":{\"name\":\"BUDI\"}}");
        });
    }

    @Test
    void soapFaultOnErrorStatusIsDecodedForErrorHandling() {
        beans.put("soapCodec", SoapCodec.soap11());
        var flow = rows.flow("F", "POST", "/f");
        var step = rows.step(flow, "s", 1, s -> s.withBodyCodec("soapCodec"));
        rows.rule(flow.id(), step.id(), "STEP_REQUEST", "HEADER", "SOAPAction", null, "\"urn:bank/Inquiry\"");
        rows.stepRule(step, "$.Inquiry.no", "$.request.body.no");
        downstream.on("/s", r -> new DownstreamResponse(500, Map.of(), """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><soap:Fault>
                <faultcode>soap:Server</faultcode><faultstring>Account not found</faultstring>
                </soap:Fault></soap:Body></soap:Envelope>"""));

        var result = run("POST", "/f", "{\"no\":\"1\"}");

        assertThat(downstream.last("/s").headers()).containsEntry("SOAPAction", "urn:bank/Inquiry")
                .containsEntry("Content-Type", "text/xml; charset=UTF-8");
        assertThat(downstream.last("/s").body()).contains("<soapenv:Body><Inquiry><no>1</no></Inquiry></soapenv:Body>");
        assertThat(result.audit().errorType()).isEqualTo("DOWNSTREAM_HTTP_ERROR");
        assertThat(result.audit().steps().getFirst().responsePayload().get("Fault").get("faultstring").asString())
                .isEqualTo("Account not found");
    }

    @Test
    void undecodableResponseIsAnInvalidResponse() {
        beans.put("xmlCodec", new XmlCodec());
        var flow = rows.flow("F", "POST", "/f");
        var step = rows.step(flow, "s", 1, s -> s.withBodyCodec("xmlCodec"));
        rows.stepRule(step, "$.R.a", "$.request.body.a");
        downstream.ok("/s", "{\"json\":\"not xml\"}");

        var result = run("POST", "/f", "{\"a\":1}");

        assertThat(result.audit().errorType()).isEqualTo("DOWNSTREAM_INVALID_RESPONSE");
        assertThat(result.audit().steps().getFirst().responsePayload().asString()).isEqualTo("{\"json\":\"not xml\"}");
    }

    @Test
    void bodyThatCannotBeEncodedIsAMappingError() {
        beans.put("xmlCodec", new XmlCodec());
        var flow = rows.flow("F", "POST", "/f");
        var step = rows.step(flow, "s", 1, s -> s.withBodyCodec("xmlCodec"));
        rows.stepRule(step, "$.a", "$.request.body.a");
        rows.stepRule(step, "$.b", "$.request.body.a");

        var result = run("POST", "/f", "{\"a\":1}");

        assertThat(result.audit().errorType()).isEqualTo("MAPPING_ERROR");
        assertThat(downstream.requests).isEmpty();
    }

    @Test
    void soapFaultWithHttp200IsCaughtBySuccessExpr() {
        beans.put("soapCodec", SoapCodec.soap11());
        var flow = rows.flow("F", "POST", "/f");
        var ok = rows.step(flow, "ok", 1, s -> s.withBodyCodec("soapCodec").withSuccess("${steps.ok.body.Fault} == null"));
        var bad = rows.step(flow, "bad", 2, s -> s.withBodyCodec("soapCodec").withSuccess("${steps.bad.body.Fault} == null"));
        rows.stepRule(ok, "$.Ping", "$.request.body.a");
        rows.stepRule(bad, "$.Ping", "$.request.body.a");
        String envelope = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>%s</s:Body></s:Envelope>";
        downstream.on("/ok", r -> new DownstreamResponse(200, Map.of(), envelope.formatted("<Pong>1</Pong>")));
        downstream.on("/bad", r -> new DownstreamResponse(200, Map.of(),
                envelope.formatted("<s:Fault><faultcode>s:Client</faultcode><faultstring>no</faultstring></s:Fault>")));

        var result = run("POST", "/f", "{\"a\":1}");

        assertThat(result.audit().errorType()).isEqualTo("DOWNSTREAM_BUSINESS_ERROR");
        assertThat(result.audit().steps()).extracting(s -> s.stepName() + "=" + s.outcome())
                .containsExactly("ok=SUCCESS", "bad=FAILED");
    }
}
