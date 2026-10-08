package com.mhamzah.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mhamzah.gateway.config.ConfigRows.RuleRow;
import com.mhamzah.gateway.extension.DefaultErrorHandler;
import com.mhamzah.gateway.extension.ErrorHandler;
import com.mhamzah.gateway.extension.FieldHandler;
import com.mhamzah.gateway.extension.MessageHandler;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class ConfigCompilerTest {

    private final Map<String, Object> beans = Map.of(
            "defaultErrorHandler", new DefaultErrorHandler(),
            "myErrors", (ErrorHandler) (e, c) -> null,
            "myMessage", (MessageHandler) (m, c) -> null,
            "myField", (FieldHandler) (v, c) -> v);

    private final ConfigCompiler compiler = new ConfigCompiler(
            new ConfigCompiler.HandlerLookup() {
                @Override
                public <T> T find(String name, Class<T> type) {
                    Object bean = beans.get(name);
                    return type.isInstance(bean) ? type.cast(bean) : null;
                }
            },
            Map.of("CORE", new GatewayProperties.TargetSystem("http://core", 1000, 5000, Map.of(), null)),
            java.util.function.UnaryOperator.identity(),
            Duration.ofSeconds(30),
            Duration.ofSeconds(10));

    private final Rows rows = new Rows();

    private void assertInvalid(String... messageFragments) {
        assertThatThrownBy(() -> compiler.compile(rows.build()))
                .isInstanceOfSatisfying(ConfigValidationException.class, e -> {
                    for (String fragment : messageFragments) {
                        assertThat(e.errors()).anySatisfy(err -> assertThat(err).contains(fragment));
                    }
                });
    }

    @Test
    void compilesAValidMultiStepFlow() {
        var flow = rows.flow("TRANSFER", "POST", "/v1/accounts/{accountNo}/transfer",
                f -> f.withHandlers("myMessage", "myMessage", "myErrors"));
        var inquiry = rows.step(flow, "inquiry", 1, s -> s.withPathTemplate("/accounts/{acc}"));
        rows.rule(flow.id(), inquiry.id(), "STEP_REQUEST", "PATH", "acc", "$.request.path.accountNo", null);
        var fee = rows.step(flow, "fee", 1);
        var debit = rows.step(flow, "debit", 2,
                s -> s.withCondition("${steps.inquiry.body.status} == 'ACTIVE'")
                        .withSuccess("${steps.debit.body.responseCode} == '00'"));
        rows.stepRule(debit, "$.name", "$.steps.inquiry.body.name");
        rows.responseRule(flow, "$.fee", "$.steps.fee.body.amount");
        rows.lookup("CODES", "00", "OK");
        rows.lookup("CODES", "*", "FAIL");

        FlowRegistry registry = compiler.compile(rows.build());

        FlowDefinition f = registry.flows().getFirst();
        assertThat(f.code()).isEqualTo("TRANSFER");
        assertThat(f.groups()).hasSize(2);
        assertThat(f.groups().get(0)).extracting(StepDefinition::name).containsExactly("fee", "inquiry");
        assertThat(f.groups().get(1).getFirst().condition()).isNotNull();
        assertThat(f.timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(f.errorHandlerName()).isEqualTo("myErrors");
        assertThat(f.groups().get(0).getFirst().timeout()).isEqualTo(Duration.ofMillis(5000));
        assertThat(registry.lookups().get("CODES").fallback().stringValue()).isEqualTo("FAIL");

        var match = registry.match(HttpMethod.POST, "/v1/accounts/123/transfer").orElseThrow();
        assertThat(match.pathVariables()).containsEntry("accountNo", "123");
        assertThat(registry.match(HttpMethod.GET, "/v1/accounts/123/transfer")).isEmpty();
    }

    @Test
    void flowWithoutErrorHandlerUsesDefault() {
        rows.flow("F", "GET", "/x");
        FlowDefinition f = compiler.compile(rows.build()).flows().getFirst();
        assertThat(f.errorHandlerName()).isEqualTo("defaultErrorHandler");
        assertThat(f.errorHandler()).isInstanceOf(DefaultErrorHandler.class);
    }

    @Test
    void disabledFlowsAndStepsAreDropped() {
        var flow = rows.flow("F", "GET", "/x");
        rows.flow("OFF", "GET", "/x", f -> f.withEnabled(false));
        rows.step(flow, "gone", 1, s -> s.withEnabled(false));
        FlowRegistry r = compiler.compile(rows.build());
        assertThat(r.flows()).extracting(FlowDefinition::code).containsExactly("F");
        assertThat(r.flows().getFirst().groups()).isEmpty();
    }

    @Test
    void moreSpecificRouteWins() {
        rows.flow("ANY", "GET", "/accounts/{id}");
        rows.flow("ME", "GET", "/accounts/me");
        FlowRegistry r = compiler.compile(rows.build());
        assertThat(r.match(HttpMethod.GET, "/accounts/me").orElseThrow().flow().code()).isEqualTo("ME");
        assertThat(r.match(HttpMethod.GET, "/accounts/7").orElseThrow().flow().code()).isEqualTo("ANY");
    }

    // ---- validation rules (spec Section 11) ----

    @Test
    void rule1_duplicateCodesAndRoutes() {
        rows.flow("A", "GET", "/x/{id}");
        rows.flow("A", "POST", "/y");
        rows.flow("B", "GET", "/x/{other}");
        assertInvalid("Duplicate flow code 'A'", "same route");
    }

    @Test
    void rule1_invalidMethodAndPattern() {
        rows.flow("A", "FETCH", "/x");
        rows.flow("B", "GET", "no-slash");
        assertInvalid("http_method 'FETCH'", "path_pattern");
    }

    @Test
    void rule2_missingAndInvalidSchemas() {
        rows.flow("A", "POST", "/x", f -> f.withSchemas("NOPE", null));
        rows.schema("BROKEN", "{\"type\": 42}");
        assertInvalid("schema 'NOPE'", "BROKEN");
    }

    @Test
    void rule3_unknownOrWrongTypeHandlers() {
        var flow = rows.flow("A", "POST", "/x", f -> f.withHandlers("missingBean", null, "myMessage"));
        var step = rows.step(flow, "s", 1);
        rows.rule(new RuleRow(999, flow.id(), step.id(), "STEP_REQUEST", 1, "BODY", "$.x", null, null, null, null,
                null, "myErrors", false));
        assertInvalid("'missingBean'", "'myMessage'", "'myErrors'");
    }

    @Test
    void rule4_unknownTargetSystem() {
        var flow = rows.flow("A", "POST", "/x");
        rows.step(flow, "s", 1, s -> s.withTarget("NOWHERE"));
        assertInvalid("target_system 'NOWHERE'");
    }

    @Test
    void rule5_stepNamesAndOrder() {
        var flow = rows.flow("A", "POST", "/x");
        rows.step(flow, "s", 1);
        rows.step(flow, "s", 2);
        rows.step(flow, "bad name", 0);
        assertInvalid("Duplicate step name 's'", "step_order", "'bad name'");
    }

    @Test
    void rule6_badPathsAndWildcardMismatch() {
        var flow = rows.flow("A", "POST", "/x");
        rows.responseRule(flow, "$.a[", "$.request.body.x");
        rows.responseRule(flow, "$.a[*].b[*]", "$.request.body.items[*]");
        rows.rule(flow.id(), null, "FLOW_RESPONSE", "HEADER", "X-Items", "$.request.body.items[*].id", null);
        assertInvalid("target_path", "[*]", "wildcard");
    }

    @Test
    void rule7_sourceConstantExclusivityAndPhaseShape() {
        var flow = rows.flow("A", "POST", "/x");
        var step = rows.step(flow, "s", 1);
        rows.rule(flow.id(), null, "FLOW_RESPONSE", "BODY", "$.a", "$.request.body.a", "1");
        rows.rule(flow.id(), null, "FLOW_RESPONSE", "BODY", "$.b", null, null);
        rows.rule(flow.id(), null, "STEP_REQUEST", "BODY", "$.c", "$.request.body.c", null);
        rows.rule(flow.id(), step.id(), "FLOW_RESPONSE", "BODY", "$.d", "$.request.body.d", null);
        rows.rule(flow.id(), null, "FLOW_RESPONSE", "QUERY", "q", "$.request.body.q", null);
        rows.rule(flow.id(), null, "SIDEWAYS", "BODY", "$.e", "$.request.body.e", null);
        assertInvalid("exactly one of source_path / constant_value", "STEP_REQUEST rule requires step_id",
                "FLOW_RESPONSE rule must not have step_id", "target_type QUERY", "phase 'SIDEWAYS'");
    }

    @Test
    void rule8_convertersAndLookups() {
        var flow = rows.flow("A", "POST", "/x");
        rows.rule(new RuleRow(50, flow.id(), null, "FLOW_RESPONSE", 1, "BODY", "$.a", "$.request.body.a", null, null,
                "PAD_LEFT:x", null, null, false));
        rows.rule(new RuleRow(51, flow.id(), null, "FLOW_RESPONSE", 2, "BODY", "$.b", "$.request.body.b", null, null,
                null, "NO_SUCH_LOOKUP", null, false));
        assertInvalid("PAD_LEFT", "lookup_code 'NO_SUCH_LOOKUP'");
    }

    @Test
    void rule9_conditionsMustCompile() {
        var flow = rows.flow("A", "POST", "/x");
        rows.step(flow, "s", 1, s -> s.withCondition("T(java.lang.System).exit(0) == null"));
        rows.step(flow, "t", 1, s -> s.withSuccess("${steps.t.status} =="));
        assertInvalid("condition_expr", "success_expr");
    }

    @Test
    void rule10_stepsMayOnlyReferenceEarlierGroups() {
        var flow = rows.flow("A", "POST", "/x");
        var a = rows.step(flow, "a", 1);
        rows.step(flow, "b", 1, s -> s.withCondition("${steps.a.status} == 200"));
        rows.stepRule(a, "$.x", "$.steps.c.body.x");
        rows.step(flow, "c", 2, s -> s.withSuccess("${steps.c.body.ok} == true"));
        rows.responseRule(flow, "$.y", "$.steps.ghost.body.y");
        assertInvalid("step 'b'", "step 'a'", "unknown step 'ghost'");
    }

    @Test
    void rule10_successExprMayReferenceItself() {
        var flow = rows.flow("A", "POST", "/x");
        rows.step(flow, "a", 1, s -> s.withSuccess("${steps.a.body.responseCode} == '00'"));
        assertThat(compiler.compile(rows.build()).flows()).hasSize(1);
    }

    @Test
    void rule11_pathTemplateVariablesNeedPathMappings() {
        var flow = rows.flow("A", "POST", "/x");
        rows.step(flow, "a", 1, s -> s.withPathTemplate("/accounts/{acc}/{other}"));
        assertInvalid("{acc}", "{other}");
    }

    @Test
    void reportsAllErrorsAtOnce() {
        rows.flow("A", "FETCH", "/x");
        rows.flow("B", "GET", "/y", f -> f.withSchemas("NOPE", null));
        assertThatThrownBy(() -> compiler.compile(rows.build()))
                .isInstanceOfSatisfying(ConfigValidationException.class, e -> assertThat(e.errors()).hasSize(2));
    }
}
