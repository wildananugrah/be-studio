package com.mhamzah.gateway.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mhamzah.gateway.codec.SoapCodec;
import com.mhamzah.gateway.codec.XmlCodec;
import com.mhamzah.gateway.config.ConfigCompiler;
import com.mhamzah.gateway.config.ConfigRows.RuleRow;
import com.mhamzah.gateway.config.ConfigValidationException;
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
    void listResponseFromDownstreamIsAnArrayForTheClient() {
        rows.schema("LIST", "{\"type\":\"array\",\"items\":{\"type\":\"object\",\"required\":[\"id\"]}}");
        var flow = rows.flow("F", "POST", "/f", f -> f.withSchemas(null, "LIST"));
        rows.step(flow, "list", 1, s -> s);
        rows.responseRule(flow, "$[*].id", "$.steps.list.body[*].guaranteeId");
        downstream.ok("/list", "[{\"guaranteeId\":\"G1\"},{\"guaranteeId\":\"G2\"}]");

        var result = run("POST", "/f", "{}");

        assertThat(result.response().status()).as(String.valueOf(result.response().body())).isEqualTo(200);
        assertThat(result.response().body().toString()).isEqualTo("[{\"id\":\"G1\"},{\"id\":\"G2\"}]");
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
        // the audit trail keeps the XML as it was on the wire (masked as text when written)
        assertThat(result.audit().steps()).singleElement().satisfies(s -> {
            assertThat(s.requestPayload().asString()).isEqualTo(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><InquiryRequest><accountNo>123</accountNo></InquiryRequest>");
            assertThat(s.responsePayload().asString()).contains("<InquiryResponse>", "BUDI");
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
        assertThat(result.audit().steps().getFirst().responsePayload().asString())
                .contains("<soap:Fault>", "<faultstring>Account not found</faultstring>");
    }

    private static final String ENVELOPE = """
            <soapenv:Envelope
            	xmlns:q0="http://service.example.com/core"
            	xmlns:bo="http://service.example.com/core/bo"
            	xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
            	xmlns:xsd="http://www.w3.org/2001/XMLSchema"
            	xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                <soapenv:Body>
                    <q0:transaction>
                        <request>
                            <systemId>API</systemId>
                            <content xsi:type="bo:AccountShortInquiryReq">
                                <accountNum>${request.path.accountNo}</accountNum>
                                <options>${request.query.option:01}</options>
                                <padded>${body.padded}</padded>
                            </content>
                        </request>
                    </q0:transaction>
                </soapenv:Body>
            </soapenv:Envelope>""";

    @Test
    void bodyTemplateIsSentAsWrittenWithEscapedValuesAndTheSoapResponseDecoded() {
        beans.put("soapCodec", SoapCodec.soap11());
        var flow = rows.flow("F", "POST", "/f/{accountNo}");
        var step = rows.step(flow, "s", 1, s -> s.withBodyTemplate(ENVELOPE));
        // a BODY rule still works for ${body.x}: here with a converter
        rows.rule(new RuleRow(900, flow.id(), step.id(), "STEP_REQUEST", 1, "BODY", "$.padded",
                "$.request.path.accountNo", null, null, "PAD_LEFT:12:0", null, null, false));
        rows.responseRule(flow, "$.name", "$.steps.s.body.transactionResponse.response.content.accountName");
        downstream.ok("/s", """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"><soapenv:Body>
                <q0:transactionResponse xmlns:q0="http://service.example.com/core"><response><content>
                <accountName>BUDI</accountName></content></response></q0:transactionResponse>
                </soapenv:Body></soapenv:Envelope>""");

        var result = run("POST", "/f/78<&1", "{}");

        String sent = downstream.last("/s").body();
        assertThat(sent).startsWith("<soapenv:Envelope\n\txmlns:q0=\"http://service.example.com/core\"")
                .contains("xmlns:bo=\"http://service.example.com/core/bo\"", "<content xsi:type=\"bo:AccountShortInquiryReq\">",
                        "<accountNum>78&lt;&amp;1</accountNum>", "<options>01</options>", "<padded>000000078<&1</padded>".replace("<&", "&lt;&amp;"));
        assertThat(downstream.last("/s").headers()).containsEntry("Content-Type", "text/xml; charset=UTF-8");
        assertThat(result.response().status()).as(String.valueOf(result.response().body())).isEqualTo(200);
        assertThat(text(result.response(), "name")).isEqualTo("BUDI");
        // the audit trail shows exactly what was sent
        assertThat(result.audit().steps().getFirst().requestPayload().asString()).isEqualTo(sent);
    }

    // ---- branching: next_on_success / next_on_failure

    private static java.util.List<String> outcomes(ExecutionResult result) {
        return result.audit().steps().stream().map(x -> x.stepName() + "=" + x.outcome()).toList();
    }

    @Test
    void failureJumpsToAFallbackAndTheStepsInBetweenAreSkipped() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "primary", 1, s -> s.withNext(null, "fallback"));
        rows.step(flow, "enrich", 2);
        rows.step(flow, "fallback", 3);
        rows.step(flow, "after", 4);
        rows.responseRule(flow, "$.primary", "$.steps.primary.outcome");
        rows.responseRule(flow, "$.enrich", "$.steps.enrich.outcome");
        rows.responseRule(flow, "$.fallback", "$.steps.fallback.outcome");
        downstream.on("/primary", r -> new DownstreamResponse(503, Map.of(), ""));
        for (String ok : java.util.List.of("/enrich", "/fallback", "/after")) {
            downstream.on(ok, r -> new DownstreamResponse(200, Map.of(), "{}"));
        }

        var result = run("POST", "/f", "{}");

        assertThat(result.response().status()).isEqualTo(200);
        assertThat(result.response().body().toString())
                .isEqualTo("{\"primary\":\"FAILED\",\"enrich\":\"SKIPPED\",\"fallback\":\"SUCCESS\"}");
        assertThat(outcomes(result)).containsExactly("primary=FAILED", "enrich=SKIPPED", "fallback=SUCCESS", "after=SUCCESS");
        assertThat(downstream.calls("/enrich")).isZero();
    }

    @Test
    void successJumpsOverTheFailurePathAndEndStopsTheFlow() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "debit", 1, s -> s.withNext("notify", "reversal"));
        rows.step(flow, "reversal", 2, s -> s.withNext("END", null));
        rows.step(flow, "notify", 3);
        downstream.on("/debit", r -> new DownstreamResponse(200, Map.of(), "{}"));
        downstream.on("/reversal", r -> new DownstreamResponse(200, Map.of(), "{}"));
        downstream.on("/notify", r -> new DownstreamResponse(200, Map.of(), "{}"));

        assertThat(outcomes(run("POST", "/f", "{}")))
                .containsExactly("debit=SUCCESS", "reversal=SKIPPED", "notify=SUCCESS");

        downstream.on("/debit", r -> new DownstreamResponse(500, Map.of(), "{}"));
        var failed = run("POST", "/f", "{}");
        assertThat(failed.response().status()).isEqualTo(200);
        assertThat(outcomes(failed)).containsExactly("debit=FAILED", "reversal=SUCCESS", "notify=SKIPPED");
    }

    @Test
    void failEndsTheFailurePathWithTheOriginalError() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "debit", 1, s -> s.withSuccess("${steps.debit.body.code} == '00'").withNext(null, "reversal"));
        rows.step(flow, "reversal", 2, s -> s.withNext("FAIL", null));
        rows.step(flow, "notify", 3);
        downstream.on("/debit", r -> new DownstreamResponse(200, Map.of(), "{\"code\":\"51\"}"));
        downstream.on("/reversal", r -> new DownstreamResponse(200, Map.of(), "{}"));
        downstream.on("/notify", r -> new DownstreamResponse(200, Map.of(), "{}"));

        var result = run("POST", "/f", "{}");

        assertThat(result.response().status()).isEqualTo(422);
        assertThat(text(result.response(), "errorCode")).isEqualTo("GW-422-BUSINESS");
        assertThat(text(result.response(), "step")).isEqualTo("debit");
        assertThat(outcomes(result)).containsExactly("debit=FAILED", "reversal=SUCCESS");
        assertThat(downstream.calls("/notify")).isZero();
    }

    @Test
    void aConditionSkippedBranchingStepFollowsTheNormalOrder() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "a", 1, s -> s.withCondition("${request.body.go} == true").withNext("END", null));
        rows.step(flow, "b", 2);
        downstream.on("/a", r -> new DownstreamResponse(200, Map.of(), "{}"));
        downstream.on("/b", r -> new DownstreamResponse(200, Map.of(), "{}"));
        assertThat(outcomes(run("POST", "/f", "{\"go\":false}"))).containsExactly("a=SKIPPED", "b=SUCCESS");
        assertThat(outcomes(run("POST", "/f", "{\"go\":true}"))).containsExactly("a=SUCCESS", "b=SKIPPED");
    }

    @Test
    void statusRulePassesTheBackendStatusThrough() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "s", 1);
        rows.statusRule(flow, null, "$.steps.s.status", null, null);
        rows.responseRule(flow, "$.id", "$.steps.s.body.id");
        downstream.on("/s", r -> new DownstreamResponse(201, Map.of(), "{\"id\":\"A1\"}"));

        var result = run("POST", "/f", "{}");

        assertThat(result.response().status()).isEqualTo(201);
        assertThat(result.response().body().toString()).isEqualTo("{\"id\":\"A1\"}");
    }

    @Test
    void aFailedStepIsMappedByANamedResponseWithTheBackendStatus() {
        var flow = rows.flow("F", "GET", "/f");
        rows.step(flow, "s", 1, st -> st.withNext(null, "RESPOND:failed"));
        rows.response(flow, "failed", 502);
        rows.statusRule(flow, "failed", "$.steps.s.status", null, null);
        rows.responseRule(flow, "failed", "$.errorCode", "$.steps.s.body.code", null);
        rows.responseRule(flow, "failed", "$.errorMessage", "$.steps.s.body.message", null);
        downstream.on("/s", r -> new DownstreamResponse(404, Map.of(), "{\"code\":\"NF01\",\"message\":\"Account not found\"}"));

        var result = run("GET", "/f", "");

        assertThat(result.response().status()).isEqualTo(404);
        assertThat(result.response().body().toString())
                .isEqualTo("{\"errorCode\":\"NF01\",\"errorMessage\":\"Account not found\"}");
    }

    @Test
    void aLookupTranslatesTheStatusAndBadValuesAreMappingErrors() {
        var flow = rows.flow("F", "GET", "/f");
        rows.step(flow, "s", 1, st -> st.withNext(null, "RESPOND:failed"));
        rows.response(flow, "failed", 500);
        rows.lookup("HTTP_STATUS", "503", "502");
        rows.lookup("HTTP_STATUS", "*", "500");
        rows.statusRule(flow, "failed", "$.steps.s.status", null, "HTTP_STATUS");
        downstream.on("/s", r -> new DownstreamResponse(503, Map.of(), "{}"));
        assertThat(run("GET", "/f", "").response().status()).isEqualTo(502);

        var bad = rows.flow("B", "GET", "/b");
        rows.statusRule(bad, null, null, "\"abc\"", null);
        var result = run("GET", "/b", "");
        assertThat(result.response().status()).isEqualTo(500);
        assertThat(text(result.response(), "errorCode")).isEqualTo("GW-500-MAPPING");
    }

    @Test
    void statusRulesAreOnlyForTheClientResponse() {
        var flow = rows.flow("F", "POST", "/f");
        var step = rows.step(flow, "s", 1);
        rows.rule(flow.id(), step.id(), "STEP_REQUEST", "STATUS", "status", "$.request.body.code", null);
        assertThatThrownBy(this::registry).isInstanceOfSatisfying(ConfigValidationException.class, e -> assertThat(e.errors())
                .anySatisfy(m -> assertThat(m).contains("target_type STATUS is not allowed for STEP_REQUEST")));
    }

    @Test
    void errorHandlerRespondMapsEveryUnroutedFailure() {
        var flow = rows.flow("F", "POST", "/f", f -> f.withHandlers(null, null, "RESPOND:error"));
        rows.step(flow, "s", 1);
        rows.response(flow, "error", 500);
        rows.statusRule(flow, "error", "$.error.status", null, null);
        rows.responseRule(flow, "error", "$.result.code", "$.error.code", null);
        rows.responseRule(flow, "error", "$.result.type", "$.error.type", null);
        rows.responseRule(flow, "error", "$.result.failedStep", "$.error.step", null);
        rows.responseRule(flow, "error", "$.result.backendMessage", "$.steps.s.body.message", null);
        rows.responseRule(flow, "error", "$.traceId", "$.correlationId", null);
        downstream.on("/s", r -> new DownstreamResponse(500, Map.of(), "{\"message\":\"core exploded\"}"));

        var result = run("POST", "/f", "{}");

        assertThat(result.response().status()).isEqualTo(502);
        assertThat(result.response().body().toString()).isEqualTo("{\"result\":{\"code\":\"GW-502-DOWNSTREAM\","
                + "\"type\":\"DOWNSTREAM_HTTP_ERROR\",\"failedStep\":\"s\",\"backendMessage\":\"core exploded\"},"
                + "\"traceId\":\"corr-1\"}");
    }

    @Test
    void errorResponseMapsSchemaDetailsAndFallsBackToTheStandardErrorWhenItFails() {
        rows.schema("REQ", "{\"type\":\"object\",\"required\":[\"amount\"]}");
        var flow = rows.flow("F", "POST", "/f", f -> f.withSchemas("REQ", null).withHandlers(null, null, "RESPOND:error"));
        rows.response(flow, "error", 422);
        rows.responseRule(flow, "error", "$.problems", "$.error.details", null);
        var invalid = run("POST", "/f", "{}");
        assertThat(invalid.response().status()).isEqualTo(422);
        assertThat(invalid.response().body().get("problems").get(0).asString()).contains("amount");

        // a STATUS rule that gives no HTTP status: the standard error is sent instead
        rows.statusRule(flow, "error", null, "\"oops\"", null);
        var fallback = run("POST", "/f", "{}");
        assertThat(fallback.response().status()).isEqualTo(400);
        assertThat(text(fallback.response(), "errorCode")).isEqualTo("GW-400-SCHEMA");
    }

    @Test
    void errorHandlerRespondMustNameAResponse() {
        rows.flow("F", "POST", "/f", f -> f.withHandlers(null, null, "RESPOND:nope"));
        assertThatThrownBy(this::registry).isInstanceOfSatisfying(ConfigValidationException.class, e -> assertThat(e.errors())
                .anySatisfy(m -> assertThat(m).contains("error_handler 'RESPOND:nope' is not a response of this flow")));
    }

    @Test
    void aBranchCanAnswerWithANamedResponseOfItsOwn() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "debit", 1, s -> s.withSuccess("${steps.debit.body.code} == '00'").withNext("notify", "reversal"));
        rows.step(flow, "reversal", 2, s -> s.withNext("RESPOND:reversed", null));
        rows.step(flow, "notify", 3);
        rows.rule(new com.mhamzah.gateway.config.ConfigRows.RuleRow(999, flow.id(), null, "FLOW_RESPONSE", 1, "BODY",
                "$.status", null, "DONE", null, null, null, null, false));
        rows.response(flow, "reversed", 409);
        rows.responseRule(flow, "reversed", "$.status", null, "REVERSED");
        rows.responseRule(flow, "reversed", "$.reason", "$.steps.debit.body.message", null);
        rows.responseRule(flow, "reversed", "$.reversalRef", "$.steps.reversal.body.ref", null);
        downstream.on("/debit", r -> new DownstreamResponse(200, Map.of(), "{\"code\":\"51\",\"message\":\"Insufficient funds\"}"));
        downstream.on("/reversal", r -> new DownstreamResponse(200, Map.of(), "{\"ref\":\"REV-1\"}"));
        downstream.on("/notify", r -> new DownstreamResponse(200, Map.of(), "{}"));

        var failed = run("POST", "/f", "{}");
        assertThat(failed.response().status()).isEqualTo(409);
        assertThat(failed.response().body().toString())
                .isEqualTo("{\"status\":\"REVERSED\",\"reason\":\"Insufficient funds\",\"reversalRef\":\"REV-1\"}");
        assertThat(outcomes(failed)).containsExactly("debit=FAILED", "reversal=SUCCESS", "notify=SKIPPED");

        downstream.on("/debit", r -> new DownstreamResponse(200, Map.of(), "{\"code\":\"00\"}"));
        var ok = run("POST", "/f", "{}");
        assertThat(ok.response().status()).isEqualTo(200);
        assertThat(ok.response().body().toString()).isEqualTo("{\"status\":\"DONE\"}");
    }

    @Test
    void namedResponseMistakesAreReportedOnReload() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "a", 1, s -> s.withNext("RESPOND:nope", null));
        rows.response(flow, "bad name", 200);
        rows.response(flow, "teapot", 999);
        rows.responseRule(flow, "ghost", "$.x", null, "1");
        assertThatThrownBy(this::registry).isInstanceOfSatisfying(ConfigValidationException.class, e -> assertThat(e.errors())
                .anySatisfy(m -> assertThat(m).contains("step 'a' next_on_success: 'nope' is not a response of this flow (teapot)"))
                .anySatisfy(m -> assertThat(m).contains("response name 'bad name' must start with a letter"))
                .anySatisfy(m -> assertThat(m).contains("response 'teapot': status 999 is not a valid HTTP status"))
                .anySatisfy(m -> assertThat(m).contains("response 'ghost' is not a response of this flow")));
    }

    // ---- transform steps: re-map what the flow has so far, for the steps after them

    private com.mhamzah.gateway.config.ConfigRows.StepRow transform(com.mhamzah.gateway.config.ConfigRows.FlowRow flow,
            String name, int order, java.util.function.UnaryOperator<com.mhamzah.gateway.config.ConfigRows.StepRow> more) {
        return rows.step(flow, name, order, s -> more.apply(s.withTarget("TRANSFORM").withMethod(null).withPathTemplate(null)));
    }

    @Test
    void aTransformRemapsTheRequestAndEarlierStepsForTheNextStep() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "account", 1);
        var t = transform(flow, "prepared", 2, s -> s);
        rows.stepRule(t, "$.debit.accountNo", "$.request.body.from");
        rows.stepRule(t, "$.debit.holder", "$.steps.account.body.acctName");
        rows.rule(new com.mhamzah.gateway.config.ConfigRows.RuleRow(900, flow.id(), t.id(), "STEP_REQUEST", 3, "BODY",
                "$.amount", "$.request.body.amount", null, null, "PAD_LEFT:10:0", null, null, true));
        rows.rule(new com.mhamzah.gateway.config.ConfigRows.RuleRow(901, flow.id(), t.id(), "STEP_REQUEST", 4, "BODY",
                "$.channel", null, "MOBILE", null, null, null, null, false));
        var pay = rows.step(flow, "pay", 3);
        rows.stepRule(pay, "$.payload", "$.steps.prepared.body");
        rows.responseRule(flow, "$.sent", "$.steps.prepared.body.amount");
        downstream.on("/account", r -> new DownstreamResponse(200, Map.of(), "{\"acctName\":\"BUDI\"}"));
        downstream.on("/pay", r -> new DownstreamResponse(200, Map.of(), "{}"));

        var result = run("POST", "/f", "{\"from\":\"1001\",\"amount\":150000}");

        assertThat(result.response().status()).isEqualTo(200);
        assertThat(downstream.last("/pay").body()).isEqualTo(
                "{\"payload\":{\"debit\":{\"accountNo\":\"1001\",\"holder\":\"BUDI\"},\"amount\":\"0000150000\",\"channel\":\"MOBILE\"}}");
        assertThat(result.response().body().toString()).isEqualTo("{\"sent\":\"0000150000\"}");
        // nothing was called for the transform; the audit shows what it built
        assertThat(downstream.requests).extracting(r -> r.path()).containsExactly("/account", "/pay");
        assertThat(result.audit().steps()).filteredOn(x -> x.stepName().equals("prepared")).singleElement().satisfies(x -> {
            assertThat(x.httpMethod()).isEqualTo("TRANSFORM");
            assertThat(x.outcome()).isEqualTo("SUCCESS");
            assertThat(x.responsePayload().get("channel").asString()).isEqualTo("MOBILE");
        });
    }

    @Test
    void aTransformCanBeWrittenAsAJsonTemplate() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "account", 1);
        var t = transform(flow, "doc", 2, s -> s.withBodyTemplate("""
                {"customer": {"id": "${request.body.from}", "name": "${steps.account.body.acctName}"},
                 "amount": ${body.amount}, "tags": ${request.body.tags:[]}, "channel": "${request.headers.x-channel:API}"}"""));
        rows.rule(new com.mhamzah.gateway.config.ConfigRows.RuleRow(910, flow.id(), t.id(), "STEP_REQUEST", 1, "BODY",
                "$.amount", "$.request.body.amount", null, null, "DECIMAL_SCALE:2", null, null, false));
        rows.responseRule(flow, "$.doc", "$.steps.doc.body");
        downstream.on("/account", r -> new DownstreamResponse(200, Map.of(), "{\"acctName\":\"BUDI \\\"B\\\"\"}"));

        var result = run("POST", "/f", "{\"from\":\"1001\",\"amount\":15,\"tags\":[\"a\",\"b\"]}");

        assertThat(result.response().status()).isEqualTo(200);
        assertThat(result.response().body().toString()).isEqualTo("{\"doc\":{\"customer\":{\"id\":\"1001\",\"name\":\"BUDI \\\"B\\\"\"},"
                + "\"amount\":15.00,\"tags\":[\"a\",\"b\"],\"channel\":\"API\"}}");
    }

    @Test
    void aTransformCanCheckWhatItBuilt() {
        var flow = rows.flow("F", "POST", "/f");
        var t = transform(flow, "check", 1, s -> s.withSuccess("${steps.check.body.limit} > 100"));
        rows.stepRule(t, "$.limit", "$.request.body.limit");
        rows.schema("CHECKED", "{\"type\":\"object\",\"required\":[\"limit\"],\"properties\":{\"limit\":{\"type\":\"integer\"}}}");
        var s2 = transform(flow, "typed", 2, s -> new com.mhamzah.gateway.config.ConfigRows.StepRow(s.id(), s.flowId(), s.name(),
                s.stepOrder(), s.targetSystem(), null, null, null, null, "STOP", null, "CHECKED", null, null, null, true));
        rows.stepRule(s2, "$.limit", "$.request.body.limit");

        assertThat(run("POST", "/f", "{\"limit\":500}").response().status()).isEqualTo(200);
        var small = run("POST", "/f", "{\"limit\":5}");
        assertThat(small.response().status()).isEqualTo(422);
        assertThat(text(small.response(), "step")).isEqualTo("check");
        var wrongType = run("POST", "/f", "{\"limit\":\"500x\"}");
        assertThat(text(wrongType.response(), "step")).isIn("check", "typed");
    }

    @Test
    void transformMistakesAreReportedOnReload() {
        var flow = rows.flow("F", "POST", "/f");
        var t = transform(flow, "t", 1, s -> s.withBodyTemplate("<a/>"));
        rows.rule(t.flowId(), t.id(), "STEP_REQUEST", "HEADER", "X-A", "$.request.body.a", null);
        rows.target("TRANSFORM", "http://somewhere");
        assertThatThrownBy(this::registry).isInstanceOfSatisfying(ConfigValidationException.class, e -> assertThat(e.errors())
                .anySatisfy(m -> assertThat(m).contains("step 't': body_template of a transform step must be JSON"))
                .anySatisfy(m -> assertThat(m).contains("target_type HEADER is not allowed for a transform step"))
                .anySatisfy(m -> assertThat(m).contains("'TRANSFORM' is reserved for transform steps")));
    }

    // ---- condition steps: check values and route

    private com.mhamzah.gateway.config.ConfigRows.StepRow condition(com.mhamzah.gateway.config.ConfigRows.FlowRow flow,
            String name, int order, String cases, java.util.function.UnaryOperator<com.mhamzah.gateway.config.ConfigRows.StepRow> more) {
        return rows.step(flow, name, order, s -> more.apply(s.withTarget("CONDITION").withMethod(null).withPathTemplate(null).withCases(cases)));
    }

    @Test
    void aConditionValidatesAndRoutesToTheFirstMatchingCase() {
        var flow = rows.flow("F", "POST", "/f");
        condition(flow, "check", 1, """
                [{"when": "${request.body.amount} <= 0", "then": "RESPOND:invalid", "label": "not positive"},
                 {"when": "${request.body.amount} > 50000000", "then": "approval", "label": "large"}]""", s -> s.withNext("post", null));
        rows.step(flow, "approval", 2, s -> s.withNext("END", null));
        rows.step(flow, "post", 3);
        rows.responseRule(flow, "$.matched", "$.steps.check.body.matched");
        rows.response(flow, "invalid", 400);
        rows.responseRule(flow, "invalid", "$.error", null, "amount must be positive");
        downstream.on("/approval", r -> new DownstreamResponse(200, Map.of(), "{}"));
        downstream.on("/post", r -> new DownstreamResponse(200, Map.of(), "{}"));

        var small = run("POST", "/f", "{\"amount\":10}");
        assertThat(small.response().body().toString()).isEqualTo("{\"matched\":\"otherwise\"}");
        assertThat(outcomes(small)).containsExactly("check=SUCCESS", "approval=SKIPPED", "post=SUCCESS");

        var large = run("POST", "/f", "{\"amount\":60000000}");
        assertThat(large.response().body().toString()).isEqualTo("{\"matched\":\"large\"}");
        assertThat(outcomes(large)).containsExactly("check=SUCCESS", "approval=SUCCESS", "post=SKIPPED");

        var invalid = run("POST", "/f", "{\"amount\":-5}");
        assertThat(invalid.response().status()).isEqualTo(400);
        assertThat(invalid.response().body().toString()).isEqualTo("{\"error\":\"amount must be positive\"}");
        assertThat(invalid.audit().steps()).first().satisfies(x -> {
            assertThat(x.httpMethod()).isEqualTo("CONDITION");
            assertThat(x.responsePayload().get("matched").asString()).isEqualTo("not positive");
        });
    }

    @Test
    void conditionMistakesAreReportedOnReload() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "a", 1);
        var c = condition(flow, "c", 2, "[{\"when\": \"${request.body.x} == 1\", \"then\": \"a\"}, {\"when\": \"${request.body.x} == 2\"},"
                + " {\"when\": \"${steps.later.body.y} == 1\", \"then\": \"END\"}]", s -> s);
        rows.stepRule(c, "$.x", "$.request.body.x");
        condition(flow, "d", 3, "{not json", s -> s);
        condition(flow, "e", 4, "[]", s -> s);
        rows.step(flow, "later", 5);
        assertThatThrownBy(this::registry).isInstanceOfSatisfying(ConfigValidationException.class, e -> assertThat(e.errors())
                .anySatisfy(m -> assertThat(m).contains("step 'c' case 1 then: step 'a' (step_order 1) does not come after"))
                .anySatisfy(m -> assertThat(m).contains("step 'c' case 2: needs both when (an expression) and then"))
                .anySatisfy(m -> assertThat(m).contains("step 'c' case 3 when: references step 'later'"))
                .anySatisfy(m -> assertThat(m).contains("step 'c': a condition step has no mapping rules"))
                .anySatisfy(m -> assertThat(m).contains("step 'd': cases is not valid JSON"))
                .anySatisfy(m -> assertThat(m).contains("step 'e': a condition step needs at least one case")));
    }

    @Test
    void branchingMistakesAreReportedOnReload() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "a", 1, s -> s.withNext("nope", "FAIL"));
        rows.step(flow, "b", 2, s -> s.withNext("a", null));
        rows.step(flow, "c", 3, s -> s.withNext(null, "END"));
        rows.step(flow, "d", 3);
        assertThatThrownBy(this::registry).isInstanceOfSatisfying(ConfigValidationException.class, e -> assertThat(e.errors())
                .anySatisfy(m -> assertThat(m).contains("step 'a' next_on_success: 'nope' is not END, FAIL, RESPOND:<response> or an enabled step"))
                .anySatisfy(m -> assertThat(m).contains("step 'a' next_on_failure: FAIL is only for next_on_success"))
                .anySatisfy(m -> assertThat(m).contains("step 'b' next_on_success: step 'a' (step_order 1) does not come after"))
                .anySatisfy(m -> assertThat(m).contains("step 'c': a step that chooses the next step")));
    }

    @Test
    void badPlaceholdersAndStepsThatHaveNotRunAreReportedOnReload() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "s", 1, s -> s.withBodyTemplate("<a>${request.body.x</a>"));
        rows.step(flow, "t", 2, s -> s.withBodyTemplate("{\"v\": \"${steps.later.body.v}\", \"w\": ${nope.x}}"));
        rows.step(flow, "later", 3);
        assertThatThrownBy(this::registry).isInstanceOfSatisfying(ConfigValidationException.class, e -> assertThat(e.errors())
                .anySatisfy(m -> assertThat(m).contains("step 's': body_template: unclosed ${"))
                .anySatisfy(m -> assertThat(m).contains("step 't': body_template: placeholder ${nope.x} must start with")));
    }

    @Test
    void jsonTemplateEscapesInsideStrings() {
        var flow = rows.flow("F", "POST", "/f");
        rows.step(flow, "s", 1, s -> s.withBodyTemplate("{\"note\": \"${request.body.note}\", \"amount\": ${request.body.amount}}"));
        downstream.ok("/s", "{}");

        run("POST", "/f", "{\"note\":\"say \\\"hi\\\"\\n\",\"amount\":150.5}");

        assertThat(downstream.last("/s").body()).isEqualTo("{\"note\": \"say \\\"hi\\\"\\n\", \"amount\": 150.5}");
        assertThat(downstream.last("/s").headers()).containsEntry("Content-Type", "application/json");
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
