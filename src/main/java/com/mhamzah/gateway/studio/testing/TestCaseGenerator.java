package com.mhamzah.gateway.studio.testing;

import com.mhamzah.gateway.config.FlowDefinition;
import com.mhamzah.gateway.studio.testing.TestModel.TestCase;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Derives test cases for one flow from its operation in the generated OpenAPI document (the one Swagger UI shows):
 * a happy path with every field, one with only the required fields, one per missing required field or parameter,
 * and, when the flow validates its body with a JSON schema, one per field sent with the wrong type.
 */
public final class TestCaseGenerator {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final int MAX_NEGATIVE = 8;
    private static final Set<String> SKIPPED_HEADERS = Set.of("x-correlation-id");

    private final JsonNode doc;

    private TestCaseGenerator(JsonNode doc) {
        this.doc = doc;
    }

    /**
     * @param openApi the document from {@code OpenApiGenerator.generate}
     * @param apiBasePath {@code gateway.api-base-path}; case paths are relative to it
     */
    public static List<TestCase> generate(JsonNode openApi, FlowDefinition flow, String apiBasePath) {
        return new TestCaseGenerator(openApi).cases(flow, apiBasePath);
    }

    /** A plausible value for a schema of the document (as the happy path uses), for the API specification. */
    static JsonNode example(JsonNode openApi, JsonNode schema, String name) {
        return new TestCaseGenerator(openApi).example(schema, name, false, 0);
    }

    /** The schema with local {@code $ref}s followed and {@code allOf} merged. */
    static JsonNode resolve(JsonNode openApi, JsonNode schema) {
        return new TestCaseGenerator(openApi).resolve(schema);
    }

    static String typeOf(JsonNode schema) {
        return type(schema);
    }

    private List<TestCase> cases(FlowDefinition flow, String apiBasePath) {
        String method = flow.method().name();
        JsonNode op = null;
        String template = null;
        for (Map.Entry<String, JsonNode> path : doc.path("paths").properties()) {
            JsonNode candidate = path.getValue().get(method.toLowerCase(Locale.ROOT));
            if (candidate != null && flow.code().equals(candidate.path("operationId").asString(null))) {
                op = candidate;
                template = path.getKey();
            }
        }
        if (op == null) {
            throw new IllegalArgumentException("flow '" + flow.code() + "' is not in the API description");
        }
        String base = apiBasePath.endsWith("/") ? apiBasePath.substring(0, apiBasePath.length() - 1) : apiBasePath;
        String relative = template.startsWith(base) ? template.substring(base.length()) : template;

        Map<String, String> pathVars = new LinkedHashMap<>();
        Map<String, Param> query = new LinkedHashMap<>();
        Map<String, Param> headers = new LinkedHashMap<>();
        for (JsonNode p : op.path("parameters")) {
            String name = p.path("name").asString();
            boolean required = p.path("required").asBoolean(false);
            String value = guessText(name, resolve(p.path("schema")));
            switch (p.path("in").asString()) {
                case "path" -> pathVars.put(name, value);
                case "query" -> query.put(name, new Param(value, required));
                case "header" -> {
                    if (!SKIPPED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                        headers.put(name, new Param(value, required));
                    }
                }
                default -> { }
            }
        }
        String path = relative;
        for (Map.Entry<String, String> v : pathVars.entrySet()) {
            path = path.replace("{" + v.getKey() + "}", v.getValue());
        }

        JsonNode bodySchema = op.has("requestBody")
                ? resolve(op.path("requestBody").path("content").path("application/json").path("schema")) : null;
        boolean validated = flow.requestSchema() != null;
        int ok = flow.successStatus();
        List<TestCase> out = new ArrayList<>();

        JsonNode full = bodySchema == null ? null : example(bodySchema, "body", false, 0);
        out.add(new TestCase("Happy path - all fields",
                "Every documented parameter and body field with a plausible value. Expect the flow's success status.",
                method, path, values(query, false), values(headers, false), full, ok));

        JsonNode minimal = bodySchema == null ? null : example(bodySchema, "body", true, 0);
        boolean hasOptional = !java.util.Objects.equals(full, minimal)
                || query.values().stream().anyMatch(p -> !p.required) || headers.values().stream().anyMatch(p -> !p.required);
        if (hasOptional) {
            out.add(new TestCase("Happy path - required fields only",
                    "Only the required parameters and body fields. Expect the flow's success status.",
                    method, path, values(query, true), values(headers, true), minimal, ok));
        }

        int negatives = 0;
        if (full instanceof ObjectNode body) {
            for (String field : required(bodySchema)) {
                if (negatives++ >= MAX_NEGATIVE) {
                    break;
                }
                ObjectNode without = body.deepCopy();
                without.remove(field);
                out.add(new TestCase("Missing required field '" + field + "'",
                        "Body without '" + field + "'. Expect 400 ("
                                + (validated ? "GW-400-SCHEMA from schema " + flow.requestSchema().code()
                                        : "a required mapping rule reads it") + ").",
                        method, path, values(query, false), values(headers, false), without, 400));
            }
        }
        for (Map.Entry<String, Param> q : query.entrySet()) {
            if (q.getValue().required && negatives++ < MAX_NEGATIVE) {
                Map<String, String> without = values(query, false);
                without.remove(q.getKey());
                out.add(new TestCase("Missing required query parameter '" + q.getKey() + "'",
                        "Expect 400: a required mapping rule reads it.", method, path, without,
                        values(headers, false), full, 400));
            }
        }
        for (Map.Entry<String, Param> h : headers.entrySet()) {
            if (h.getValue().required && negatives++ < MAX_NEGATIVE) {
                Map<String, String> without = values(headers, false);
                without.remove(h.getKey());
                out.add(new TestCase("Missing required header '" + h.getKey() + "'",
                        "Expect 400: a required mapping rule reads it.", method, path, values(query, false),
                        without, full, 400));
            }
        }
        if (validated && full instanceof ObjectNode body) {
            int wrong = 0;
            for (Map.Entry<String, JsonNode> prop : resolve(bodySchema).path("properties").properties()) {
                JsonNode bad = wrongType(resolve(prop.getValue()));
                if (bad == null || wrong++ >= MAX_NEGATIVE / 2) {
                    continue;
                }
                ObjectNode mutated = body.deepCopy();
                mutated.set(prop.getKey(), bad);
                out.add(new TestCase("Wrong type for '" + prop.getKey() + "'",
                        "'" + prop.getKey() + "' sent as " + bad + ". Expect 400 (GW-400-SCHEMA from schema "
                                + flow.requestSchema().code() + ").",
                        method, path, values(query, false), values(headers, false), mutated, 400));
            }
        }
        return out;
    }

    private record Param(String value, boolean required) {}

    private static Map<String, String> values(Map<String, Param> params, boolean requiredOnly) {
        Map<String, String> out = new LinkedHashMap<>();
        params.forEach((k, v) -> {
            if (!requiredOnly || v.required) {
                out.put(k, v.value);
            }
        });
        return out;
    }

    /** Follows local {@code $ref}s and merges {@code allOf}. */
    private JsonNode resolve(JsonNode schema) {
        JsonNode s = schema;
        for (int i = 0; i < 10 && s != null && s.has("$ref"); i++) {
            String ref = s.get("$ref").asString();
            s = ref.startsWith("#") ? doc.at(ref.substring(1)) : null;
        }
        if (s == null || s.isMissingNode()) {
            return F.objectNode();
        }
        if (s.has("allOf")) {
            ObjectNode merged = F.objectNode().put("type", "object");
            ObjectNode props = merged.putObject("properties");
            ArrayNode req = merged.putArray("required");
            for (JsonNode part : s.get("allOf")) {
                JsonNode p = resolve(part);
                p.path("properties").properties().forEach(e -> props.set(e.getKey(), e.getValue()));
                p.path("required").forEach(req::add);
            }
            return merged;
        }
        if (s.has("oneOf") || s.has("anyOf")) {
            JsonNode first = (s.has("oneOf") ? s.get("oneOf") : s.get("anyOf")).path(0);
            return first.isMissingNode() ? s : resolve(first);
        }
        return s;
    }

    private Set<String> required(JsonNode schema) {
        Set<String> out = new LinkedHashSet<>();
        resolve(schema).path("required").forEach(r -> out.add(r.asString()));
        return out;
    }

    private static String type(JsonNode s) {
        JsonNode t = s.get("type");
        if (t != null && t.isArray()) {
            for (JsonNode x : t) {
                if (!"null".equals(x.asString())) {
                    return x.asString();
                }
            }
        }
        if (t != null && t.isString()) {
            return t.asString();
        }
        return s.has("properties") ? "object" : s.has("items") ? "array" : null;
    }

    private JsonNode example(JsonNode raw, String name, boolean requiredOnly, int depth) {
        JsonNode s = resolve(raw);
        for (String k : List.of("example", "default", "const")) {
            if (s.has(k)) {
                return s.get(k).deepCopy();
            }
        }
        if (s.path("examples").isArray() && !s.get("examples").isEmpty()) {
            return s.get("examples").get(0).deepCopy();
        }
        if (s.path("enum").isArray() && !s.get("enum").isEmpty()) {
            return s.get("enum").get(0).deepCopy();
        }
        String type = type(s);
        if (type == null) {
            type = "string";
        }
        switch (type) {
            case "object": {
                ObjectNode o = F.objectNode();
                if (depth > 6) {
                    return o;
                }
                Set<String> req = required(s);
                s.path("properties").properties().forEach(e -> {
                    if (!requiredOnly || req.contains(e.getKey())) {
                        o.set(e.getKey(), example(e.getValue(), e.getKey(), requiredOnly, depth + 1));
                    }
                });
                return o;
            }
            case "array": {
                ArrayNode a = F.arrayNode();
                if (depth <= 6) {
                    a.add(example(s.path("items"), name, requiredOnly, depth + 1));
                }
                return a;
            }
            case "integer":
                return F.numberNode(guessNumber(name, s, true).longValue());
            case "number":
                return F.numberNode(guessNumber(name, s, false));
            case "boolean":
                return F.booleanNode(true);
            default:
                return F.stringNode(guessText(name, s));
        }
    }

    private static java.math.BigDecimal guessNumber(String name, JsonNode s, boolean integer) {
        String n = name.toLowerCase(Locale.ROOT);
        double v = n.matches(".*(amount|amt|bal|price|fee|total).*") ? 150000
                : n.matches(".*(limit|size|count).*") ? 10 : n.matches(".*(page).*") ? 1 : 1;
        if (s.has("minimum")) {
            v = Math.max(v, s.get("minimum").asDouble());
        }
        if (s.has("exclusiveMinimum") && s.get("exclusiveMinimum").isNumber()) {
            v = Math.max(v, s.get("exclusiveMinimum").asDouble() + 1);
        }
        if (s.has("maximum")) {
            v = Math.min(v, s.get("maximum").asDouble());
        }
        // plain digits (150000, not 1.5E+5) in the test cases and the API specification examples
        return integer ? java.math.BigDecimal.valueOf((long) v)
                : new java.math.BigDecimal(java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString());
    }

    static String guessText(String name, JsonNode s) {
        String format = s.path("format").asString("");
        String value = switch (format) {
            case "date" -> LocalDate.now().toString();
            case "date-time" -> OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
            case "email" -> "unit.test@example.com";
            case "uuid" -> "3f2a9c1e-0000-4000-8000-000000000001";
            default -> null;
        };
        if (value == null) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.matches(".*(to|beneficiary|dest).*acc.*")) {
                value = "2002";
            } else if (n.matches(".*acc.*|.*account.*|.*cif.*")) {
                value = "1001";
            } else if (n.contains("channel")) {
                value = "MOBILE";
            } else if (n.matches(".*(currency|ccy).*")) {
                value = "IDR";
            } else if (n.contains("user")) {
                value = "unit-tester";
            } else if (n.matches(".*(phone|mobile|msisdn).*")) {
                value = "081234567890";
            } else if (n.matches(".*(note|remark|desc|memo).*")) {
                value = "Unit test";
            } else if (n.contains("name")) {
                value = "BUDI SANTOSO";
            } else if (n.matches(".*(limit|size|count)")) {
                value = "10";
            } else if (n.matches(".*(amount|amt)")) {
                value = "150000";
            } else if (n.matches(".*(date|dt)")) {
                value = LocalDate.now().toString().replace("-", "");
            } else {
                value = "TEST01";
            }
        }
        int min = s.path("minLength").asInt(0);
        int max = s.path("maxLength").asInt(Integer.MAX_VALUE);
        StringBuilder b = new StringBuilder(value);
        while (b.length() < min) {
            b.append('0');
        }
        return b.length() > max ? b.substring(0, Math.max(max, 0)) : b.toString();
    }

    private static JsonNode wrongType(JsonNode s) {
        String type = type(s);
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "string" -> F.numberNode(12345);
            case "integer", "number" -> F.stringNode("not-a-number");
            case "boolean" -> F.stringNode("yes");
            case "object" -> F.stringNode("not-an-object");
            case "array" -> F.stringNode("not-an-array");
            default -> null;
        };
    }
}
