package com.mhamzah.gateway.codec;

import com.mhamzah.gateway.extension.BodyCodec;
import com.mhamzah.gateway.extension.ExecutionContext;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * A file download (bean {@code fileCodec}): the request is JSON, a 2xx response that is not JSON is kept as a file
 * (the bytes as received, with its Content-Type and Content-Disposition file name). The step's result
 * {@code $.steps.<name>.body} is the file's description ({@code filename, contentType, size, sha256}); a FLOW_RESPONSE
 * rule {@code target_type = FILE, source_path = $.steps.<name>} sends the file itself to the client. JSON responses
 * (an error, or a 2xx that reports one) are decoded as JSON, as with {@link JsonCodec}.
 */
public final class FileCodec implements BodyCodec {

    public static final String BEAN_NAME = "fileCodec";
    public static final FileCodec INSTANCE = new FileCodec();

    private FileCodec() {}

    @Override
    public String contentType() {
        return "application/json";
    }

    @Override
    public String accept() {
        return "*/*";
    }

    @Override
    public String encode(JsonNode body, Map<String, String> headers, ExecutionContext ctx) {
        return JsonCodec.INSTANCE.encode(body, headers, ctx);
    }

    @Override
    public JsonNode decode(String body, Map<String, String> headers) {
        return JsonCodec.INSTANCE.decode(body, headers);
    }

    /** Whether a 2xx response with this Content-Type is a file (anything but JSON). */
    public static boolean isFile(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        return !isJson(contentType);
    }

    /**
     * {@code application/json} or {@code application/<subtype>+json}, parameters allowed: the types of
     * {@code application/([a-z0-9.+-]*\+)?json.*}, checked in linear time (the regex backtracks on a long subtype).
     */
    public static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        String prefix = "application/";
        if (!type.startsWith(prefix)) {
            return false;
        }
        for (int i = prefix.length(); ; i++) {
            if (type.startsWith("json", i) && (i == prefix.length() || type.charAt(i - 1) == '+')) {
                return true;
            }
            if (i >= type.length() || !subtypeChar(type.charAt(i))) {
                return false;
            }
        }
    }

    private static boolean subtypeChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '+' || c == '-';
    }
}
