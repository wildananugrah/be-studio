package com.mhamzah.gateway.mapping;

import com.mhamzah.gateway.extension.ExecutionContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * An error map from {@code gw_error_map}: a list of JSON objects (e.g. one per error code, with its HTTP status,
 * service and messages). A mapping rule reads one field of the entry that matches its conditions.
 */
public record ErrorMap(String code, List<JsonNode> entries) {

    /**
     * The row of a lookup table that attaches an error map to it, e.g. in {@code SNAP_ERR_<target system>}: its value
     * says which map, which fields to take and the conditions (see {@link Fields}).
     */
    public static final String LOOKUP_KEY = "@errorMap";
    /** A placeholder in a template text: anything in braces, e.g. {@code {applicationNo}} or {@code {master.currency()}}. */
    static final Pattern PLACEHOLDER = Pattern.compile("\\{[^{}]*}");
    /** Longer values are not matched against templates (a message, not a document). */
    static final int MAX_TEMPLATE_INPUT = 4000;

    public ErrorMap {
        entries = List.copyOf(entries);
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

    /**
     * One condition of a rule: the entry's {@code field} equals the value at {@code source}, or {@code value}. With
     * {@code template}, the entry's field is a template instead: its text must match literally, and each
     * {@code {name}} in it matches any text (captured under that name).
     */
    public record Condition(String field, JsonPath source, JsonNode value, boolean template) {

        public Condition(String field, JsonPath source, JsonNode value) {
            this(field, source, value, false);
        }
    }

    /**
     * An entry's text compiled as a template: {@code names} are its placeholders in order (group i + 1 of
     * {@code pattern}); {@code literal} is the length of its fixed text, used to prefer the most specific match.
     */
    record Template(Pattern pattern, List<String> names, int literal) {

        static Template of(String text) {
            StringBuilder regex = new StringBuilder();
            List<String> names = new ArrayList<>();
            int literal = 0;
            int last = 0;
            Matcher m = PLACEHOLDER.matcher(text);
            while (m.find()) {
                String fixed = text.substring(last, m.start());
                regex.append(Pattern.quote(fixed)).append("(.*?)");
                literal += fixed.length();
                names.add(m.group().substring(1, m.group().length() - 1));
                last = m.end();
            }
            String rest = text.substring(last);
            regex.append(Pattern.quote(rest));
            literal += rest.length();
            return new Template(Pattern.compile(regex.toString(), Pattern.DOTALL), names, literal);
        }

        /** Placeholder name to the captured text, or null when {@code value} does not match. */
        Map<String, String> match(String value) {
            Matcher m = pattern.matcher(value);
            if (!m.matches()) {
                return null;
            }
            Map<String, String> out = new LinkedHashMap<>();
            for (int i = 0; i < names.size(); i++) {
                out.putIfAbsent(names.get(i), m.group(i + 1));
            }
            return out;
        }
    }

    /**
     * What one lookup found, for the value and for the debug line in the log (and so in Studio's audit trail):
     * {@code values} the condition values read, {@code matches} how many entries matched, {@code entry} the position
     * in the map of the one taken (null when none) and {@code value} its output. {@code picked} is that entry and
     * {@code captured} what its templates captured, for {@link #take}.
     */
    public record Result(JsonNode value, List<String> values, int matches, Integer entry, JsonNode picked,
            Map<String, String> captured, boolean fill) {

        static Result none(List<String> values, int matches) {
            return new Result(null, values, matches, null, null, Map.of(), false);
        }

        /** Field {@code name} of the picked entry, its placeholders filled when the lookup fills; null when none. */
        public JsonNode take(String name) {
            return Lookup.take(picked, name, fill, captured);
        }
    }

    /**
     * An error map attached to a lookup table ({@link #LOOKUP_KEY}): the picked entry's fields, under new names
     * ({@code fields}: name in the output to the entry's field, in order).
     */
    public record Fields(Lookup lookup, Map<String, String> fields) {

        public Fields {
            fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }
    }

    /**
     * What a rule takes from a map: field {@code output} of an entry matching every condition. The matching entries
     * are ordered by their fixed template text, most first (so {@code "Application {id} not found"} comes before
     * {@code "{message}"}), then by their order in the map; {@code index} picks one of them (0 = the first, the best
     * match). With {@code fill}, the placeholders in a text output are replaced by what the templates captured under
     * the same names.
     */
    public record Lookup(ErrorMap map, List<Condition> where, String output, boolean fill, int index,
            Map<String, Template[]> templates) {

        public Lookup(ErrorMap map, List<Condition> where, String output) {
            this(map, where, output, false);
        }

        public Lookup(ErrorMap map, List<Condition> where, String output, boolean fill) {
            this(map, where, output, fill, 0);
        }

        public Lookup(ErrorMap map, List<Condition> where, String output, boolean fill, int index) {
            this(map, where, output, fill, index, compile(map, where));
        }

        public Lookup {
            where = List.copyOf(where);
        }

        /** Every entry's template text of each template field, compiled once (null where the entry has no text). */
        private static Map<String, Template[]> compile(ErrorMap map, List<Condition> where) {
            Map<String, Template[]> out = new HashMap<>();
            for (Condition c : where) {
                if (c.template() && !out.containsKey(c.field())) {
                    Template[] t = new Template[map.entries().size()];
                    for (int i = 0; i < t.length; i++) {
                        String text = JsonValues.keyOf(map.entries().get(i).get(c.field()));
                        t[i] = text == null ? null : Template.of(text.strip());
                    }
                    out.put(c.field(), t);
                }
            }
            return out;
        }

        /** The output field of the picked entry; null when there is none (the rule's default applies). */
        public JsonNode resolve(ExecutionContext ctx) {
            return evaluate(ctx).value();
        }

        /** The lookup with what it found (see {@link Result}). */
        public Result evaluate(ExecutionContext ctx) {
            return evaluate(ctx::read);
        }

        /** The lookup with its sources read from {@code root}, e.g. a failed call {@code {status, headers, body}}. */
        public Result evaluate(JsonNode root) {
            return evaluate(path -> path.read(root));
        }

        private Result evaluate(java.util.function.Function<JsonPath, JsonNode> reader) {
            List<String> values = new ArrayList<>();
            for (Condition c : where) {
                JsonNode v = c.source() != null ? reader.apply(c.source()) : c.value();
                if (JsonValues.isAbsent(v)) {
                    values.add(null);
                    return Result.none(values, 0); // a missing value matches nothing
                }
                values.add(JsonValues.keyOf(v));
            }
            record Match(int entry, int score, Map<String, String> captured) {}
            List<Match> matches = new ArrayList<>();
            List<JsonNode> entries = map.entries();
            for (int i = 0; i < entries.size(); i++) {
                JsonNode entry = entries.get(i);
                Map<String, String> captured = new HashMap<>();
                int score = 0;
                boolean all = true;
                for (int j = 0; j < where.size() && all; j++) {
                    Condition c = where.get(j);
                    String value = values.get(j);
                    if (c.template()) {
                        Template t = templates.get(c.field())[i];
                        String input = value.strip();
                        Map<String, String> m = t == null || input.length() > MAX_TEMPLATE_INPUT ? null : t.match(input);
                        all = m != null;
                        if (all) {
                            m.forEach(captured::putIfAbsent);
                            score += t.literal();
                        }
                    } else {
                        all = value.equals(JsonValues.keyOf(entry.get(c.field())));
                    }
                }
                if (all) {
                    matches.add(new Match(i, score, captured));
                }
            }
            // most fixed template text first; List.sort is stable, so equal scores keep the map's order
            matches.sort(java.util.Comparator.comparingInt(Match::score).reversed());
            if (index >= matches.size()) {
                return Result.none(values, matches.size());
            }
            Match picked = matches.get(index);
            JsonNode entry = entries.get(picked.entry());
            return new Result(take(entry, output, fill, picked.captured()), values, matches.size(), picked.entry(),
                    entry, picked.captured(), fill);
        }

        /** Field {@code name} of {@code entry}, with its placeholders filled when {@code fill}; null when none. */
        static JsonNode take(JsonNode entry, String name, boolean fill, Map<String, String> captured) {
            JsonNode out = entry == null || name == null ? null : entry.get(name);
            if (fill && out != null && out.isString()) {
                return JsonNodeFactory.instance.stringNode(fill(out.asString(), captured));
            }
            return out;
        }

        /** {@code text} with each {@code {name}} replaced by the value captured under that name (others stay). */
        static String fill(String text, Map<String, String> captured) {
            if (captured.isEmpty()) {
                return text;
            }
            Matcher m = PLACEHOLDER.matcher(text);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String value = captured.get(m.group().substring(1, m.group().length() - 1));
                m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group()));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        /** The context paths the conditions read. */
        public List<JsonPath> references() {
            return where.stream().map(Condition::source).filter(java.util.Objects::nonNull).toList();
        }
    }
}
