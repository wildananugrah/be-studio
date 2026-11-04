package com.mhamzah.gateway.invoke;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Raw downstream HTTP response. Header names are lower-case. {@code body} is the content as text (decoded with the
 * Content-Type charset, UTF-8 by default); {@code bytes} is the content as received, for file downloads.
 */
public record DownstreamResponse(int status, Map<String, String> headers, String body, byte[] bytes) {

    private static final Pattern CHARSET = Pattern.compile(";\\s*charset=\"?([^\";\\s]+)", Pattern.CASE_INSENSITIVE);

    public DownstreamResponse {
        headers = Map.copyOf(headers);
        body = body == null ? "" : body;
        bytes = bytes == null ? body.getBytes(StandardCharsets.UTF_8) : bytes;
    }

    /** A text response (its bytes are the UTF-8 encoding of {@code body}). */
    public DownstreamResponse(int status, Map<String, String> headers, String body) {
        this(status, headers, body, null);
    }

    /** A response as received: the text is decoded with the Content-Type charset. */
    public static DownstreamResponse of(int status, Map<String, String> headers, byte[] bytes) {
        byte[] content = bytes == null ? new byte[0] : bytes;
        return new DownstreamResponse(status, headers, new String(content, charset(headers.get("content-type"))), content);
    }

    public boolean is2xx() {
        return status >= 200 && status < 300;
    }

    static Charset charset(String contentType) {
        if (contentType != null) {
            Matcher m = CHARSET.matcher(contentType);
            if (m.find()) {
                try {
                    return Charset.forName(m.group(1).toUpperCase(Locale.ROOT));
                } catch (RuntimeException ignored) {
                    // unknown charset: UTF-8
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}
