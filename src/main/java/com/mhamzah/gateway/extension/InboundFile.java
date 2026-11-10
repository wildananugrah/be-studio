package com.mhamzah.gateway.extension;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A file the client uploaded: a {@code multipart/form-data} part with a file name (by its form field), or a raw
 * request body of a non-JSON type such as {@code application/pdf} or {@code image/png} (field {@code body}). Flows
 * see its description at {@code $.request.files.<field>} ({@link #describe()}); the bytes stay here, for file
 * storage steps and custom handlers ({@link ExecutionContext#file(String)}). Also a file a step downloaded
 * ({@code body_codec = fileCodec}; field = the step name, {@link ExecutionContext#stepFile(String)}).
 *
 * @param filename as the client sent it (may contain anything; never use it as a path unchecked)
 * @param contentType the part's or request's content type; {@code application/octet-stream} when absent
 */
public record InboundFile(String field, String filename, String contentType, byte[] bytes) {

    private static final Pattern DISPOSITION_FILENAME = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?",
            Pattern.CASE_INSENSITIVE);

    public InboundFile {
        filename = filename == null || filename.isBlank() ? "upload" : filename;
        contentType = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        bytes = bytes == null ? new byte[0] : bytes;
    }

    /** The file name of a {@code Content-Disposition} header ({@code filename="a.pdf"} or {@code filename*=UTF-8''a.pdf}); null when none. */
    public static String filenameOf(String contentDisposition) {
        if (contentDisposition == null) {
            return null;
        }
        Matcher m = DISPOSITION_FILENAME.matcher(contentDisposition);
        return m.find() ? URLDecoder.decode(m.group(1), StandardCharsets.UTF_8) : null;
    }

    public long size() {
        return bytes.length;
    }

    /** Hex SHA-256 of the content. */
    public String sha256() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code {field, filename, contentType, size, sha256}}: what flows read at {@code $.request.files.<field>}. */
    public ObjectNode describe() {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("field", field);
        o.put("filename", filename);
        o.put("contentType", contentType);
        o.put("size", size());
        o.put("sha256", sha256());
        return o;
    }
}
