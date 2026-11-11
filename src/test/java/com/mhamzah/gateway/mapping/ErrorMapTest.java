package com.mhamzah.gateway.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ErrorMapTest {

    private static ErrorMap map(String json) {
        List<JsonNode> entries = new ArrayList<>();
        JsonValues.MAPPER.readTree(json).forEach(entries::add);
        return new ErrorMap("M", entries);
    }

    private static final ErrorMap ERRORS = map("""
            [{"code":"NOT_FOUND","message":"{message}","messageEn":"{message}"},
             {"code":"NOT_FOUND","message":"API key tidak valid","messageEn":"Invalid API key"},
             {"code":"NOT_FOUND","message":"Upload tidak ditemukan: {id}","messageEn":"Upload not found: {id}"},
             {"code":"NOT_FOUND"}]""");

    private static ErrorMap.Lookup lookup(ErrorMap map) {
        return new ErrorMap.Lookup(map, List.of(
                new ErrorMap.Condition("code", JsonPath.compile("$.body.code"), null, true),
                new ErrorMap.Condition("message", JsonPath.compile("$.body.message"), null, true)), "messageEn", true);
    }

    private static String messageEn(String message) {
        JsonNode v = lookup(ERRORS).evaluate(JsonValues.MAPPER.readTree(
                "{\"body\":{\"code\":\"NOT_FOUND\",\"message\":\"" + message + "\"}}")).value();
        return v == null ? null : v.asString();
    }

    @Test
    void theTemplatesOfAMapAreCompiledOnceForAllItsLookups() {
        ErrorMap map = map("[{\"message\":\"A {x}\"},{\"message\":\"B\"}]");
        ErrorMap.Template[] first = map.templates("message");

        lookup(map);
        lookup(map);

        assertThat(map.templates("message")).isSameAs(first);
    }

    @Test
    void onlyTextsWithPlaceholdersBecomeARegex() {
        ErrorMap.Template plain = ErrorMap.Template.of("API key tidak valid");
        ErrorMap.Template withId = ErrorMap.Template.of("Upload tidak ditemukan: {id}");

        assertThat(plain.pattern()).isNull();
        assertThat(plain.match("API key tidak valid")).isEqualTo(Map.of());
        assertThat(plain.match("API key tidak valid!")).isNull();
        assertThat(plain.literal()).isEqualTo("API key tidak valid".length());
        assertThat(withId.pattern()).isNotNull();
        assertThat(withId.match("Upload tidak ditemukan: X1")).isEqualTo(Map.of("id", "X1"));
    }

    @Test
    void everyEntryIsStillMatchedAndTheMostSpecificWins() {
        // plain text beats the catch-all, a template with a value is filled, anything else falls to the catch-all
        assertThat(messageEn("API key tidak valid")).isEqualTo("Invalid API key");
        assertThat(messageEn("  API key tidak valid  ")).isEqualTo("Invalid API key");
        assertThat(messageEn("Upload tidak ditemukan: X1")).isEqualTo("Upload not found: X1");
        assertThat(messageEn("Lainnya")).isEqualTo("Lainnya");
        // the entry without a message never matches a template condition on it
        assertThat(lookup(ERRORS).evaluate(JsonValues.MAPPER.readTree(
                "{\"body\":{\"code\":\"NOT_FOUND\",\"message\":\"x\"}}")).matches()).isEqualTo(1);
    }
}
