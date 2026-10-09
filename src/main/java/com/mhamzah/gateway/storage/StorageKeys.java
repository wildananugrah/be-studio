package com.mhamzah.gateway.storage;

import com.mhamzah.gateway.extension.InboundFile;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Object keys of file storage steps, from the step's {@code path_template}, e.g.
 * {@code /{yyyy}/{MM}/{uuid}-{filename}}. A {@code {var}} is a PATH mapping rule of the step or one of the built-in
 * variables ({@link #BUILT_INS}); a rule wins over a built-in of the same name. Every value is made safe for a key
 * segment (letters, digits, {@code . _ -}; anything else becomes {@code _}; never {@code ..}), so a file name sent
 * by the client can never leave the storage's directory or prefix.
 */
public final class StorageKeys {

    /** Variables a key template may use without a mapping rule. */
    public static final Set<String> BUILT_INS = Set.of("uuid", "correlationId", "filename", "name", "ext", "field",
            "yyyy", "MM", "dd", "HH", "mm", "ss");

    private static final Pattern VAR = Pattern.compile("\\{([^}/]+)}");
    /** Literal text of a template: key characters only, so a template cannot contain "..", "\" or a scheme. */
    private static final Pattern LITERAL = Pattern.compile("[A-Za-z0-9._/\\-]*");

    private StorageKeys() {}

    /** Null when the template is acceptable, otherwise why not. */
    public static String check(String template) {
        if (template == null || !template.startsWith("/")) {
            return "path_template (the object key) must start with '/'";
        }
        String literal = VAR.matcher(template).replaceAll("");
        if (!LITERAL.matcher(literal).matches() || template.contains("..") || template.contains("//")) {
            return "path_template may only contain letters, digits, '.', '_', '-', '/' and {variables} (no '..' or '//')";
        }
        if (template.endsWith("/")) {
            return "path_template must end with a file name, not '/'";
        }
        return null;
    }

    /** The variable names of a template. */
    public static java.util.List<String> variables(String template) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Matcher m = VAR.matcher(template == null ? "" : template);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * The key (without leading '/'), {@code prefix} not included.
     *
     * @param ruleValues PATH rule values of the step (win over built-ins)
     */
    public static String build(String template, Map<String, String> ruleValues, InboundFile file, String correlationId) {
        Map<String, String> values = new LinkedHashMap<>(builtIns(file, correlationId));
        values.putAll(ruleValues);
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(segment(v)));
        }
        m.appendTail(sb);
        String key = sb.toString();
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        return key;
    }

    static Map<String, String> builtIns(InboundFile file, String correlationId) {
        ZonedDateTime now = ZonedDateTime.now();
        Map<String, String> v = new LinkedHashMap<>();
        v.put("uuid", UUID.randomUUID().toString());
        v.put("correlationId", correlationId);
        v.put("yyyy", now.format(DateTimeFormatter.ofPattern("yyyy")));
        v.put("MM", now.format(DateTimeFormatter.ofPattern("MM")));
        v.put("dd", now.format(DateTimeFormatter.ofPattern("dd")));
        v.put("HH", now.format(DateTimeFormatter.ofPattern("HH")));
        v.put("mm", now.format(DateTimeFormatter.ofPattern("mm")));
        v.put("ss", now.format(DateTimeFormatter.ofPattern("ss")));
        if (file != null) {
            String filename = baseName(file.filename());
            int dot = filename.lastIndexOf('.');
            v.put("filename", filename);
            v.put("name", dot > 0 ? filename.substring(0, dot) : filename);
            v.put("ext", dot > 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "");
            v.put("field", file.field());
        }
        return v;
    }

    /** The last path element of a client file name ("C:\\x\\a.pdf" and "../../a.pdf" are both "a.pdf"). */
    static String baseName(String filename) {
        String s = filename == null ? "" : filename;
        int cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        return s.substring(cut + 1);
    }

    /** One safe key segment: never empty, never "." or "..", no '/'. */
    static String segment(String value) {
        String s = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("\\.{2,}", "_");
        if (s.isEmpty() || s.equals(".")) {
            return "_";
        }
        return s.length() > 200 ? s.substring(s.length() - 200) : s;
    }
}
