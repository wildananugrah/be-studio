package com.mhamzah.gateway.studio.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits a document code block into coloured pieces for Word and PDF: an HTTP message (request or status line,
 * headers, then its body), JSON or XML. Anything else (log lines) stays one plain piece. The pieces joined give the
 * text back unchanged.
 */
final class CodeHighlighter {

    /** What a piece is; each kind has one colour, light enough to print on the light code background. */
    enum Kind {
        PLAIN("2B2D33"),
        PUNCT("6A6D75"),
        KEY("1F5FAD"),
        STRING("2E7D32"),
        NUMBER("B5530A"),
        LITERAL("8E3BA8"),
        TAG("1F5FAD"),
        ATTR("8E3BA8"),
        COMMENT("8A8C92"),
        HTTP_LINE("17181C"),
        HEADER("8E3BA8");

        final String hex;

        Kind(String hex) {
            this.hex = hex;
        }
    }

    record Piece(String text, Kind kind) {}

    /** {@code json}, {@code xml}, {@code http} or {@code ""}: the language of a Markdown code fence. */
    static String language(String text) {
        String t = text.stripLeading();
        if (HTTP_START.matcher(t).lookingAt()) {
            return "http";
        }
        return t.startsWith("{") || t.startsWith("[") ? "json" : t.startsWith("<") ? "xml" : "";
    }

    private static final Pattern HTTP_START =
            Pattern.compile("(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS) \\S+|HTTP[/ ]\\S*");
    private static final Pattern HEADER_LINE = Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]+: ?.*");

    private CodeHighlighter() {}

    static List<Piece> highlight(String text) {
        List<Piece> out = new ArrayList<>();
        if (!language(text).equals("http")) {
            body(text, out);
            return out;
        }
        // start line and headers, then (after the blank line) the body
        int at = 0;
        boolean first = true;
        while (at < text.length()) {
            int nl = text.indexOf('\n', at);
            String line = nl < 0 ? text.substring(at) : text.substring(at, nl);
            if (line.isBlank() && !first) {
                break;
            }
            if (first) {
                out.add(new Piece(line, Kind.HTTP_LINE));
            } else if (HEADER_LINE.matcher(line).matches()) {
                int colon = line.indexOf(':');
                out.add(new Piece(line.substring(0, colon), Kind.HEADER));
                out.add(new Piece(line.substring(colon), Kind.PLAIN));
            } else {
                out.add(new Piece(line, Kind.PLAIN));
            }
            first = false;
            if (nl < 0) {
                return out;
            }
            out.add(new Piece("\n", Kind.PLAIN));
            at = nl + 1;
        }
        body(text.substring(at), out);
        return out;
    }

    private static void body(String text, List<Piece> out) {
        String t = text.stripLeading();
        if (t.startsWith("{") || t.startsWith("[")) {
            json(text, out);
        } else if (t.startsWith("<")) {
            xml(text, out);
        } else if (!text.isEmpty()) {
            out.add(new Piece(text, Kind.PLAIN));
        }
    }

    private static void json(String s, List<Piece> out) {
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (j < n && s.charAt(j) != '"' && s.charAt(j) != '\n') {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j + 1, n);
                // a string followed by ':' is a key
                int k = j;
                while (k < n && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) {
                    k++;
                }
                out.add(new Piece(s.substring(i, j), k < n && s.charAt(k) == ':' ? Kind.KEY : Kind.STRING));
                i = j;
            } else if (c == '-' || Character.isDigit(c)) {
                int j = i + 1;
                while (j < n && "0123456789.eE+-".indexOf(s.charAt(j)) >= 0) {
                    j++;
                }
                out.add(new Piece(s.substring(i, j), Kind.NUMBER));
                i = j;
            } else if (s.startsWith("true", i) || s.startsWith("null", i)) {
                out.add(new Piece(s.substring(i, i + 4), Kind.LITERAL));
                i += 4;
            } else if (s.startsWith("false", i)) {
                out.add(new Piece("false", Kind.LITERAL));
                i += 5;
            } else if ("{}[]:,".indexOf(c) >= 0) {
                out.add(new Piece(String.valueOf(c), Kind.PUNCT));
                i++;
            } else {
                int j = i + 1;
                while (j < n && "\"{}[]:,-0123456789".indexOf(s.charAt(j)) < 0 && !s.startsWith("true", j)
                        && !s.startsWith("false", j) && !s.startsWith("null", j)) {
                    j++;
                }
                out.add(new Piece(s.substring(i, j), Kind.PLAIN));
                i = j;
            }
        }
    }

    private static final Pattern XML_NAME = Pattern.compile("[A-Za-z_][\\w.:-]*");

    private static void xml(String s, List<Piece> out) {
        int i = 0;
        int n = s.length();
        while (i < n) {
            if (s.startsWith("<!--", i)) {
                int end = s.indexOf("-->", i);
                end = end < 0 ? n : end + 3;
                out.add(new Piece(s.substring(i, end), Kind.COMMENT));
                i = end;
            } else if (s.startsWith("<![CDATA[", i)) {
                int end = s.indexOf("]]>", i);
                end = end < 0 ? n : end + 3;
                out.add(new Piece(s.substring(i, end), Kind.STRING));
                i = end;
            } else if (s.charAt(i) == '<') {
                int end = s.indexOf('>', i);
                end = end < 0 ? n : end + 1;
                tag(s.substring(i, end), out);
                i = end;
            } else {
                int end = s.indexOf('<', i);
                end = end < 0 ? n : end;
                out.add(new Piece(s.substring(i, end), Kind.PLAIN));
                i = end;
            }
        }
    }

    /** {@code <ns:name attr="v" ...>}: brackets, the name, attribute names and their values. */
    private static void tag(String t, List<Piece> out) {
        int i = 1;
        int n = t.length();
        StringBuilder open = new StringBuilder("<");
        while (i < n && "/?!".indexOf(t.charAt(i)) >= 0) {
            open.append(t.charAt(i++));
        }
        out.add(new Piece(open.toString(), Kind.PUNCT));
        var m = XML_NAME.matcher(t).region(i, n);
        if (m.lookingAt()) {
            out.add(new Piece(m.group(), Kind.TAG));
            i = m.end();
        }
        while (i < n) {
            char c = t.charAt(i);
            if (c == '"' || c == '\'') {
                int end = t.indexOf(c, i + 1);
                end = end < 0 ? n : end + 1;
                out.add(new Piece(t.substring(i, end), Kind.STRING));
                i = end;
            } else if (Character.isLetter(c) || c == '_') {
                m = XML_NAME.matcher(t).region(i, n);
                if (m.lookingAt()) {
                    out.add(new Piece(m.group(), Kind.ATTR));
                    i = m.end();
                } else {
                    out.add(new Piece(String.valueOf(c), Kind.PLAIN));
                    i++;
                }
            } else if ("/?>=".indexOf(c) >= 0) {
                out.add(new Piece(String.valueOf(c), Kind.PUNCT));
                i++;
            } else {
                out.add(new Piece(String.valueOf(c), Kind.PLAIN));
                i++;
            }
        }
    }
}
