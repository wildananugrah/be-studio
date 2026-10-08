package com.mhamzah.gateway.mapping;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Value translation table from {@code gw_lookup_entry}; {@code fallback} comes from the {@code *} row. */
public record LookupTable(String code, Map<String, JsonNode> entries, JsonNode fallback) {

    public static final String FALLBACK_KEY = "*";

    public LookupTable {
        entries = Map.copyOf(entries);
    }

    /** Translated value; the input itself when nothing matches and there is no fallback. */
    public JsonNode translate(JsonNode value) {
        if (JsonValues.isAbsent(value)) {
            return value;
        }
        JsonNode hit = find(value);
        return hit != null ? hit : value;
    }

    /** Matching entry or fallback; null when neither exists. */
    public JsonNode find(JsonNode value) {
        JsonNode hit = JsonValues.isAbsent(value) ? null : entries.get(JsonValues.keyOf(value));
        return hit != null ? hit : fallback;
    }
}
