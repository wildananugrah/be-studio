package com.mhamzah.gateway.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CompiledSchemaTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private static final String SCHEMA = """
            {"type":"object","required":["amount"],
             "properties":{"amount":{"type":"number","minimum":1},"note":{"type":"string"}}}""";

    @Test
    void validDocumentHasNoErrors() {
        CompiledSchema s = CompiledSchema.compile("TRANSFER_REQ", SCHEMA);
        assertThat(s.validate(mapper.readTree("{\"amount\":10}"))).isEmpty();
    }

    @Test
    void invalidDocumentReportsMessages() {
        CompiledSchema s = CompiledSchema.compile("TRANSFER_REQ", SCHEMA);
        assertThat(s.validate(mapper.readTree("{\"amount\":0,\"note\":5}")))
                .hasSize(2)
                .anySatisfy(m -> assertThat(m).contains("amount"))
                .anySatisfy(m -> assertThat(m).contains("note"));
        assertThat(s.validate(mapper.readTree("{}"))).anySatisfy(m -> assertThat(m).contains("amount"));
    }

    @Test
    void nestedErrorsUseJsonPathLocation() {
        CompiledSchema s = CompiledSchema.compile("TRANSFER_REQ", SCHEMA);
        assertThat(s.validate(mapper.readTree("{\"amount\":0}")))
                .singleElement().satisfies(m -> assertThat(m).startsWith("$.amount: "));
    }

    @Test
    void rootLevelErrorsUseDollarLocation() {
        CompiledSchema s = CompiledSchema.compile("TRANSFER_REQ", SCHEMA);
        assertThat(s.validate(mapper.readTree("{}"))).allSatisfy(m -> assertThat(m).startsWith("$: "));
    }

    @Test
    void rejectsSchemaThatIsNotJson() {
        assertThatThrownBy(() -> CompiledSchema.compile("BAD", "{not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BAD");
    }

    @Test
    void rejectsSchemaViolatingTheMetaSchema() {
        assertThatThrownBy(() -> CompiledSchema.compile("BAD", "{\"type\":42}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BAD");
    }
}
