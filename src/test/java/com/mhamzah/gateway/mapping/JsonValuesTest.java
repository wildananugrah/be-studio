package com.mhamzah.gateway.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class JsonValuesTest {

    @Test
    void literalIsParsedAsJsonWhenValid() {
        assertThat(JsonValues.parseLiteral("123").isIntegralNumber()).isTrue();
        assertThat(JsonValues.parseLiteral("true").isBoolean()).isTrue();
        assertThat(JsonValues.parseLiteral("{\"a\":1}").isObject()).isTrue();
        assertThat(JsonValues.parseLiteral("\"quoted\"").stringValue()).isEqualTo("quoted");
    }

    @Test
    void decimalLiteralsAreExact() {
        assertThat(JsonValues.parseLiteral("12.50").toString()).isEqualTo("12.50");
        assertThat(JsonValues.parseLiteral("0.1").decimalValue()).isEqualTo(new java.math.BigDecimal("0.1"));
    }

    @Test
    void sharedMapperParsesFloatsAsExactDecimals() {
        assertThat(JsonValues.MAPPER.readTree("{\"a\":12500.50}").toString()).isEqualTo("{\"a\":12500.50}");
        assertThat(JsonValues.MAPPER.readTree("{\"a\":1.0E+3}").get("a").isBigDecimal()).isTrue();
    }

    @Test
    void literalFallsBackToString() {
        assertThat(JsonValues.parseLiteral("MOBILE").stringValue()).isEqualTo("MOBILE");
        assertThat(JsonValues.parseLiteral("00").stringValue()).isEqualTo("00");
        assertThat(JsonValues.parseLiteral("").stringValue()).isEqualTo("");
        assertThat(JsonValues.parseLiteral("12 34").stringValue()).isEqualTo("12 34");
    }

    @Test
    void keyOfUsesPlainTextForScalars() {
        assertThat(JsonValues.keyOf(JsonNodeFactory.instance.stringNode("51"))).isEqualTo("51");
        assertThat(JsonValues.keyOf(JsonNodeFactory.instance.numberNode(51))).isEqualTo("51");
        assertThat(JsonValues.keyOf(JsonNodeFactory.instance.booleanNode(true))).isEqualTo("true");
    }
}
