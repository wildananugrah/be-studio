package com.mhamzah.gateway.mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Small path syntax used for both reading and writing JSON (spec Section 6.2):
 * {@code $}, {@code .name}, {@code ['any name']}, {@code [n]}, {@code [*]}.
 * Reading a missing path yields {@code null}; writing creates intermediate nodes.
 */
public final class JsonPath {

    private static final Pattern SIMPLE_NAME = Pattern.compile("[A-Za-z0-9_\\-]+");

    sealed interface Segment permits Field, Index, Wildcard {}

    record Field(String name) implements Segment {}

    record Index(int index) implements Segment {}

    record Wildcard() implements Segment {}

    /** One match of a wildcard read: the value (null if missing) and the array index chosen for each {@code [*]}. */
    public record Match(int[] indices, JsonNode value) {}

    private final String text;
    private final List<Segment> segments;

    private JsonPath(String text, List<Segment> segments) {
        this.text = text;
        this.segments = List.copyOf(segments);
    }

    public static JsonPath compile(String path) {
        if (path == null || !path.startsWith("$")) {
            throw new IllegalArgumentException("Path must start with '$': " + path);
        }
        List<Segment> segments = new ArrayList<>();
        int i = 1;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '.') {
                int end = i + 1;
                while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') {
                    end++;
                }
                String name = path.substring(i + 1, end);
                if (!SIMPLE_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("Invalid field name '" + name + "' in path: " + path);
                }
                segments.add(new Field(name));
                i = end;
            } else if (c == '[') {
                int close = path.indexOf(']', i);
                if (close < 0) {
                    throw new IllegalArgumentException("Unclosed '[' in path: " + path);
                }
                String inner = path.substring(i + 1, close);
                if (inner.equals("*")) {
                    segments.add(new Wildcard());
                } else if (inner.length() >= 2 && inner.startsWith("'") && inner.endsWith("'")) {
                    segments.add(new Field(inner.substring(1, inner.length() - 1)));
                } else if (inner.matches("\\d+")) {
                    segments.add(new Index(Integer.parseInt(inner)));
                } else {
                    throw new IllegalArgumentException("Invalid bracket segment '[" + inner + "]' in path: " + path);
                }
                i = close + 1;
            } else {
                throw new IllegalArgumentException("Unexpected character '" + c + "' in path: " + path);
            }
        }
        return new JsonPath(path, segments);
    }

    public boolean isRoot() {
        return segments.isEmpty();
    }

    public int wildcardCount() {
        return (int) segments.stream().filter(s -> s instanceof Wildcard).count();
    }

    /** First field name, e.g. {@code request} for {@code $.request.body.x}; null for the root path. */
    public String firstField() {
        return !segments.isEmpty() && segments.get(0) instanceof Field f ? f.name() : null;
    }

    /** Second field name, e.g. {@code inquiry} for {@code $.steps.inquiry.body}. */
    public String secondField() {
        return segments.size() > 1 && segments.get(1) instanceof Field f ? f.name() : null;
    }

    /** One entry per segment: the field name, or null for an array position ({@code [n]} or {@code [*]}). */
    public List<String> fieldNames() {
        List<String> names = new ArrayList<>();
        for (Segment s : segments) {
            names.add(s instanceof Field f ? f.name() : null);
        }
        return names;
    }

    /** The path up to (not including) the first {@code [*]}; e.g. {@code $.a.items} for {@code $.a.items[*].x}. */
    public JsonPath prefixBeforeWildcard() {
        List<Segment> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder("$");
        for (Segment s : segments) {
            if (s instanceof Wildcard) {
                break;
            }
            out.add(s);
            appendText(sb, s);
        }
        return new JsonPath(sb.toString(), out);
    }

    /** Replaces each {@code [*]} in order with the given index. */
    public JsonPath withIndices(int[] indices) {
        List<Segment> out = new ArrayList<>(segments.size());
        StringBuilder sb = new StringBuilder("$");
        int w = 0;
        for (Segment s : segments) {
            Segment resolved = s instanceof Wildcard && w < indices.length ? new Index(indices[w++]) : s;
            out.add(resolved);
            appendText(sb, resolved);
        }
        return new JsonPath(sb.toString(), out);
    }

    /** Reads a path without wildcards. Returns null when any segment is missing. */
    public JsonNode read(JsonNode root) {
        if (wildcardCount() > 0) {
            throw new IllegalStateException("Use readAll for wildcard path " + text);
        }
        JsonNode current = root;
        for (Segment s : segments) {
            current = step(current, s);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** Reads a path with wildcards, returning one match per array element reached by the wildcards. */
    public List<Match> readAll(JsonNode root) {
        List<Match> out = new ArrayList<>();
        collect(root, 0, new int[0], out);
        return out;
    }

    private void collect(JsonNode current, int from, int[] indices, List<Match> out) {
        for (int i = from; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (s instanceof Wildcard) {
                if (current == null || !current.isArray()) {
                    return;
                }
                for (int e = 0; e < current.size(); e++) {
                    int[] next = java.util.Arrays.copyOf(indices, indices.length + 1);
                    next[indices.length] = e;
                    collect(current.get(e), i + 1, next, out);
                }
                return;
            }
            current = step(current, s);
        }
        out.add(new Match(indices, current));
    }

    private static JsonNode step(JsonNode current, Segment s) {
        if (current == null) {
            return null;
        }
        JsonNode next = switch (s) {
            case Field f -> current.isObject() ? current.get(f.name()) : null;
            case Index ix -> current.isArray() ? current.get(ix.index()) : null;
            case Wildcard w -> throw new IllegalStateException("wildcard");
        };
        return next == null || next.isMissingNode() ? null : next;
    }

    /**
     * Writes {@code value} at this path, creating intermediate objects and arrays.
     * Writing to {@code $} merges the fields of an object value into the root.
     */
    public void write(ObjectNode root, JsonNode value) {
        if (wildcardCount() > 0) {
            throw new IllegalStateException("Cannot write to wildcard path " + text + "; resolve indices first");
        }
        if (segments.isEmpty()) {
            if (!(value instanceof ObjectNode obj)) {
                throw new IllegalArgumentException("Only an object can be written to '$'");
            }
            root.setAll(obj);
            return;
        }
        JsonNode current = root;
        for (int i = 0; i < segments.size() - 1; i++) {
            current = child(current, segments.get(i), segments.get(i + 1));
        }
        Segment last = segments.getLast();
        switch (last) {
            case Field f -> ((ObjectNode) current).set(f.name(), value);
            case Index ix -> {
                ArrayNode arr = (ArrayNode) current;
                pad(arr, ix.index(), null);
                arr.set(ix.index(), value);
            }
            case Wildcard w -> throw new IllegalStateException("wildcard");
        }
    }

    /** Returns the container at {@code s} under {@code current}, creating it with the type {@code next} needs. */
    private static JsonNode child(JsonNode current, Segment s, Segment next) {
        boolean wantArray = next instanceof Index;
        JsonNodeFactory f = JsonNodeFactory.instance;
        switch (s) {
            case Field field -> {
                ObjectNode obj = asObject(current, field.name());
                JsonNode existing = obj.get(field.name());
                if (existing == null || !(wantArray ? existing.isArray() : existing.isObject())) {
                    existing = wantArray ? f.arrayNode() : f.objectNode();
                    obj.set(field.name(), existing);
                }
                return existing;
            }
            case Index ix -> {
                if (!(current instanceof ArrayNode arr)) {
                    throw new IllegalArgumentException("Expected array at index segment [" + ix.index() + "]");
                }
                pad(arr, ix.index(), wantArray ? null : s);
                JsonNode existing = arr.get(ix.index());
                if (existing == null || !(wantArray ? existing.isArray() : existing.isObject())) {
                    existing = wantArray ? f.arrayNode() : f.objectNode();
                    arr.set(ix.index(), existing);
                }
                return existing;
            }
            case Wildcard w -> throw new IllegalStateException("wildcard");
        }
    }

    private static ObjectNode asObject(JsonNode node, String field) {
        if (!(node instanceof ObjectNode obj)) {
            throw new IllegalArgumentException("Expected object when writing field '" + field + "'");
        }
        return obj;
    }

    /** Ensures {@code arr} has an element at {@code index}; padding with empty objects (or nulls when {@code objectPad} is null). */
    private static void pad(ArrayNode arr, int index, Segment objectPad) {
        while (arr.size() <= index) {
            if (objectPad != null) {
                arr.add(JsonNodeFactory.instance.objectNode());
            } else {
                arr.addNull();
            }
        }
    }

    private static void appendText(StringBuilder sb, Segment s) {
        switch (s) {
            case Field f -> {
                if (SIMPLE_NAME.matcher(f.name()).matches()) {
                    sb.append('.').append(f.name());
                } else {
                    sb.append("['").append(f.name()).append("']");
                }
            }
            case Index ix -> sb.append('[').append(ix.index()).append(']');
            case Wildcard w -> sb.append("[*]");
        }
    }

    @Override
    public String toString() {
        return text;
    }
}
