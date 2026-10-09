package com.mhamzah.gateway.extension;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A file the client uploaded: a {@code multipart/form-data} part with a file name (by its form field), or a raw
 * request body of a non-JSON type such as {@code application/pdf} or {@code image/png} (field {@code body}). Flows
 * see its description at {@code $.request.files.<field>} ({@link #describe()}); the bytes stay here, for file
 * storage steps and custom handlers ({@link ExecutionContext#file(String)}).
 *
 * @param filename as the client sent it (may contain anything; never use it as a path unchecked)
 * @param contentType the part's or request's content type; {@code application/octet-stream} when absent
 */
public record InboundFile(String field, String filename, String contentType, byte[] bytes) {

    public InboundFile {
        filename = filename == null || filename.isBlank() ? "upload" : filename;
        contentType = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        bytes = bytes == null ? new byte[0] : bytes;
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
