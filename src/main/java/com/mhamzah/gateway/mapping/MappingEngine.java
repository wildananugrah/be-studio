package com.mhamzah.gateway.mapping;

import com.mhamzah.gateway.extension.ErrorType;
import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.extension.GatewayError;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Applies mapping rules in order (spec Section 6.3): value, default, lookup, converter, field handler,
 * required check, write.
 */
public class MappingEngine {

    public MappedMessage apply(List<CompiledRule> rules, ExecutionContext ctx) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> query = new LinkedHashMap<>();
        Map<String, String> pathVars = new LinkedHashMap<>();
        for (CompiledRule rule : rules) {
            if (rule.source() != null && rule.source().wildcardCount() > 0) {
                applyWildcard(rule, ctx, body);
            } else {
                JsonNode value = rule.constant() != null ? rule.constant() : rule.source() != null ? ctx.read(rule.source()) : null;
                value = process(rule, value, ctx);
                if (value != null) {
                    write(rule, value, body, headers, query, pathVars);
                }
            }
        }
        return new MappedMessage(body, headers, query, pathVars);
    }

    private void applyWildcard(CompiledRule rule, ExecutionContext ctx, ObjectNode body) {
        JsonPath source = rule.source();
        List<JsonPath.Match> matches = ctx.readAll(source);
        if (matches.isEmpty()) {
            JsonNode container = ctx.read(source.prefixBeforeWildcard());
            if (container == null || !container.isArray()) {
                // the source array itself is missing
                if (rule.required()) {
                    throw missing(rule);
                }
                return;
            }
        }
        boolean positional = rule.targetPath().wildcardCount() > 0;
        if (positional && matches.isEmpty()) {
            writeBody(rule, rule.targetPath().prefixBeforeWildcard(), JsonNodeFactory.instance.arrayNode(), body);
            return;
        }
        ArrayNode collected = JsonNodeFactory.instance.arrayNode();
        for (JsonPath.Match m : matches) {
            JsonNode value = process(rule, m.value(), ctx);
            if (value == null) {
                continue;
            }
            if (positional) {
                writeBody(rule, rule.targetPath().withIndices(m.indices()), value, body);
            } else {
                collected.add(value);
            }
        }
        if (!positional) {
            writeBody(rule, rule.targetPath(), collected, body);
        }
    }

    /** Default, lookup, converter, handler and required check. Returns null when the value is missing. */
    private JsonNode process(CompiledRule rule, JsonNode value, ExecutionContext ctx) {
        if (JsonValues.isAbsent(value) && rule.defaultValue() != null) {
            value = rule.defaultValue();
        }
        if (rule.lookup() != null) {
            value = rule.lookup().translate(value);
        }
        if (rule.converter() != null) {
            try {
                value = rule.converter().apply(value);
            } catch (ConversionException e) {
                throw GatewayError.of(ErrorType.MAPPING_ERROR)
                        .message("Conversion failed for " + rule.describe())
                        .details(List.of(rule.describe() + ": " + e.getMessage()))
                        .clientError(rule.sourcedFromRequest())
                        .cause(e)
                        .build();
            }
        }
        if (rule.fieldHandler() != null) {
            try {
                value = rule.fieldHandler().handle(value, ctx);
            } catch (GatewayError e) {
                throw e;
            } catch (RuntimeException e) {
                throw GatewayError.of(ErrorType.HANDLER_ERROR)
                        .message("Field handler '" + rule.fieldHandlerName() + "' failed for " + rule.describe())
                        .cause(e)
                        .build();
            }
        }
        if (value == null || value.isMissingNode()) {
            if (rule.required()) {
                throw missing(rule);
            }
            return null;
        }
        return value;
    }

    private void write(CompiledRule rule, JsonNode value, ObjectNode body,
            Map<String, String> headers, Map<String, String> query, Map<String, String> pathVars) {
        switch (rule.targetType()) {
            case BODY -> writeBody(rule, rule.targetPath(), value, body);
            case HEADER -> putText(headers, rule.targetName(), value);
            case QUERY -> putText(query, rule.targetName(), value);
            case PATH -> putText(pathVars, rule.targetName(), value);
        }
    }

    private static void putText(Map<String, String> target, String name, JsonNode value) {
        String text = JsonValues.keyOf(value);
        if (text != null) {
            target.put(name, text);
        }
    }

    private static void writeBody(CompiledRule rule, JsonPath path, JsonNode value, ObjectNode body) {
        try {
            path.write(body, value);
        } catch (IllegalArgumentException e) {
            throw GatewayError.of(ErrorType.MAPPING_ERROR)
                    .message("Cannot write " + rule.describe())
                    .details(List.of(rule.describe() + ": " + e.getMessage()))
                    .cause(e)
                    .build();
        }
    }

    private static GatewayError missing(CompiledRule rule) {
        String what = rule.source() != null ? rule.source().toString() : "value";
        return GatewayError.of(ErrorType.MAPPING_ERROR)
                .message("Required " + what + " is missing")
                .details(List.of("Required field " + what + " is missing"))
                .clientError(rule.sourcedFromRequest())
                .build();
    }
}
