package com.mhamzah.gateway.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mhamzah.gateway.extension.ErrorType;
import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.extension.GatewayError;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ConditionTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private ExecutionContext ctx;

    @BeforeEach
    void setUp() {
        ObjectNode request = (ObjectNode) mapper.readTree("""
                {"headers":{"x-id":"abc"},"body":{"amount":150.5,"count":3,"vip":true}}""");
        ctx = new ExecutionContext("F", "c", Map.of(), request);
        ctx.putStepResult("inquiry", (ObjectNode) mapper.readTree("""
                {"outcome":"SUCCESS","status":200,"body":{"status":"ACTIVE","responseCode":"00"}}"""));
    }

    private boolean eval(String expr) {
        return Condition.compile(expr).evaluate(ctx);
    }

    @Test
    void comparesStringsFromContext() {
        assertThat(eval("${steps.inquiry.body.status} == 'ACTIVE'")).isTrue();
        assertThat(eval("${steps.inquiry.body.responseCode} != '00'")).isFalse();
    }

    @Test
    void comparesNumbersAcrossTypes() {
        assertThat(eval("${request.body.amount} > 100")).isTrue();
        assertThat(eval("${request.body.count} == 3")).isTrue();
        assertThat(eval("${request.body.amount} <= 150.5")).isTrue();
    }

    @Test
    void booleanLogicAndBracketPaths() {
        assertThat(eval("${request.body.vip} and ${request.headers['x-id']} == 'abc'")).isTrue();
        assertThat(eval("not ${request.body.vip} or ${steps.inquiry.status} == 200")).isTrue();
    }

    @Test
    void missingValuesAreNull() {
        assertThat(eval("${request.body.nope} == null")).isTrue();
        assertThat(eval("${steps.notRunYet.outcome} == 'SKIPPED'")).isFalse();
    }

    @Test
    void exposesReferencedPaths() {
        var c = Condition.compile("${steps.a.body.x} == 1 and ${request.body.y} == 2");
        assertThat(c.references()).extracting(Object::toString)
                .containsExactly("$.steps.a.body.x", "$.request.body.y");
    }

    @Test
    void nonBooleanResultIsMappingError() {
        assertThatThrownBy(() -> eval("${request.body.count} + 1"))
                .isInstanceOfSatisfying(GatewayError.class, e -> assertThat(e.type()).isEqualTo(ErrorType.MAPPING_ERROR));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "T(java.lang.Runtime).getRuntime() != null",
        "new java.io.File('x') != null",
        "@someBean != null",
        "'abc'.length() == 3",
        "${request.body.count}.class != null",
        "#root != null",
        "#p5 == 1",
        "{1,2}.size() == 2",
        "${request.body.count} = 5",
    })
    void sandboxRejectsUnsafeConstructsAtCompile(String expr) {
        assertThatThrownBy(() -> Condition.compile(expr)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSyntaxErrorsAndBadPaths() {
        assertThatThrownBy(() -> Condition.compile("${request.body.x} ==")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Condition.compile("${request..x} == 1")).isInstanceOf(IllegalArgumentException.class);
    }
}
