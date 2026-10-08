package com.mhamzah.gateway.masking;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Replaces values of configured field / header names (case-insensitive, any depth) for logs and audit. */
public class Masker {

    private final Set<String> fields;
    private final String mask;

    public Masker(Collection<String> fields, String mask) {
        this.fields = fields.stream().map(f -> f.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        this.mask = mask;
    }

    /** Masked deep copy; the input is not modified. */
    public JsonNode mask(JsonNode node) {
        if (node == null) {
            return null;
        }
        JsonNode copy = node.deepCopy();
        maskInPlace(copy);
        return copy;
    }

    public Map<String, String> maskHeaders(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((k, v) -> out.put(k, isSensitive(k) ? mask : v));
        return out;
    }

    private void maskInPlace(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            for (String name : Set.copyOf(obj.propertyNames())) {
                if (isSensitive(name)) {
                    obj.put(name, mask);
                } else {
                    maskInPlace(obj.get(name));
                }
            }
        } else if (node instanceof ArrayNode arr) {
            arr.forEach(this::maskInPlace);
        }
    }

    private boolean isSensitive(String name) {
        return fields.contains(name.toLowerCase(Locale.ROOT));
    }
}
