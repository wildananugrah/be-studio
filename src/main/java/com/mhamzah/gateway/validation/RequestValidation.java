package com.mhamzah.gateway.validation;

import com.mhamzah.gateway.condition.Condition;
import com.mhamzah.gateway.extension.ErrorType;
import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.extension.GatewayError;
import com.mhamzah.gateway.mapping.JsonPath;
import com.mhamzah.gateway.mapping.JsonValues;
import com.mhamzah.gateway.mapping.LookupTable;
import com.mhamzah.gateway.schema.CompiledSchema;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import tools.jackson.databind.JsonNode;

/**
 * {@code gw_flow.request_validation}: checks on the inbound request and the request schema chosen by a field's value.
 *
 * <pre>{
 *   "order": "CHECKS_FIRST",            // or SCHEMA_FIRST: which runs first, the checks or the schema
 *   "checks": [                          // in order; the first that fails answers GW-400-VALIDATION
 *     {"field": "$.request.body.purpose", "check": "ENUM", "values": ["new", "modify", "delete"],
 *      "message": "purpose must be new, modify or delete", "code": "PURPOSE_INVALID"}
 *   ],
 *   "schemaBy": {"field": "$.request.body.purpose",
 *                "schemas": {"new": "REQ_NEW", "modify": "REQ_MODIFY", "delete": "REQ_DELETE"}}
 * }</pre>
 *
 * Checks: REQUIRED, ENUM ({@code values}), LOOKUP ({@code lookup}: the keys of a {@code gw_lookup_entry} table),
 * PATTERN ({@code pattern}, the whole text), LENGTH ({@code min} / {@code max} characters, or items of an array),
 * RANGE ({@code min} / {@code max}, a number or numeric text) and EXPR ({@code expr}, an expression that must be true;
 * no field). A missing or null field fails every check unless the check is {@code "optional": true}. A check fails with
 * its {@code message} (or a standard one) and its optional {@code code}.
 *
 * <p>With {@code schemaBy}, the field's value picks the request schema; a value with no schema uses the flow's
 * {@code request_schema_code}, and without one the request fails (GW-400-VALIDATION).
 */
public final class RequestValidation {

    public enum Order { CHECKS_FIRST, SCHEMA_FIRST }

    public enum Kind { REQUIRED, ENUM, LOOKUP, PATTERN, LENGTH, RANGE, EXPR }

    /** Where a check may read: the inbound request. */
    private static final Set<String> REQUEST_PARTS = Set.of("body", "headers", "query", "path");

    /** One compiled check. */
    public record Check(Kind kind, JsonPath field, boolean optional, List<String> values, LookupTable lookup,
            Pattern pattern, BigDecimal min, BigDecimal max, Condition expr, String message, String code) {}

    /** The schema picked by the value of {@code field}. */
    public record SchemaBy(JsonPath field, Map<String, CompiledSchema> schemas) {}

    private final Order order;
    private final List<Check> checks;
    private final SchemaBy schemaBy;

    public RequestValidation(Order order, List<Check> checks, SchemaBy schemaBy) {
        this.order = order == null ? Order.CHECKS_FIRST : order;
        this.checks = List.copyOf(checks);
        this.schemaBy = schemaBy;
    }

    public Order order() {
        return order;
    }

    public List<Check> checks() {
        return checks;
    }

    public SchemaBy schemaBy() {
        return schemaBy;
    }

    /** Every schema {@code schemaBy} can pick (for docs and export). */
    public List<CompiledSchema> schemas() {
        return schemaBy == null ? List.of() : List.copyOf(schemaBy.schemas().values());
    }

    // ------------------------------------------------------------------ run

    /** Runs the checks in order; throws GW-400-VALIDATION with the first that fails. */
    public void check(ExecutionContext ctx) {
        for (Check c : checks) {
            String problem = problem(c, ctx);
            if (problem != null) {
                throw failure(c.field(), c.kind().name(), c.code(), c.message() != null ? c.message() : problem,
                        c.kind() == Kind.REQUIRED || problem.endsWith(" is required"));
            }
        }
    }

    /**
     * The schema for this request: the one {@code schemaBy} picks, else {@code fallback}. Throws GW-400-VALIDATION when
     * {@code schemaBy} picks nothing and there is no fallback.
     */
    public CompiledSchema schemaFor(ExecutionContext ctx, CompiledSchema fallback) {
        if (schemaBy == null) {
            return fallback;
        }
        JsonNode value = ctx.read(schemaBy.field());
        CompiledSchema picked = JsonValues.isAbsent(value) || value.isContainer() ? null
                : schemaBy.schemas().get(JsonValues.keyOf(value));
        if (picked != null || fallback != null) {
            return picked != null ? picked : fallback;
        }
        String shown = JsonValues.isAbsent(value) ? "missing" : "'" + JsonValues.keyOf(value) + "'";
        boolean missing = JsonValues.isAbsent(value);
        throw failure(schemaBy.field(), "SCHEMA_BY", null, missing ? name(schemaBy.field()) + " is required"
                : name(schemaBy.field()) + " must be one of " + schemaBy.schemas().keySet() + " (got " + shown + ")", missing);
    }

    private static String problem(Check c, ExecutionContext ctx) {
        if (c.kind() == Kind.EXPR) {
            try {
                return c.expr().evaluate(ctx) ? null : "condition " + c.expr() + " is false";
            } catch (RuntimeException e) {
                // e.g. text compared with a number: the request's values do not fit the expression
                return "condition " + c.expr() + " cannot be checked with these values";
            }
        }
        JsonNode value = ctx.read(c.field());
        String name = name(c.field());
        if (JsonValues.isAbsent(value)) {
            return c.optional() && c.kind() != Kind.REQUIRED ? null : name + " is required";
        }
        String text = value.isContainer() ? null : JsonValues.keyOf(value);
        return switch (c.kind()) {
            case REQUIRED -> null;
            case ENUM -> text != null && c.values().contains(text) ? null : name + " must be one of " + c.values();
            case LOOKUP -> text != null && c.lookup().entries().containsKey(text) ? null
                    : name + " must be one of " + new java.util.TreeSet<>(c.lookup().entries().keySet());
            case PATTERN -> text != null && c.pattern().matcher(text).matches() ? null
                    : name + " does not match " + c.pattern().pattern();
            case LENGTH -> {
                Integer n = value.isArray() ? Integer.valueOf(value.size()) : text != null ? Integer.valueOf(text.length()) : null;
                yield n != null && within(BigDecimal.valueOf(n), c) ? null : name + " must be " + bounds(c, " characters long");
            }
            case RANGE -> {
                BigDecimal n = number(value);
                yield n != null && within(n, c) ? null : name + " must be a number " + bounds(c, "");
            }
            case EXPR -> null;
        };
    }

    private static boolean within(BigDecimal n, Check c) {
        return (c.min() == null || n.compareTo(c.min()) >= 0) && (c.max() == null || n.compareTo(c.max()) <= 0);
    }

    private static String bounds(Check c, String unit) {
        if (c.min() != null && c.max() != null) {
            return "from " + c.min().toPlainString() + " to " + c.max().toPlainString() + unit;
        }
        return c.min() != null ? "at least " + c.min().toPlainString() + unit : "at most " + c.max().toPlainString() + unit;
    }

    private static BigDecimal number(JsonNode value) {
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (value.isString()) {
            try {
                return new BigDecimal(value.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** {@code purpose} for {@code $.request.body.purpose}, {@code x-channel} for a header: how a client names it. */
    static String name(JsonPath field) {
        List<String> names = field.fieldNames();
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < names.size(); i++) { // after request.body / request.headers / ...
            String n = names.get(i);
            if (n == null) {
                sb.append("[]");
            } else {
                sb.append(sb.isEmpty() ? "" : ".").append(n);
            }
        }
        return sb.isEmpty() ? field.toString() : sb.toString();
    }

    private static GatewayError failure(JsonPath field, String check, String code, String message, boolean missing) {
        return GatewayError.of(ErrorType.REQUEST_VALIDATION_FAILED)
                .message(message)
                .details(List.of(message))
                .validation(new GatewayError.Validation(field == null ? null : field.toString(),
                        field == null ? null : name(field), check, code, missing))
                .clientError(true)
                .build();
    }

    // ------------------------------------------------------------------ compile

    /**
     * Compiles {@code gw_flow.request_validation}; problems go to {@code errors} (prefixed with {@code where}) and the
     * result is then null. Blank text gives null without problems.
     */
    public static RequestValidation compile(String text, String where, Function<String, CompiledSchema> schemas,
            Function<String, LookupTable> lookups, List<String> errors) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int before = errors.size();
        String w = where + " request_validation";
        JsonNode json;
        try {
            json = JsonValues.MAPPER.readTree(text);
        } catch (RuntimeException e) {
            errors.add(w + " is not valid JSON: " + e.getMessage());
            return null;
        }
        if (!json.isObject()) {
            errors.add(w + " must be a JSON object {order, checks, schemaBy}");
            return null;
        }
        for (var e : json.properties()) {
            if (!Set.of("order", "checks", "schemaBy").contains(e.getKey())) {
                errors.add(w + ": unknown field '" + e.getKey() + "' (use order, checks, schemaBy)");
            }
        }
        Order order = Order.CHECKS_FIRST;
        JsonNode o = json.get("order");
        if (o != null && !o.isNull()) {
            try {
                order = Order.valueOf(o.asString().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.add(w + ": order '" + o.asString() + "' must be CHECKS_FIRST or SCHEMA_FIRST");
            }
        }
        List<Check> checks = new ArrayList<>();
        JsonNode list = json.get("checks");
        if (list != null && !list.isNull()) {
            if (!list.isArray()) {
                errors.add(w + ": checks must be an array");
            } else {
                int n = 0;
                for (JsonNode c : list) {
                    Check check = check(c, w + " check " + (++n), lookups, errors);
                    if (check != null) {
                        checks.add(check);
                    }
                }
            }
        }
        SchemaBy schemaBy = null;
        JsonNode by = json.get("schemaBy");
        if (by != null && !by.isNull()) {
            schemaBy = schemaBy(by, w + " schemaBy", schemas, errors);
        }
        return errors.size() > before ? null : new RequestValidation(order, checks, schemaBy);
    }

    private static Check check(JsonNode c, String w, Function<String, LookupTable> lookups, List<String> errors) {
        if (!c.isObject()) {
            errors.add(w + " must be an object {field, check, ...}");
            return null;
        }
        int before = errors.size();
        String kindText = text(c, "check");
        Kind kind = null;
        try {
            kind = kindText == null ? null : Kind.valueOf(kindText.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // reported below
        }
        if (kind == null) {
            errors.add(w + ": check '" + kindText + "' must be one of REQUIRED, ENUM, LOOKUP, PATTERN, LENGTH, RANGE, EXPR");
            return null;
        }
        String label = w + " (" + kind + ")";
        JsonPath field = null;
        if (kind != Kind.EXPR) {
            field = requestPath(text(c, "field"), label + " field", errors);
        }
        List<String> values = List.of();
        LookupTable lookup = null;
        Pattern pattern = null;
        BigDecimal min = null;
        BigDecimal max = null;
        Condition expr = null;
        switch (kind) {
            case ENUM -> {
                JsonNode v = c.get("values");
                if (v == null || !v.isArray() || v.isEmpty()) {
                    errors.add(label + ": values must be a non-empty array, e.g. [\"new\", \"modify\"]");
                } else {
                    List<String> out = new ArrayList<>();
                    v.forEach(x -> out.add(JsonValues.keyOf(x)));
                    values = out;
                }
            }
            case LOOKUP -> {
                String code = text(c, "lookup");
                lookup = code == null ? null : lookups.apply(code);
                if (lookup == null) {
                    errors.add(label + ": lookup '" + code + "' has no gw_lookup_entry rows");
                }
            }
            case PATTERN -> {
                String p = text(c, "pattern");
                try {
                    pattern = p == null ? null : Pattern.compile(p);
                } catch (PatternSyntaxException e) {
                    errors.add(label + ": pattern is not a valid regular expression: " + e.getDescription());
                }
                if (p == null) {
                    errors.add(label + ": pattern is required, e.g. \"^[0-9]{10}$\"");
                }
            }
            case LENGTH, RANGE -> {
                min = decimal(c, "min", label, errors);
                max = decimal(c, "max", label, errors);
                if (min == null && max == null) {
                    errors.add(label + ": set min, max or both");
                }
            }
            case EXPR -> {
                String e = text(c, "expr");
                if (e == null) {
                    errors.add(label + ": expr is required, e.g. \"${request.body.amount} > 0\"");
                } else {
                    try {
                        expr = Condition.compile(e);
                        for (JsonPath p : expr.references()) {
                            if (!"request".equals(p.firstField())) {
                                errors.add(label + ": expr can only read the request (${request...}), not " + p);
                            }
                        }
                    } catch (IllegalArgumentException ex) {
                        errors.add(label + ": expr: " + ex.getMessage());
                    }
                }
            }
            case REQUIRED -> { }
        }
        if (errors.size() > before) {
            return null;
        }
        return new Check(kind, field, c.path("optional").asBoolean(false), values, lookup, pattern, min, max, expr,
                text(c, "message"), text(c, "code"));
    }

    private static SchemaBy schemaBy(JsonNode by, String w, Function<String, CompiledSchema> schemas, List<String> errors) {
        int before = errors.size();
        JsonPath field = requestPath(text(by, "field"), w + " field", errors);
        Map<String, CompiledSchema> out = new LinkedHashMap<>();
        JsonNode map = by.get("schemas");
        if (map == null || !map.isObject() || map.isEmpty()) {
            errors.add(w + ": schemas must map values to schema codes, e.g. {\"new\": \"REQ_NEW\"}");
        } else {
            for (var e : map.properties()) {
                String code = e.getValue().isString() ? e.getValue().asString() : null;
                CompiledSchema s = code == null || code.isBlank() ? null : schemas.apply(code);
                if (s == null) {
                    errors.add(w + ": value '" + e.getKey() + "' references unknown schema '" + code + "'");
                } else {
                    out.put(e.getKey(), s);
                }
            }
        }
        return errors.size() > before ? null : new SchemaBy(field, out);
    }

    /** A path under $.request.body / headers / query / path, without [*]. */
    private static JsonPath requestPath(String text, String w, List<String> errors) {
        if (text == null) {
            errors.add(w + " is required, e.g. $.request.body.purpose");
            return null;
        }
        JsonPath p;
        try {
            p = JsonPath.compile(text.trim());
        } catch (IllegalArgumentException e) {
            errors.add(w + ": " + e.getMessage());
            return null;
        }
        if (!"request".equals(p.firstField()) || !REQUEST_PARTS.contains(p.secondField()) || p.size() < 3) {
            errors.add(w + " must be a field of the request: $.request.body.x, $.request.headers.x, $.request.query.x"
                    + " or $.request.path.x (not " + text + ")");
            return null;
        }
        if (p.wildcardCount() > 0) {
            errors.add(w + ": [*] is not supported here; check the array (LENGTH) or one element ([0])");
            return null;
        }
        return p;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || v.isContainer() || v.asString().isBlank() ? null : v.asString();
    }

    private static BigDecimal decimal(JsonNode n, String field, String w, List<String> errors) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        BigDecimal d = number(v);
        if (d == null) {
            errors.add(w + ": " + field + " must be a number");
        }
        return d;
    }
}
