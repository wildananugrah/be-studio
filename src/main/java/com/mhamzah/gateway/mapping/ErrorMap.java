package com.mhamzah.gateway.mapping;

import com.mhamzah.gateway.extension.ExecutionContext;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * An error map from {@code gw_error_map}: a list of JSON objects (e.g. one per error code, with its HTTP status,
 * service and messages). A mapping rule reads one field of the first entry that matches its conditions.
 */
public record ErrorMap(String code, List<JsonNode> entries) {

    public ErrorMap {
        entries = List.copyOf(entries);
    }

    /**
     * The first entry whose fields equal {@code values} (field name to value, compared as text, so {@code 400} matches
     * {@code "400"}); null when none does.
     */
    public JsonNode find(List<String> fields, List<JsonNode> values) {
        for (JsonNode entry : entries) {
            boolean all = true;
            for (int i = 0; i < fields.size() && all; i++) {
                String expected = JsonValues.keyOf(values.get(i));
                all = expected != null && expected.equals(JsonValues.keyOf(entry.get(fields.get(i))));
            }
            if (all) {
                return entry;
            }
        }
        return null;
    }

    /** The field names used across the entries, in the order they first appear (for Studio's pickers). */
    public List<String> fields() {
        List<String> out = new ArrayList<>();
        entries.forEach(e -> e.propertyNames().forEach(n -> {
            if (!out.contains(n)) {
                out.add(n);
            }
        }));
        return out;
    }

    /** One condition of a rule: the entry's {@code field} equals the value at {@code source}, or {@code value}. */
    public record Condition(String field, JsonPath source, JsonNode value) {}

    /** What a rule takes from a map: field {@code output} of the first entry matching every condition. */
    public record Lookup(ErrorMap map, List<Condition> where, String output) {

        public Lookup {
            where = List.copyOf(where);
        }

        /** The output field of the matching entry; null when no entry matches (the rule's default applies). */
        public JsonNode resolve(ExecutionContext ctx) {
            List<String> fields = new ArrayList<>();
            List<JsonNode> values = new ArrayList<>();
            for (Condition c : where) {
                JsonNode v = c.source() != null ? ctx.read(c.source()) : c.value();
                if (JsonValues.isAbsent(v)) {
                    return null;
                }
                fields.add(c.field());
                values.add(v);
            }
            JsonNode entry = map.find(fields, values);
            return entry == null ? null : entry.get(output);
        }

        /** The context paths the conditions read. */
        public List<JsonPath> references() {
            return where.stream().map(Condition::source).filter(java.util.Objects::nonNull).toList();
        }
    }
}
