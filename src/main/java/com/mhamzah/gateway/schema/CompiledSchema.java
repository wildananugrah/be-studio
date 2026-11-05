package com.mhamzah.gateway.schema;

import com.mhamzah.gateway.mapping.JsonValues;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A JSON Schema (draft 2020-12) from {@code gw_json_schema}, parsed and checked against the meta-schema at load. A schema
 * can reference another one by its code: {@code {"$ref": "gw:CODE"}} or {@code gw:CODE#/$defs/x}.
 */
public final class CompiledSchema {

    /** Prefix of a {@code $ref} to another gateway schema: {@code gw:CODE}. */
    public static final String REF_PREFIX = "gw:";

    /** Error locations in JSONPath form ({@code $.amount}), matching the gateway's own path syntax. */
    private static final SchemaRegistryConfig CONFIG = SchemaRegistryConfig.builder().pathType(PathType.JSON_PATH).build();
    private static final SchemaRegistry META_REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(CONFIG));
    private static final Schema META_SCHEMA =
            META_REGISTRY.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
    private static final JsonMapper MAPPER = JsonValues.MAPPER;

    private final String code;
    private final Schema schema;
    private final JsonNode json;
    /** The schemas this one references directly, set once every schema of the load is compiled. */
    private List<CompiledSchema> direct = List.of();

    private CompiledSchema(String code, Schema schema, JsonNode json) {
        this.code = code;
        this.schema = schema;
        this.json = json;
    }

    /** The outcome of {@link #compileAll}: the schemas that compiled, and the messages of those that did not (by code). */
    public record Compilation(Map<String, CompiledSchema> schemas, Map<String, List<String>> errors) {}

    /** A single schema without references to others; throws with the first problem. */
    public static CompiledSchema compile(String code, String schemaText) {
        Compilation c = compileAll(Map.of(code, schemaText));
        List<String> errors = c.errors().get(code);
        if (errors != null) {
            throw new IllegalArgumentException(errors.getFirst());
        }
        return c.schemas().get(code);
    }

    /**
     * Compiles the schemas of one config load together, so their {@code gw:CODE} references resolve. A reference to an
     * unknown code, or a fragment pointing to nothing, is an error of the referencing schema.
     */
    public static Compilation compileAll(Map<String, String> textsByCode) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        Map<String, JsonNode> parsed = new LinkedHashMap<>();
        textsByCode.forEach((code, text) -> {
            try {
                JsonNode node = MAPPER.readTree(text);
                List<Error> metaErrors = META_SCHEMA.validate(node);
                if (metaErrors.isEmpty()) {
                    parsed.put(code, node);
                } else {
                    error(errors, code, "Schema '" + code + "' is not a valid draft 2020-12 schema: "
                            + metaErrors.stream().map(Error::getMessage).toList());
                }
            } catch (JacksonException e) {
                error(errors, code, "Schema '" + code + "' is not valid JSON: " + e.getOriginalMessage());
            }
        });

        parsed.forEach((code, node) -> forEachRef(node, ref -> {
            String target = target(ref);
            String fragment = ref.substring(REF_PREFIX.length() + target.length());
            if (!textsByCode.containsKey(target)) {
                error(errors, code, "$ref '" + ref + "' references unknown schema '" + target + "'");
            } else if (fragment.startsWith("#") && fragment.length() > 1 && parsed.containsKey(target)
                    && parsed.get(target).at(fragment.substring(1)).isMissingNode()) {
                error(errors, code, "$ref '" + ref + "' points to nothing in schema '" + target + "'");
            }
        }));

        Map<String, String> byIri = new LinkedHashMap<>();
        parsed.keySet().forEach(code -> byIri.put(REF_PREFIX + code, textsByCode.get(code)));
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaRegistryConfig(CONFIG).schemas(byIri));
        Map<String, CompiledSchema> schemas = new LinkedHashMap<>();
        parsed.forEach((code, node) -> {
            if (errors.containsKey(code)) {
                return;
            }
            try {
                // from the text, not by its gw: location: a code need not be a valid IRI unless it is referenced
                schemas.put(code, new CompiledSchema(code, registry.getSchema(textsByCode.get(code), InputFormat.JSON), node));
            } catch (RuntimeException e) {
                error(errors, code, "Schema '" + code + "' cannot be loaded: " + e.getMessage());
            }
        });
        schemas.values().forEach(s -> s.direct = referencedCodes(s.json).stream()
                .map(schemas::get).filter(r -> r != null).toList());
        return new Compilation(schemas, errors);
    }

    /** The codes a schema document references with {@code gw:CODE}, in document order, without their fragments. */
    public static Set<String> referencedCodes(JsonNode schema) {
        Set<String> out = new LinkedHashSet<>();
        forEachRef(schema, ref -> out.add(target(ref)));
        return out;
    }

    private static String target(String ref) {
        String rest = ref.substring(REF_PREFIX.length());
        int hash = rest.indexOf('#');
        return hash < 0 ? rest : rest.substring(0, hash);
    }

    private static void forEachRef(JsonNode node, Consumer<String> action) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isString() && ref.asString().startsWith(REF_PREFIX)) {
                action.accept(ref.asString());
            }
            node.properties().forEach(e -> forEachRef(e.getValue(), action));
        } else if (node.isArray()) {
            node.forEach(child -> forEachRef(child, action));
        }
    }

    private static void error(Map<String, List<String>> errors, String code, String message) {
        errors.computeIfAbsent(code, k -> new ArrayList<>()).add(message);
    }

    public String code() {
        return code;
    }

    /** A copy of the schema document, e.g. for the OpenAPI description. */
    public JsonNode json() {
        return json.deepCopy();
    }

    /** Every schema reachable through {@code gw:} references, each once, nearest first; never this one. */
    public List<CompiledSchema> references() {
        Set<CompiledSchema> seen = new LinkedHashSet<>();
        Deque<CompiledSchema> queue = new ArrayDeque<>(direct);
        while (!queue.isEmpty()) {
            CompiledSchema next = queue.poll();
            if (next != this && seen.add(next)) {
                queue.addAll(next.direct);
            }
        }
        return List.copyOf(seen);
    }

    /** Validation messages prefixed with the instance location (e.g. {@code $.amount: ...}); empty when valid. */
    public List<String> validate(JsonNode document) {
        return schema.validate(document).stream()
                .map(e -> {
                    String location = String.valueOf(e.getInstanceLocation());
                    return (location.isEmpty() ? "$" : location) + ": " + e.getMessage();
                })
                .toList();
    }
}
