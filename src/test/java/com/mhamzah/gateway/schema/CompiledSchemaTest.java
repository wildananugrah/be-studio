package com.mhamzah.gateway.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        assertThat(s.validate(mapper.readTree("{}"))).isNotEmpty().allSatisfy(m -> assertThat(m).startsWith("$: "));
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

    // ---- gw:CODE references

    static final String TRANSFER = """
            {"type":"object","required":["fromAccount","amount"],
             "properties":{"fromAccount":{"type":"string"},"amount":{"type":"number"}}}""";
    static final String TOPUP = """
            {"type":"object","required":["phoneNo","amount"],
             "properties":{"phoneNo":{"type":"string"},"amount":{"type":"number"}}}""";
    static final String PAYMENT = """
            {"oneOf":[{"$ref":"gw:TRANSFER_REQUEST"},{"$ref":"gw:TOPUP_REQUEST"}]}""";

    private static Map<String, String> texts(String... codeAndText) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < codeAndText.length; i += 2) {
            out.put(codeAndText[i], codeAndText[i + 1]);
        }
        return out;
    }

    private static List<String> codes(List<CompiledSchema> schemas) {
        return schemas.stream().map(CompiledSchema::code).toList();
    }

    @Test
    void compileAllResolvesReferencesForValidation() {
        var c = CompiledSchema.compileAll(texts("TRANSFER_REQUEST", TRANSFER, "TOPUP_REQUEST", TOPUP, "PAYMENT_REQUEST", PAYMENT));
        assertThat(c.errors()).isEmpty();
        CompiledSchema p = c.schemas().get("PAYMENT_REQUEST");
        assertThat(p.validate(mapper.readTree("{\"fromAccount\":\"1\",\"amount\":5}"))).isEmpty();
        assertThat(p.validate(mapper.readTree("{\"phoneNo\":\"0812\",\"amount\":5}"))).isEmpty();
        assertThat(p.validate(mapper.readTree("{\"amount\":5}"))).isNotEmpty();
    }

    @Test
    void fragmentReference() {
        var c = CompiledSchema.compileAll(texts(
                "SHARED", "{\"$defs\":{\"money\":{\"type\":\"number\",\"minimum\":1}}}",
                "AMOUNT", "{\"type\":\"object\",\"properties\":{\"amount\":{\"$ref\":\"gw:SHARED#/$defs/money\"}}}"));
        assertThat(c.errors()).isEmpty();
        assertThat(c.schemas().get("AMOUNT").validate(mapper.readTree("{\"amount\":0}")))
                .singleElement().satisfies(m -> assertThat(m).startsWith("$.amount: "));
    }

    @Test
    void unknownReferenceIsAnErrorOfTheReferencingSchema() {
        var c = CompiledSchema.compileAll(texts("PAYMENT_REQUEST", "{\"oneOf\":[{\"$ref\":\"gw:NOPE\"},{\"$ref\":\"gw:\"}]}"));
        assertThat(c.errors().get("PAYMENT_REQUEST")).containsExactly(
                "$ref 'gw:NOPE' references unknown schema 'NOPE'", "$ref 'gw:' references unknown schema ''");
        assertThat(c.schemas()).doesNotContainKey("PAYMENT_REQUEST");
    }

    @Test
    void fragmentPointingToNothingIsAnError() {
        var c = CompiledSchema.compileAll(texts("SHARED", "{\"$defs\":{}}", "R", "{\"$ref\":\"gw:SHARED#/$defs/nope\"}"));
        assertThat(c.errors().get("R")).containsExactly("$ref 'gw:SHARED#/$defs/nope' points to nothing in schema 'SHARED'");
    }

    @Test
    void cyclesCompileAndValidate() {
        var c = CompiledSchema.compileAll(texts(
                "NODE", "{\"type\":\"object\",\"properties\":{\"child\":{\"$ref\":\"gw:NODE\"}}}",
                "A", "{\"type\":\"object\",\"properties\":{\"b\":{\"$ref\":\"gw:B\"}}}",
                "B", "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"gw:A\"}}}"));
        assertThat(c.errors()).isEmpty();
        assertThat(c.schemas().get("NODE").validate(mapper.readTree("{\"child\":{\"child\":{\"child\":5}}}")))
                .singleElement().satisfies(m -> assertThat(m).startsWith("$.child.child.child: "));
        assertThat(c.schemas().get("A").validate(mapper.readTree("{\"b\":{\"a\":{\"b\":7}}}")))
                .singleElement().satisfies(m -> assertThat(m).startsWith("$.b.a.b: "));
        assertThat(c.schemas().get("NODE").references()).isEmpty();
        assertThat(codes(c.schemas().get("A").references())).containsExactly("B");
    }

    @Test
    void referencesAreTransitiveEachOnceInDiscoveryOrder() {
        var c = CompiledSchema.compileAll(texts(
                "R", "{\"oneOf\":[{\"$ref\":\"gw:A\"},{\"$ref\":\"gw:B\"}]}",
                "A", "{\"$ref\":\"gw:C\"}",
                "B", "{\"$ref\":\"gw:C\"}",
                "C", "{\"type\":\"string\"}"));
        assertThat(c.errors()).isEmpty();
        assertThat(codes(c.schemas().get("R").references())).containsExactly("A", "B", "C");
    }

    @Test
    void invalidReferencedSchemaDoesNotBreakTheReferencer() {
        var c = CompiledSchema.compileAll(texts("BROKEN", "{\"type\":42}", "R", "{\"$ref\":\"gw:BROKEN\"}"));
        assertThat(c.errors().get("BROKEN")).singleElement().satisfies(m -> assertThat(m).contains("draft 2020-12"));
        assertThat(c.errors().getOrDefault("R", List.of())).noneMatch(m -> m.contains("unknown schema"));
    }

    @Test
    void codeThatIsNotIriSafeStillCompiles() {
        var c = CompiledSchema.compileAll(texts("MY SCHEMA", SCHEMA));
        assertThat(c.errors()).isEmpty();
        assertThat(c.schemas().get("MY SCHEMA").validate(mapper.readTree("{\"amount\":10}"))).isEmpty();
    }

    @Test
    void referencedCodesListsDirectReferencesOnly() {
        assertThat(CompiledSchema.referencedCodes(mapper.readTree(PAYMENT)))
                .containsExactly("TRANSFER_REQUEST", "TOPUP_REQUEST");
        assertThat(CompiledSchema.referencedCodes(mapper.readTree(
                "{\"$defs\":{\"x\":{\"type\":\"string\"}},\"properties\":{\"a\":{\"$ref\":\"#/$defs/x\"},"
                        + "\"b\":{\"$ref\":\"gw:SHARED#/$defs/y\"}}}"))).containsExactly("SHARED");
    }

    @Test
    void singleCompileReportsAnUnknownReference() {
        assertThatThrownBy(() -> CompiledSchema.compile("R", "{\"$ref\":\"gw:NOPE\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("references unknown schema 'NOPE'");
    }
}
