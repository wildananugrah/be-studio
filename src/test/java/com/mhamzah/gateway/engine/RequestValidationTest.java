package com.mhamzah.gateway.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mhamzah.gateway.config.ConfigCompiler;
import com.mhamzah.gateway.config.ConfigRows.FlowRow;
import com.mhamzah.gateway.config.ConfigValidationException;
import com.mhamzah.gateway.config.FlowRegistry;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.config.Rows;
import com.mhamzah.gateway.extension.DefaultErrorHandler;
import com.mhamzah.gateway.extension.GatewayResponse;
import com.mhamzah.gateway.mapping.MappingEngine;
import java.time.Duration;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** gw_flow.request_validation: checks before or after the schema, the schema picked by a field, first error only. */
class RequestValidationTest {

    private final FakeDownstream downstream = new FakeDownstream();
    private final FlowExecutor executor = new FlowExecutor(downstream, new MappingEngine(), new DefaultErrorHandler());
    private final Rows rows = new Rows();

    /** purpose must be new / modify / delete; new → REQ_NEW (name, amount), modify → REQ_MODIFY (id, name). */
    private static final String PURPOSE = """
            {"order": "%s",
             "checks": [
               {"field": "$.request.body.purpose", "check": "ENUM", "values": ["new", "modify", "delete"],
                "message": "purpose must be new, modify or delete", "code": "PURPOSE_INVALID"}],
             "schemaBy": {"field": "$.request.body.purpose",
                          "schemas": {"new": "REQ_NEW", "modify": "REQ_MODIFY"}}}""";

    @AfterEach
    void tearDown() {
        executor.close();
    }

    private void schemas() {
        rows.schema("REQ_NEW", """
                {"type": "object", "required": ["name", "amount"],
                 "properties": {"name": {"type": "string"}, "amount": {"type": "number"}}}""");
        rows.schema("REQ_MODIFY", "{\"type\": \"object\", \"required\": [\"id\", \"name\"]}");
        rows.schema("REQ_ANY", "{\"type\": \"object\", \"required\": [\"channel\"]}");
    }

    /** A flow at POST /f answering {"ok": true} without calling anything. */
    private FlowRow flow(UnaryOperator<FlowRow> customize) {
        FlowRow f = rows.flow("F", "POST", "/f", customize);
        rows.rule(f.id(), null, "FLOW_RESPONSE", "BODY", "$.ok", null, "true");
        return f;
    }

    private FlowRegistry registry() {
        ConfigCompiler compiler = new ConfigCompiler(
                new ConfigCompiler.HandlerLookup() {
                    @Override
                    public <T> T find(String name, Class<T> type) {
                        Object b = "defaultErrorHandler".equals(name) ? new DefaultErrorHandler() : null;
                        return type.isInstance(b) ? type.cast(b) : null;
                    }
                },
                Map.of("CORE", new GatewayProperties.TargetSystem("http://core", 1000, 2000, Map.of(), null)),
                UnaryOperator.identity(), Duration.ofSeconds(5), Duration.ofSeconds(2));
        return compiler.compile(rows.build());
    }

    private GatewayResponse post(String body) {
        return post(body, Map.of());
    }

    private GatewayResponse post(String body, Map<String, String> headers) {
        FlowRegistry reg = registry();
        var match = reg.match(HttpMethod.POST, "/f").orElseThrow();
        return executor.execute(reg, match, new InboundRequest(HttpMethod.POST, "/f", headers, Map.of(), body), "corr-1")
                .response();
    }

    private static String text(GatewayResponse r, String field) {
        return r.body().path(field).isMissingNode() ? null : r.body().path(field).asString();
    }

    @Test
    void theFieldIsCheckedBeforeTheSchemaItPicks() {
        schemas();
        flow(f -> f.withRequestValidation(PURPOSE.formatted("CHECKS_FIRST")));

        GatewayResponse r = post("{\"purpose\": \"new2\", \"name\": \"a\"}");

        assertThat(r.status()).isEqualTo(400);
        assertThat(text(r, "errorCode")).isEqualTo("GW-400-VALIDATION");
        assertThat(r.body().get("details").toString()).isEqualTo("[\"purpose must be new, modify or delete\"]");
        assertThat(text(r, "field")).isEqualTo("purpose");
        assertThat(text(r, "validationCode")).isEqualTo("PURPOSE_INVALID");
    }

    @Test
    void theValueOfTheFieldPicksTheSchema() {
        schemas();
        flow(f -> f.withRequestValidation(PURPOSE.formatted("CHECKS_FIRST")));

        assertThat(post("{\"purpose\": \"new\", \"name\": \"a\", \"amount\": 1}").status()).isEqualTo(200);
        assertThat(post("{\"purpose\": \"modify\", \"id\": \"7\", \"name\": \"a\"}").status()).isEqualTo(200);

        GatewayResponse noAmount = post("{\"purpose\": \"new\", \"name\": \"a\"}");
        assertThat(text(noAmount, "errorCode")).isEqualTo("GW-400-SCHEMA");
        assertThat(noAmount.body().get("details").toString()).contains("amount");

        GatewayResponse noId = post("{\"purpose\": \"modify\", \"name\": \"a\"}");
        assertThat(noId.body().get("details").toString()).contains("'id'");
    }

    @Test
    void onlyTheFirstSchemaErrorIsReported() {
        schemas();
        flow(f -> f.withRequestValidation(PURPOSE.formatted("CHECKS_FIRST")));

        // no name, no amount, both wrong: one detail
        GatewayResponse r = post("{\"purpose\": \"new\"}");

        assertThat(text(r, "errorCode")).isEqualTo("GW-400-SCHEMA");
        assertThat(r.body().get("details")).hasSize(1);
    }

    @Test
    void onlyTheFirstFailingCheckIsReported() {
        flow(f -> f.withRequestValidation("""
                {"checks": [
                  {"field": "$.request.body.a", "check": "REQUIRED", "message": "a first"},
                  {"field": "$.request.body.b", "check": "REQUIRED", "message": "b second"}]}"""));

        GatewayResponse r = post("{}");

        assertThat(r.body().get("details").toString()).isEqualTo("[\"a first\"]");
    }

    @Test
    void schemaFirstRunsTheSchemaBeforeTheChecks() {
        schemas();
        // new2 picks no schema, so the flow's request schema (REQ_ANY: channel) is checked first
        flow(f -> f.withSchemas("REQ_ANY", null).withRequestValidation(PURPOSE.formatted("SCHEMA_FIRST")));

        GatewayResponse schemaFails = post("{\"purpose\": \"new2\"}");
        assertThat(text(schemaFails, "errorCode")).isEqualTo("GW-400-SCHEMA");

        GatewayResponse checkFails = post("{\"purpose\": \"new2\", \"channel\": \"MB\"}");
        assertThat(text(checkFails, "errorCode")).isEqualTo("GW-400-VALIDATION");
        assertThat(text(checkFails, "validationCode")).isEqualTo("PURPOSE_INVALID");
    }

    @Test
    void aValueWithoutSchemaAndNoFallbackFails() {
        schemas();
        flow(f -> f.withRequestValidation("""
                {"schemaBy": {"field": "$.request.body.purpose", "schemas": {"new": "REQ_NEW"}}}"""));

        GatewayResponse r = post("{\"purpose\": \"delete\"}");

        assertThat(text(r, "errorCode")).isEqualTo("GW-400-VALIDATION");
        assertThat(r.body().get("details").get(0).asString()).isEqualTo("purpose must be one of [new] (got 'delete')");

        GatewayResponse missing = post("{}");
        assertThat(missing.body().get("details").get(0).asString()).isEqualTo("purpose is required");
    }

    @Test
    void schemaValidationOffSkipsThePickedSchemaButNotTheChecks() {
        schemas();
        flow(f -> f.withSchemaValidation("NONE").withRequestValidation(PURPOSE.formatted("CHECKS_FIRST")));

        assertThat(post("{\"purpose\": \"new\"}").status()).isEqualTo(200);
        assertThat(text(post("{\"purpose\": \"x\"}"), "errorCode")).isEqualTo("GW-400-VALIDATION");
    }

    @Test
    void everyKindOfCheck() {
        rows.lookup("CURRENCY", "IDR", "Rupiah");
        rows.lookup("CURRENCY", "USD", "Dollar");
        flow(f -> f.withRequestValidation("""
                {"checks": [
                  {"field": "$.request.headers.x-channel", "check": "REQUIRED"},
                  {"field": "$.request.body.currency", "check": "LOOKUP", "lookup": "CURRENCY"},
                  {"field": "$.request.body.accountNo", "check": "PATTERN", "pattern": "[0-9]{10}"},
                  {"field": "$.request.body.name", "check": "LENGTH", "min": 2, "max": 5},
                  {"field": "$.request.body.amount", "check": "RANGE", "min": 1, "max": 1000},
                  {"field": "$.request.body.note", "check": "LENGTH", "max": 3, "optional": true},
                  {"check": "EXPR", "expr": "${request.body.amount} < 500 || ${request.body.currency} == 'IDR'",
                   "message": "USD amounts must be below 500"}]}"""));
        Map<String, String> channel = Map.of("x-channel", "MB");
        String ok = "{\"currency\": \"IDR\", \"accountNo\": \"1234567890\", \"name\": \"Budi\", \"amount\": 700}";

        assertThat(post(ok, channel).status()).isEqualTo(200);
        assertThat(detail(post(ok, Map.of()))).isEqualTo("x-channel is required");
        assertThat(detail(post(ok.replace("IDR", "EUR"), channel))).isEqualTo("currency must be one of [IDR, USD]");
        assertThat(detail(post(ok.replace("1234567890", "12345"), channel))).isEqualTo("accountNo does not match [0-9]{10}");
        assertThat(detail(post(ok.replace("Budi", "Budiman"), channel))).isEqualTo("name must be from 2 to 5 characters long");
        // numeric text counts as a number
        assertThat(detail(post(ok.replace("700", "\"5000\""), channel))).isEqualTo("amount must be a number from 1 to 1000");
        assertThat(detail(post(ok.replace("}", ", \"note\": \"long\"}"), channel))).isEqualTo("note must be at most 3 characters long");
        assertThat(detail(post(ok.replace("IDR", "USD"), channel))).isEqualTo("USD amounts must be below 500");
        // text compared with a number: the check fails (400), not the gateway (500)
        assertThat(detail(post(ok.replace("700", "\"700\"").replace("IDR", "USD"), channel))).isEqualTo("USD amounts must be below 500");
    }

    private static String detail(GatewayResponse r) {
        assertThat(r.status()).as(r.body().toString()).isEqualTo(400);
        return r.body().get("details").get(0).asString();
    }

    @Test
    void aMappedErrorResponseReadsWhichCheckFailed() {
        schemas();
        FlowRow f = flow(x -> x.withHandlers(null, null, "RESPOND:invalid").withRequestValidation(PURPOSE.formatted("CHECKS_FIRST")));
        rows.response(f, "invalid", 422);
        rows.responseRule(f, "invalid", "$.code", "$.error.validation.code", null);
        rows.responseRule(f, "invalid", "$.field", "$.error.validation.field", null);
        rows.responseRule(f, "invalid", "$.message", "$.error.details[0]", null);

        GatewayResponse r = post("{\"purpose\": \"nope\"}");

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.body().toString())
                .isEqualTo("{\"code\":\"PURPOSE_INVALID\",\"field\":\"purpose\",\"message\":\"purpose must be new, modify or delete\"}");
    }

    @Test
    void mistakesAreReportedOnReload() {
        flow(f -> f.withRequestValidation("""
                {"order": "SOMETIMES",
                 "checks": [
                   {"field": "$.request.body.a", "check": "MAYBE"},
                   {"field": "$.steps.x.body.a", "check": "REQUIRED"},
                   {"field": "$.request.body.b", "check": "ENUM", "values": []},
                   {"field": "$.request.body.c", "check": "LOOKUP", "lookup": "NOPE"},
                   {"field": "$.request.body.d", "check": "PATTERN", "pattern": "[a-"},
                   {"field": "$.request.body.e", "check": "RANGE"},
                   {"check": "EXPR", "expr": "${steps.s.body.x} == 1"}],
                 "schemaBy": {"field": "$.request.body.purpose", "schemas": {"new": "GHOST"}}}"""));

        assertThatThrownBy(this::registry).isInstanceOfSatisfying(ConfigValidationException.class, e -> assertThat(e.errors())
                .anySatisfy(m -> assertThat(m).contains("order 'SOMETIMES' must be CHECKS_FIRST or SCHEMA_FIRST"))
                .anySatisfy(m -> assertThat(m).contains("check 'MAYBE' must be one of"))
                .anySatisfy(m -> assertThat(m).contains("must be a field of the request"))
                .anySatisfy(m -> assertThat(m).contains("values must be a non-empty array"))
                .anySatisfy(m -> assertThat(m).contains("lookup 'NOPE' has no gw_lookup_entry rows"))
                .anySatisfy(m -> assertThat(m).contains("pattern is not a valid regular expression"))
                .anySatisfy(m -> assertThat(m).contains("set min, max or both"))
                .anySatisfy(m -> assertThat(m).contains("expr can only read the request"))
                .anySatisfy(m -> assertThat(m).contains("value 'new' references unknown schema 'GHOST'")));
    }
}
