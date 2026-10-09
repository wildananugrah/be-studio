package com.mhamzah.gateway.masking;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Replaces values of configured field / header names (case-insensitive, any depth) for logs and audit. A text
 * payload (a body sent or received as XML / SOAP / JSON text) is masked too: JSON text by field, XML by the local
 * name of an element (its text content) or attribute.
 */
public class Masker {

    private static final Pattern XML_ELEMENT =
            Pattern.compile("<((?:[\\w.\\-]+:)?([\\w.\\-]+))(\\s[^<>]*)?>([^<]*)</\\1\\s*>");
    private static final Pattern XML_ATTRIBUTE =
            Pattern.compile("(\\s(?:[\\w.\\-]+:)?([\\w.\\-]+)\\s*=\\s*)(\"[^\"]*\"|'[^']*')");

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
        if (node.isString()) {
            return tools.jackson.databind.node.StringNode.valueOf(maskText(node.asString()));
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

    /** JSON or XML text with the sensitive values replaced; other text as it is. */
    public String maskText(String text) {
        if (text == null || fields.isEmpty()) {
            return text;
        }
        String lead = text.stripLeading();
        if (lead.startsWith("{") || lead.startsWith("[")) {
            try {
                JsonNode parsed = com.mhamzah.gateway.mapping.JsonValues.MAPPER.readTree(text);
                maskInPlace(parsed);
                return parsed.toString();
            } catch (RuntimeException e) {
                return text; // not JSON after all
            }
        }
        if (!lead.startsWith("<")) {
            return text;
        }
        String out = replace(XML_ELEMENT, text, m -> isSensitive(m.group(2))
                ? "<" + m.group(1) + (m.group(3) == null ? "" : m.group(3)) + ">" + mask + "</" + m.group(1) + ">" : m.group());
        return replace(XML_ATTRIBUTE, out, m -> isSensitive(m.group(2))
                ? m.group(1) + m.group(3).charAt(0) + mask + m.group(3).charAt(0) : m.group());
    }

    private static String replace(Pattern p, String text, java.util.function.Function<Matcher, String> f) {
        Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(f.apply(m)));
        }
        return m.appendTail(sb).toString();
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
