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

/**
 * Applies mapping rules in order (spec Section 6.3): value (source, constant or error map), default, lookup,
 * converter, field handler, required check, write.
 */
public class MappingEngine {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MappingEngine.class);
    /** Longer values are cut in the error map debug line. */
    private static final int LOG_VALUE_MAX = 200;

    public MappedMessage apply(List<CompiledRule> rules, ExecutionContext ctx) {
        JsonNode[] body = {JsonNodeFactory.instance.objectNode()};
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> query = new LinkedHashMap<>();
        Map<String, String> pathVars = new LinkedHashMap<>();
        Integer[] status = {null};
        String fileStep = null;
        for (CompiledRule rule : rules) {
            if (rule.targetType() == TargetType.FILE) {
                // names the step whose downloaded file is the response; the engine takes the file from the context
                fileStep = rule.source().secondField();
            } else if (rule.source() != null && rule.source().wildcardCount() > 0) {
                applyWildcard(rule, ctx, body);
            } else {
                JsonNode value = rule.errorMap() != null ? errorMap(rule, ctx)
                        : rule.constant() != null ? rule.constant() : rule.source() != null ? ctx.read(rule.source()) : null;
                value = process(rule, value, ctx);
                if (value != null) {
                    write(rule, value, body, headers, query, pathVars, status);
                }
            }
        }
        return new MappedMessage(body[0], headers, query, pathVars, status[0], fileStep);
    }

    private void applyWildcard(CompiledRule rule, ExecutionContext ctx, JsonNode[] body) {
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

    /**
     * An error map rule's value, logged so the call's log (and Studio's audit trail) shows how it was found: the
     * condition values, how many entries matched, which one was taken and what it gave.
     */
    private static JsonNode errorMap(CompiledRule rule, ExecutionContext ctx) {
        ErrorMap.Lookup lookup = rule.errorMap();
        ErrorMap.Result r = lookup.evaluate(ctx);
        String where = describeWhere(lookup, r);
        if (r.entry() == null) {
            log.info("error map {} for {}: where {} -> {} match(es), {} -> no value{}", lookup.map().code(), rule.describe(),
                    where, r.matches(), r.matches() == 0 ? "none to take" : "no match at index " + lookup.index(),
                    rule.defaultValue() != null ? ", default " + cut(rule.defaultValue().toString()) : "");
        } else {
            log.info("error map {} for {}: where {} -> {} match(es), took index {} (entry {}): {} = {}", lookup.map().code(),
                    rule.describe(), where, r.matches(), lookup.index(), r.entry(), lookup.output(),
                    r.value() == null ? "(no such field)" : cut(r.value().toString()));
        }
        return r.value();
    }

    /** The conditions with the values read, e.g. {@code httpStatus = '404', message ~ 'Upload …'}, for a log line. */
    public static String describeWhere(ErrorMap.Lookup lookup, ErrorMap.Result r) {
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < lookup.where().size(); i++) {
            ErrorMap.Condition c = lookup.where().get(i);
            where.append(i == 0 ? "" : ", ").append(c.field()).append(c.template() ? " ~ " : " = ")
                    .append(i < r.values().size() ? quote(r.values().get(i)) : "(not read)");
        }
        return where.toString();
    }

    /** {@code value}, cut for a log line. */
    public static String cut(String value) {
        return value.length() <= LOG_VALUE_MAX ? value : value.substring(0, LOG_VALUE_MAX) + "…";
    }

    private static String quote(String value) {
        return value == null ? "(missing)" : "'" + cut(value) + "'";
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

    private void write(CompiledRule rule, JsonNode value, JsonNode[] body,
            Map<String, String> headers, Map<String, String> query, Map<String, String> pathVars, Integer[] status) {
        switch (rule.targetType()) {
            case BODY -> writeBody(rule, rule.targetPath(), value, body);
            case HEADER -> putText(headers, rule.targetName(), value);
            case QUERY -> putText(query, rule.targetName(), value);
            case PATH -> putText(pathVars, rule.targetName(), value);
            case STATUS -> status[0] = httpStatus(rule, value);
            case FILE -> { } // handled in apply
        }
    }

    /** A whole number from 100 to 599 (a number or numeric text, e.g. a backend's 404 or a lookup's "502"). */
    private static int httpStatus(CompiledRule rule, JsonNode value) {
        String text = JsonValues.keyOf(value);
        try {
            int code = Integer.parseInt(text == null ? "" : text.trim());
            if (code >= 100 && code <= 599) {
                return code;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw GatewayError.of(ErrorType.MAPPING_ERROR)
                .message("Invalid HTTP status for " + rule.describe())
                .details(List.of(rule.describe() + ": '" + text + "' is not an HTTP status (100-599)"))
                .build();
    }

    private static void putText(Map<String, String> target, String name, JsonNode value) {
        String text = JsonValues.keyOf(value);
        if (text != null) {
            target.put(name, text);
        }
    }

    private static void writeBody(CompiledRule rule, JsonPath path, JsonNode value, JsonNode[] body) {
        try {
            body[0] = path.write(body[0], value);
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
