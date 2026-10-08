package com.mhamzah.gateway.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

class ConvertersTest {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private JsonNode apply(String spec, JsonNode in) {
        return Converters.parse(spec).apply(in);
    }

    @Test
    void toStringAndToNumber() {
        assertThat(apply("TO_STRING", F.numberNode(12)).stringValue()).isEqualTo("12");
        assertThat(apply("TO_NUMBER", F.stringNode("12.50")).decimalValue()).isEqualByComparingTo("12.50");
        assertThat(apply("TO_NUMBER", F.stringNode("7")).isIntegralNumber()).isTrue();
    }

    @Test
    void toBooleanAcceptsCommonForms() {
        assertThat(apply("TO_BOOLEAN", F.stringNode("Y")).booleanValue()).isTrue();
        assertThat(apply("TO_BOOLEAN", F.stringNode("0")).booleanValue()).isFalse();
        assertThat(apply("TO_BOOLEAN", F.stringNode("true")).booleanValue()).isTrue();
        assertThatThrownBy(() -> apply("TO_BOOLEAN", F.stringNode("maybe")))
                .isInstanceOf(ConversionException.class);
    }

    @Test
    void padding() {
        assertThat(apply("PAD_LEFT:12:0", F.numberNode(12500)).stringValue()).isEqualTo("000000012500");
        assertThat(apply("PAD_RIGHT:5: ", F.stringNode("ab")).stringValue()).isEqualTo("ab   ");
        assertThat(apply("PAD_LEFT:2:0", F.stringNode("abc")).stringValue()).isEqualTo("abc");
    }

    @Test
    void stringOperations() {
        assertThat(apply("TRIM", F.stringNode("  x ")).stringValue()).isEqualTo("x");
        assertThat(apply("UPPER", F.stringNode("ab")).stringValue()).isEqualTo("AB");
        assertThat(apply("LOWER", F.stringNode("AB")).stringValue()).isEqualTo("ab");
        assertThat(apply("SUBSTRING:0:3", F.stringNode("abcdef")).stringValue()).isEqualTo("abc");
        assertThat(apply("SUBSTRING:2", F.stringNode("abcdef")).stringValue()).isEqualTo("cdef");
        assertThat(apply("SUBSTRING:0:10", F.stringNode("abc")).stringValue()).isEqualTo("abc");
    }

    @Test
    void dateFormatWithEscapedColon() {
        assertThat(apply("DATE_FORMAT:yyyy-MM-dd:ddMMyyyy", F.stringNode("2026-10-08")).stringValue())
                .isEqualTo("08102026");
        assertThat(apply("DATE_FORMAT:yyyy-MM-dd'T'HH\\:mm:HHmm", F.stringNode("2026-10-08T14:05")).stringValue())
                .isEqualTo("1405");
        assertThatThrownBy(() -> apply("DATE_FORMAT:yyyy-MM-dd:ddMMyyyy", F.stringNode("08/10/2026")))
                .isInstanceOf(ConversionException.class);
    }

    @Test
    void decimalScale() {
        assertThat(apply("DECIMAL_SCALE:2", F.stringNode("10.005")).decimalValue()).isEqualByComparingTo("10.01");
        assertThat(apply("DECIMAL_SCALE:2", F.stringNode("10")).toString()).isEqualTo("10.00");
    }

    @Test
    void decimalsKeepTheirExactScale() {
        assertThat(apply("TO_NUMBER", F.stringNode("1500000.00")).toString()).isEqualTo("1500000.00");
        assertThat(apply("TO_NUMBER", F.stringNode("0.10")).toString()).isEqualTo("0.10");
    }

    @Test
    void nullValuesPassThrough() {
        assertThat(apply("UPPER", null)).isNull();
        assertThat(apply("UPPER", F.nullNode()).isNull()).isTrue();
    }

    @Test
    void invalidSpecsAreRejectedAtParse() {
        assertThatThrownBy(() -> Converters.parse("NOPE")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Converters.parse("PAD_LEFT:x:0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Converters.parse("PAD_LEFT:5")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Converters.parse("PAD_LEFT:5:ab")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Converters.parse("DATE_FORMAT:yyyy")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Converters.parse("DATE_FORMAT:qqqqqqq:yyyy")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Converters.parse("DECIMAL_SCALE:-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Converters.parse("TRIM:1")).isInstanceOf(IllegalArgumentException.class);
    }
}
