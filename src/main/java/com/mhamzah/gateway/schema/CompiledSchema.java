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
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A JSON Schema (draft 2020-12) from {@code gw_json_schema}, parsed and checked against the meta-schema at load. */
public final class CompiledSchema {

    /** Error locations in JSONPath form ({@code $.amount}), matching the gateway's own path syntax. */
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder().pathType(PathType.JSON_PATH).build()));
    private static final Schema META_SCHEMA =
            REGISTRY.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
    private static final JsonMapper MAPPER = JsonValues.MAPPER;

    private final String code;
    private final Schema schema;
    private final JsonNode json;

    private CompiledSchema(String code, Schema schema, JsonNode json) {
        this.code = code;
        this.schema = schema;
        this.json = json;
    }

    public static CompiledSchema compile(String code, String schemaText) {
        JsonNode node;
        try {
            node = MAPPER.readTree(schemaText);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Schema '" + code + "' is not valid JSON: " + e.getOriginalMessage(), e);
        }
        List<Error> metaErrors = META_SCHEMA.validate(node);
        if (!metaErrors.isEmpty()) {
            throw new IllegalArgumentException("Schema '" + code + "' is not a valid draft 2020-12 schema: "
                    + metaErrors.stream().map(Error::getMessage).toList());
        }
        try {
            return new CompiledSchema(code, REGISTRY.getSchema(schemaText, InputFormat.JSON), node);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Schema '" + code + "' cannot be loaded: " + e.getMessage(), e);
        }
    }

    public String code() {
        return code;
    }

    /** A copy of the schema document, e.g. for the OpenAPI description. */
    public JsonNode json() {
        return json.deepCopy();
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
