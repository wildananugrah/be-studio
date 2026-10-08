package com.mhamzah.gateway.mapping;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Built-in converters (spec Section 6.4). Specs look like {@code NAME} or {@code NAME:arg1:arg2},
 * with {@code \:} for a literal colon inside an argument.
 */
public final class Converters {

    /** A compiled converter. Absent values (null / JSON null) pass through unchanged. */
    @FunctionalInterface
    public interface Converter {
        JsonNode convert(JsonNode value);

        default JsonNode apply(JsonNode value) {
            return JsonValues.isAbsent(value) ? value : convert(value);
        }
    }

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private Converters() {}

    public static Converter parse(String spec) {
        List<String> parts = split(spec);
        String name = parts.getFirst().trim().toUpperCase(Locale.ROOT);
        List<String> args = parts.subList(1, parts.size());
        return switch (name) {
            case "TO_STRING" -> noArgs(name, args, v -> F.stringNode(text(v)));
            case "TO_NUMBER" -> noArgs(name, args, Converters::toNumber);
            case "TO_BOOLEAN" -> noArgs(name, args, Converters::toBoolean);
            case "TRIM" -> noArgs(name, args, v -> F.stringNode(text(v).trim()));
            case "UPPER" -> noArgs(name, args, v -> F.stringNode(text(v).toUpperCase(Locale.ROOT)));
            case "LOWER" -> noArgs(name, args, v -> F.stringNode(text(v).toLowerCase(Locale.ROOT)));
            case "PAD_LEFT" -> pad(name, args, true);
            case "PAD_RIGHT" -> pad(name, args, false);
            case "SUBSTRING" -> substring(args);
            case "DATE_FORMAT" -> dateFormat(args);
            case "DECIMAL_SCALE" -> decimalScale(args);
            default -> throw new IllegalArgumentException("Unknown converter '" + name + "'");
        };
    }

    private static Converter noArgs(String name, List<String> args, UnaryOperator<JsonNode> fn) {
        requireArgs(name, args, 0, 0);
        return fn::apply;
    }

    private static Converter pad(String name, List<String> args, boolean left) {
        requireArgs(name, args, 2, 2);
        int length = nonNegativeInt(name, args.get(0));
        if (args.get(1).length() != 1) {
            throw new IllegalArgumentException(name + " pad character must be exactly one character");
        }
        char padChar = args.get(1).charAt(0);
        return v -> {
            String s = text(v);
            if (s.length() >= length) {
                return F.stringNode(s);
            }
            String padding = String.valueOf(padChar).repeat(length - s.length());
            return F.stringNode(left ? padding + s : s + padding);
        };
    }

    private static Converter substring(List<String> args) {
        requireArgs("SUBSTRING", args, 1, 2);
        int begin = nonNegativeInt("SUBSTRING", args.get(0));
        Integer end = args.size() > 1 ? nonNegativeInt("SUBSTRING", args.get(1)) : null;
        if (end != null && end < begin) {
            throw new IllegalArgumentException("SUBSTRING end must be >= begin");
        }
        return v -> {
            String s = text(v);
            int b = Math.min(begin, s.length());
            int e = end == null ? s.length() : Math.min(end, s.length());
            return F.stringNode(s.substring(b, e));
        };
    }

    private static Converter dateFormat(List<String> args) {
        requireArgs("DATE_FORMAT", args, 2, 2);
        DateTimeFormatter in = formatter(args.get(0));
        DateTimeFormatter out = formatter(args.get(1));
        return v -> {
            try {
                TemporalAccessor parsed = in.parse(text(v));
                return F.stringNode(out.format(parsed));
            } catch (DateTimeException e) {
                throw new ConversionException("DATE_FORMAT cannot convert '" + text(v) + "': " + e.getMessage(), e);
            }
        };
    }

    private static Converter decimalScale(List<String> args) {
        requireArgs("DECIMAL_SCALE", args, 1, 1);
        int scale = nonNegativeInt("DECIMAL_SCALE", args.get(0));
        return v -> F.numberNode(decimal(v).setScale(scale, RoundingMode.HALF_UP));
    }

    private static JsonNode toNumber(JsonNode v) {
        if (v.isNumber()) {
            return v;
        }
        BigDecimal d = decimal(v);
        if (d.scale() > 0) {
            return F.numberNode(d);
        }
        try {
            return F.numberNode(d.longValueExact());
        } catch (ArithmeticException ignored) {
            return F.numberNode(d.toBigInteger());
        }
    }

    private static JsonNode toBoolean(JsonNode v) {
        if (v.isBoolean()) {
            return v;
        }
        return switch (text(v).trim().toUpperCase(Locale.ROOT)) {
            case "TRUE", "Y", "YES", "1" -> F.booleanNode(true);
            case "FALSE", "N", "NO", "0" -> F.booleanNode(false);
            default -> throw new ConversionException("TO_BOOLEAN cannot convert '" + text(v) + "'");
        };
    }

    private static BigDecimal decimal(JsonNode v) {
        if (v.isNumber()) {
            return v.decimalValue();
        }
        try {
            return new BigDecimal(text(v).trim());
        } catch (NumberFormatException e) {
            throw new ConversionException("Not a number: '" + text(v) + "'", e);
        }
    }

    private static String text(JsonNode v) {
        return JsonValues.keyOf(v);
    }

    private static DateTimeFormatter formatter(String pattern) {
        try {
            return DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid date pattern '" + pattern + "': " + e.getMessage(), e);
        }
    }

    private static int nonNegativeInt(String name, String s) {
        try {
            int n = Integer.parseInt(s.trim());
            if (n < 0) {
                throw new IllegalArgumentException(name + " argument must be >= 0: " + s);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " argument must be an integer: " + s, e);
        }
    }

    private static void requireArgs(String name, List<String> args, int min, int max) {
        if (args.size() < min || args.size() > max) {
            String expected = min == max ? String.valueOf(min) : min + "-" + max;
            throw new IllegalArgumentException(name + " expects " + expected + " argument(s), got " + args.size());
        }
    }

    /** Splits on ':' honoring the {@code \:} escape. */
    static List<String> split(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("Converter spec is empty");
        }
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (c == '\\' && i + 1 < spec.length() && spec.charAt(i + 1) == ':') {
                cur.append(':');
                i++;
            } else if (c == ':') {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        return parts;
    }
}
