package com.mhamzah.gateway.mapping;

import com.mhamzah.gateway.extension.ExecutionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * A step's request body written out as text (XML, a whole SOAP envelope, JSON, ...) with {@code ${...}}
 * placeholders, sent as it is: namespaces, prefixes and attributes stay exactly as written.
 *
 * <pre>
 * ${request.path.accountNo}        a context value ($.request..., $.steps..., $.correlationId)
 * ${body.amount}                   the step's mapped request body (its BODY rules, with converters and lookups)
 * ${request.query.option:01}       with a default for a missing or null value (else empty)
 * </pre>
 *
 * Values are escaped for the template's format: XML (it starts with {@code <}) escapes {@code & < > " '}; JSON (it
 * starts with {@code {} or {@code [}) escapes like inside a JSON string, so write {@code "${x}"} for text and
 * {@code ${n}} for a number; an object or array is inserted as JSON (write {@code ${list:[]}} without quotes). In XML
 * an object or array is inserted as its (escaped) JSON text.
 */
public final class BodyTemplate {

    /** XML, JSON or anything else (sent as text/plain unless the step's headers say otherwise). */
    public enum Kind {
        XML("text/xml; charset=UTF-8"),
        JSON("application/json"),
        TEXT("text/plain; charset=UTF-8");

        private final String contentType;

        Kind(String contentType) {
            this.contentType = contentType;
        }

        public String contentType() {
            return contentType;
        }
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-.\\[\\]*']+");

    private record Slot(int start, int end, JsonPath path, boolean fromBody, String fallback) {}

    private final String text;
    private final Kind kind;
    private final List<Slot> slots;

    private BodyTemplate(String text, Kind kind, List<Slot> slots) {
        this.text = text;
        this.kind = kind;
        this.slots = List.copyOf(slots);
    }

    /** @throws IllegalArgumentException with what is wrong (an unclosed or malformed placeholder) */
    public static BodyTemplate parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("body_template is empty");
        }
        String lead = text.stripLeading();
        Kind kind = lead.startsWith("<") ? Kind.XML : lead.startsWith("{") || lead.startsWith("[") ? Kind.JSON : Kind.TEXT;
        List<Slot> slots = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(text);
        int searched = 0;
        while (m.find()) {
            String unclosed = text.substring(searched, m.start());
            if (unclosed.contains("${")) {
                throw new IllegalArgumentException("unclosed ${ near '" + snippet(text, searched + unclosed.indexOf("${")) + "'");
            }
            String expr = m.group(1).strip();
            int colon = expr.indexOf(':');
            String path = (colon < 0 ? expr : expr.substring(0, colon)).strip();
            String fallback = colon < 0 ? null : expr.substring(colon + 1);
            if (path.isEmpty() || !NAME.matcher(path).matches()) {
                throw new IllegalArgumentException("placeholder ${" + expr + "}: write a path such as ${request.path.id},"
                        + " ${steps.x.body.code} or ${body.amount}");
            }
            String root = path.split("[.\\[]", 2)[0];
            if (!List.of("request", "steps", "correlationId", "body").contains(root)) {
                throw new IllegalArgumentException("placeholder ${" + expr + "} must start with request, steps,"
                        + " correlationId or body");
            }
            JsonPath compiled;
            try {
                compiled = JsonPath.compile("$." + path);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("placeholder ${" + expr + "}: " + e.getMessage(), e);
            }
            if (compiled.wildcardCount() > 0) {
                throw new IllegalArgumentException("placeholder ${" + expr + "}: [*] is not allowed here; use an index such as [0]");
            }
            slots.add(new Slot(m.start(), m.end(), compiled, root.equals("body"), fallback));
            searched = m.end();
        }
        if (text.substring(searched).contains("${")) {
            throw new IllegalArgumentException("unclosed ${ near '" + snippet(text, searched + text.substring(searched).indexOf("${")) + "'");
        }
        return new BodyTemplate(text, kind, slots);
    }

    private static String snippet(String text, int at) {
        return text.substring(at, Math.min(text.length(), at + 30)).replaceAll("\\s+", " ");
    }

    public Kind kind() {
        return kind;
    }

    /** The context paths the placeholders read ({@code body...} ones excluded), for the step-order checks. */
    public List<JsonPath> references() {
        return slots.stream().filter(s -> !s.fromBody()).map(Slot::path).toList();
    }

    /** The text with every placeholder replaced, escaped for the template's format. */
    public String render(ExecutionContext ctx, JsonNode mappedBody) {
        StringBuilder sb = new StringBuilder(text.length() + 64);
        int at = 0;
        for (Slot s : slots) {
            sb.append(text, at, s.start());
            JsonNode v = s.fromBody() ? read(s.path(), mappedBody) : ctx.read(s.path());
            if (kind == Kind.JSON && !JsonValues.isAbsent(v) && (v.isObject() || v.isArray())) {
                sb.append(JsonValues.MAPPER.writeValueAsString(v)); // an object or array goes in as JSON
            } else {
                String value = JsonValues.isAbsent(v) ? (s.fallback() == null ? "" : s.fallback())
                        : v.isValueNode() ? v.asString() : JsonValues.MAPPER.writeValueAsString(v);
                sb.append(escape(value));
            }
            at = s.end();
        }
        return sb.append(text, at, text.length()).toString();
    }

    /** {@code body.x.y} against the mapped body: drop the leading "body". */
    private static JsonNode read(JsonPath path, JsonNode body) {
        if (body == null) {
            return null;
        }
        JsonNode root = com.mhamzah.gateway.mapping.JsonValues.MAPPER.createObjectNode().set("body", body);
        return path.read(root);
    }

    private String escape(String s) {
        return switch (kind) {
            case XML -> s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                    .replace("'", "&apos;");
            case JSON -> {
                String json = JsonValues.MAPPER.writeValueAsString(s);
                yield json.substring(1, json.length() - 1);
            }
            case TEXT -> s;
        };
    }

    @Override
    public String toString() {
        return kind.name().toLowerCase(Locale.ROOT) + " template, " + slots.size() + " placeholder(s)";
    }
}
