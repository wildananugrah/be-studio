package com.mhamzah.gateway.mapping;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/** Helpers for literal values stored as text in config tables. */
public final class JsonValues {

    /**
     * The mapper used for every payload. Floats are read as exact {@link java.math.BigDecimal}s with their
     * scale preserved, so amounts like {@code 12500.50} pass through unchanged.
     */
    public static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private JsonValues() {}

    /** Parses {@code text} as JSON when it is valid JSON, otherwise returns it as a string node. */
    public static JsonNode parseLiteral(String text) {
        if (text == null) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(text);
            if (node != null && !node.isMissingNode()) {
                return node;
            }
        } catch (JacksonException ignored) {
            // not JSON: use as plain string
        }
        return JsonNodeFactory.instance.stringNode(text);
    }

    /** Text form used for lookup keys and string conversions: plain text for scalars, JSON for containers. */
    public static String keyOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isValueNode() ? node.asString() : node.toString();
    }

    /** True when the value should be treated as absent (missing or JSON null). */
    public static boolean isAbsent(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }
}
