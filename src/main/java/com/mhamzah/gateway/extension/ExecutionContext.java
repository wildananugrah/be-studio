package com.mhamzah.gateway.extension;

import com.mhamzah.gateway.mapping.JsonPath;
import com.mhamzah.gateway.mapping.LookupTable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Per-request state that mappings, conditions and handlers read from (spec Section 6.1):
 * <pre>
 * $.request.headers / .path / .query / .body / .files.&lt;field&gt; (uploads: filename, contentType, size, sha256;
 * {@code .base64} and {@code .text} give the content, produced only when read)
 * $.steps.&lt;name&gt;.outcome / .status / .headers / .body
 * $.correlationId
 * </pre>
 * Steps in a parallel group write their results concurrently, so access is guarded by a read/write lock.
 */
public final class ExecutionContext {

    private final String flowCode;
    private final String correlationId;
    private final Map<String, LookupTable> lookups;
    private final ObjectNode root;
    private final ObjectNode steps;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, InboundFile> files;

    public ExecutionContext(String flowCode, String correlationId, Map<String, LookupTable> lookups, ObjectNode request) {
        this(flowCode, correlationId, lookups, request, Map.of());
    }

    /** @param files the uploaded files by form field (their descriptions are expected at {@code request.files}) */
    public ExecutionContext(String flowCode, String correlationId, Map<String, LookupTable> lookups, ObjectNode request,
            Map<String, InboundFile> files) {
        this.files = files == null ? Map.of() : Map.copyOf(files);
        this.flowCode = flowCode;
        this.correlationId = correlationId;
        this.lookups = lookups == null ? Map.of() : lookups;
        JsonNodeFactory f = JsonNodeFactory.instance;
        this.root = f.objectNode();
        this.steps = f.objectNode();
        root.set("request", request == null ? f.objectNode() : request);
        root.set("steps", steps);
        root.put("correlationId", correlationId);
    }

    /** The uploaded file of a form field ({@code body} for a raw upload); null when there is none. */
    public InboundFile file(String field) {
        return field == null ? null : files.get(field);
    }

    public Map<String, InboundFile> files() {
        return files;
    }

    /** Flow code, or null when no flow matched. */
    public String flowCode() {
        return flowCode;
    }

    public String correlationId() {
        return correlationId;
    }

    /** Reads a context path such as {@code $.request.body.amount}; null when missing. */
    public JsonNode read(String path) {
        return read(JsonPath.compile(path));
    }

    public JsonNode read(JsonPath path) {
        JsonNode content = fileContent(path);
        if (content != null) {
            return content;
        }
        lock.readLock().lock();
        try {
            return path.read(root);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * {@code $.request.files.<field>.base64} / {@code .text}: an uploaded file's content (Base64, or UTF-8 text),
     * e.g. to send it on inside a JSON body. Built on demand, never kept in the context (nor in the audit trail of
     * the request).
     */
    private JsonNode fileContent(JsonPath path) {
        List<String> names = path.fieldNames();
        if (names.size() != 4 || !"request".equals(names.get(0)) || !"files".equals(names.get(1))) {
            return null;
        }
        String kind = names.get(3);
        if (!"base64".equals(kind) && !"text".equals(kind)) {
            return null;
        }
        InboundFile f = files.get(names.get(2));
        if (f == null) {
            return null;
        }
        byte[] bytes = f.bytes();
        return com.mhamzah.gateway.mapping.JsonValues.MAPPER.getNodeFactory().stringNode("base64".equals(kind)
                ? java.util.Base64.getEncoder().encodeToString(bytes)
                : new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    public List<JsonPath.Match> readAll(JsonPath path) {
        lock.readLock().lock();
        try {
            return path.readAll(root);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Translates {@code value} through the lookup table {@code lookupCode} (matching entry or {@code *} fallback). */
    public Optional<JsonNode> lookup(String lookupCode, JsonNode value) {
        LookupTable table = lookups.get(lookupCode);
        return table == null ? Optional.empty() : Optional.ofNullable(table.find(value));
    }

    /** Deep copy of the whole context tree, e.g. for logging or custom handlers that need to iterate. */
    public ObjectNode snapshot() {
        lock.readLock().lock();
        try {
            return root.deepCopy();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** The mutable inbound request node. Engine use only, before steps run. */
    public ObjectNode request() {
        return (ObjectNode) root.get("request");
    }

    /** Stores the result of step {@code name}. Engine use only. */
    public void putStepResult(String name, ObjectNode result) {
        lock.writeLock().lock();
        try {
            steps.set(name, result);
        } finally {
            lock.writeLock().unlock();
        }
    }
}
