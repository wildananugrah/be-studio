package com.mhamzah.gateway.docs;

import com.mhamzah.gateway.condition.Condition;
import com.mhamzah.gateway.config.FlowDefinition;
import com.mhamzah.gateway.config.FlowRegistry;
import com.mhamzah.gateway.config.StepDefinition;
import com.mhamzah.gateway.mapping.CompiledRule;
import com.mhamzah.gateway.mapping.JsonPath;
import com.mhamzah.gateway.mapping.TargetType;
import com.mhamzah.gateway.schema.CompiledSchema;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds an OpenAPI 3.1 description of the gateway from the live {@link FlowRegistry}: one operation per enabled flow.
 * <ul>
 *   <li>Path parameters come from {@code gw_flow.path_pattern}.</li>
 *   <li>Query and header parameters are those the flow's mapping rules and expressions read
 *       ({@code $.request.query.x}, {@code $.request.headers.x}); required when a {@code required} rule reads them.</li>
 *   <li>Request / response bodies use {@code request_schema_code} / {@code response_schema_code} when set
 *       (OpenAPI 3.1 schemas are JSON Schema 2020-12), otherwise a skeleton built from the rules that read
 *       {@code $.request.body...} / write the flow response.</li>
 * </ul>
 */
public final class OpenApiGenerator {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final String JSON = "application/json";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final Set<HttpMethod> NO_BODY = Set.of(HttpMethod.GET);

    private OpenApiGenerator() {}

    public static ObjectNode generate(FlowRegistry registry, String apiBasePath, String title) {
        ObjectNode doc = F.objectNode();
        doc.put("openapi", "3.1.0");
        ObjectNode info = doc.putObject("info");
        info.put("title", title);
        info.put("version", registry.loadedAt().toString());
        info.put("description", "Generated from the flow configuration loaded at " + registry.loadedAt()
                + ". Every enabled flow is listed; reload the configuration (POST /admin/config/reload) to refresh."
                + " Error responses use the default format unless the flow has a custom error_handler.");
        ArrayNode tags = doc.putArray("tags");
        tags.addObject().put("name", "Flows").put("description", "Endpoints configured in gw_flow");
        tags.addObject().put("name", "Admin").put("description", "Gateway administration");

        ObjectNode paths = doc.putObject("paths");
        ObjectNode components = doc.putObject("components");
        ObjectNode schemas = components.putObject("schemas");
        schemas.set("ErrorResponse", errorResponseSchema());

        String base = apiBasePath.endsWith("/") ? apiBasePath.substring(0, apiBasePath.length() - 1) : apiBasePath;
        registry.flows().stream()
                .sorted(Comparator.comparing((FlowDefinition f) -> f.pathPattern().getPatternString())
                        .thenComparing(f -> f.method().name()))
                .forEach(flow -> {
                    Template template = template(flow.pathPattern().getPatternString());
                    ObjectNode item = paths.has(base + template.path)
                            ? (ObjectNode) paths.get(base + template.path) : paths.putObject(base + template.path);
                    item.set(flow.method().name().toLowerCase(Locale.ROOT), operation(flow, template, schemas));
                });

        paths.set("/admin/config/reload", adminReload());
        ObjectNode adminToken = components.putObject("securitySchemes").putObject("adminToken");
        adminToken.put("type", "apiKey").put("in", "header").put("name", "X-Admin-Token");
        return doc;
    }

    private static ObjectNode operation(FlowDefinition flow, Template template, ObjectNode schemas) {
        ObjectNode op = F.objectNode();
        op.putArray("tags").add("Flows");
        op.put("operationId", flow.code());
        op.put("summary", flow.code());
        op.put("description", "Flow `" + flow.code() + "`, timeout " + flow.timeout().toMillis() + " ms.");

        Inputs inputs = inputs(flow);
        ArrayNode params = op.putArray("parameters");
        template.variables.forEach((name, regex) -> {
            ObjectNode p = params.addObject();
            p.put("name", name).put("in", "path").put("required", true);
            ObjectNode schema = p.putObject("schema").put("type", "string");
            if (regex != null) {
                schema.put("pattern", "^" + regex + "$");
            }
        });
        inputs.query.forEach((name, required) -> parameter(params, name, "query", required));
        inputs.headers.forEach((name, required) -> parameter(params, name, "header", required));
        params.addObject().put("name", CORRELATION_HEADER).put("in", "header").put("required", false)
                .put("description", "Optional; generated when absent and echoed in the response")
                .putObject("schema").put("type", "string").put("pattern", "^[A-Za-z0-9._:\\-]{1,64}$");

        if (!NO_BODY.contains(flow.method()) && inputs.files.keySet().equals(java.util.Set.of("body"))) {
            // a raw upload: the whole body is the file
            parameter(params, "X-File-Name", "header", false);
            ObjectNode body = op.putObject("requestBody");
            body.put("required", true);
            body.putObject("content").putObject("application/octet-stream").putObject("schema")
                    .put("type", "string").put("format", "binary");
        } else if (!NO_BODY.contains(flow.method()) && !inputs.files.isEmpty()) {
            // multipart/form-data: the text fields the flow reads plus one binary property per file field
            ObjectNode body = op.putObject("requestBody");
            body.put("required", true);
            ObjectNode schema = inputs.body.deepCopy();
            schema.put("type", "object");
            ObjectNode props = schema.has("properties") ? (ObjectNode) schema.get("properties") : schema.putObject("properties");
            // form fields arrive as text
            props.properties().forEach(e -> {
                if (e.getValue() instanceof ObjectNode o && !o.has("type")) {
                    o.put("type", "string");
                }
            });
            inputs.files.forEach((field, required) -> {
                props.putObject(field).put("type", "string").put("format", "binary");
                if (required) {
                    (schema.has("required") ? (ArrayNode) schema.get("required") : schema.putArray("required")).add(field);
                }
            });
            body.putObject("content").putObject("multipart/form-data").set("schema", schema);
        } else if (!NO_BODY.contains(flow.method())) {
            ObjectNode body = op.putObject("requestBody");
            JsonNode schema = flow.requestSchema() != null ? ref(flow.requestSchema(), schemas) : inputs.body;
            body.put("required", flow.requestSchema() != null || inputs.body.has("required"));
            body.putObject("content").putObject(JSON).set("schema", schema);
        }

        ObjectNode responses = op.putObject("responses");
        ObjectNode ok = responses.putObject(String.valueOf(flow.successStatus()));
        ok.put("description", "Success");
        ObjectNode headers = ok.putObject("headers");
        headers.putObject(CORRELATION_HEADER).putObject("schema").put("type", "string");
        ObjectNode responseBody = objectSchema();
        for (CompiledRule r : flow.responseRules()) {
            if (r.targetType() == TargetType.HEADER) {
                headers.putObject(r.targetName()).putObject("schema").put("type", "string");
            } else if (r.targetType() == TargetType.BODY && r.targetPath() != null) {
                addPath(responseBody, r.targetPath().fieldNames(), false);
            }
        }
        JsonNode okSchema = flow.responseSchema() != null ? ref(flow.responseSchema(), schemas) : responseBody;
        ok.putObject("content").putObject(JSON).set("schema", okSchema);
        ObjectNode error = responses.putObject("default");
        error.put("description", "Error (default format; a flow with a custom error_handler may differ)");
        error.putObject("content").putObject(JSON).putObject("schema").put("$ref", "#/components/schemas/ErrorResponse");
        return op;
    }

    private static void parameter(ArrayNode params, String name, String in, boolean required) {
        params.addObject().put("name", name).put("in", in).put("required", required)
                .putObject("schema").put("type", "string");
    }

    /** What a flow reads from the request: query parameters, headers (name to required) and a body skeleton. */
    private record Inputs(Map<String, Boolean> query, Map<String, Boolean> headers, ObjectNode body,
            Map<String, Boolean> files) {}

    private static Inputs inputs(FlowDefinition flow) {
        Inputs in = new Inputs(new LinkedHashMap<>(), new LinkedHashMap<>(), objectSchema(), new LinkedHashMap<>());
        List<CompiledRule> rules = new ArrayList<>(flow.responseRules());
        List<Condition> conditions = new ArrayList<>();
        for (StepDefinition s : flow.allSteps()) {
            rules.addAll(s.requestRules());
            conditions.add(s.condition());
            conditions.add(s.success());
        }
        rules.stream().filter(r -> r.source() != null).forEach(r -> read(in, r.source(), r.required()));
        // a body template's ${request...} placeholders read the request too
        flow.allSteps().stream().filter(s -> s.bodyTemplate() != null)
                .forEach(s -> s.bodyTemplate().references().forEach(p -> read(in, p, false)));
        conditions.stream().filter(c -> c != null).forEach(c -> c.references().forEach(p -> read(in, p, false)));
        return in;
    }

    private static void read(Inputs in, JsonPath path, boolean required) {
        List<String> names = path.fieldNames();
        if (names.size() < 2 || !"request".equals(names.get(0))) {
            return;
        }
        String part = names.get(1);
        if ("body".equals(part)) {
            addPath(in.body, names.subList(2, names.size()), required);
        } else if ("files".equals(part) && names.size() > 2 && names.get(2) != null) {
            in.files.merge(names.get(2), required, Boolean::logicalOr);
        } else if (names.size() > 2 && names.get(2) != null && ("query".equals(part) || "headers".equals(part))) {
            String name = names.get(2);
            if ("headers".equals(part) && name.equalsIgnoreCase(CORRELATION_HEADER)) {
                return;
            }
            (part.equals("query") ? in.query : in.headers).merge(name, required, Boolean::logicalOr);
        }
    }

    /** Adds a field path to a schema skeleton; null entries are array positions. */
    private static void addPath(ObjectNode schema, List<String> names, boolean required) {
        ObjectNode node = schema;
        boolean stillRequired = required;
        for (String name : names) {
            if (name == null) {
                node.put("type", "array");
                node = node.has("items") ? (ObjectNode) node.get("items") : node.putObject("items");
                stillRequired = false;
                continue;
            }
            if (!node.has("type")) {
                node.put("type", "object");
            }
            ObjectNode props = node.has("properties") ? (ObjectNode) node.get("properties") : node.putObject("properties");
            ObjectNode child = props.has(name) ? (ObjectNode) props.get(name) : props.putObject(name);
            if (stillRequired) {
                ArrayNode req = node.has("required") ? (ArrayNode) node.get("required") : node.putArray("required");
                boolean present = false;
                for (JsonNode r : req) {
                    present |= r.asString().equals(name);
                }
                if (!present) {
                    req.add(name);
                }
            }
            node = child;
        }
    }

    private static ObjectNode objectSchema() {
        return F.objectNode().put("type", "object");
    }

    /** Adds the gw_json_schema to components (once) and returns a $ref to it. */
    private static ObjectNode ref(CompiledSchema schema, ObjectNode schemas) {
        String pointer = "#/components/schemas/" + schema.code().replace("~", "~0").replace("/", "~1");
        if (!schemas.has(schema.code())) {
            JsonNode json = schema.json();
            if (json instanceof ObjectNode o) {
                o.remove("$schema");
                o.remove("$id");
                rewriteRefs(o, pointer);
            }
            schemas.set(schema.code(), json);
        }
        return F.objectNode().put("$ref", pointer);
    }

    /** Local refs ({@code #/$defs/x}) point into the schema document, which now lives under {@code pointer}. */
    private static void rewriteRefs(JsonNode node, String pointer) {
        if (node instanceof ObjectNode o) {
            JsonNode ref = o.get("$ref");
            if (ref != null && ref.isString() && ref.asString().startsWith("#")) {
                o.put("$ref", pointer + ref.asString().substring(1));
            }
            o.properties().forEach(e -> rewriteRefs(e.getValue(), pointer));
        } else if (node instanceof ArrayNode a) {
            a.forEach(child -> rewriteRefs(child, pointer));
        }
    }

    private static ObjectNode errorResponseSchema() {
        ObjectNode s = objectSchema();
        ObjectNode p = s.putObject("properties");
        p.putObject("errorCode").put("type", "string");
        p.putObject("errorMessage").put("type", "string");
        p.putObject("correlationId").put("type", "string");
        p.putObject("step").put("type", "string").put("description", "The failing step, when a step failed");
        p.putObject("details").put("type", "array").put("description", "Validation messages")
                .putObject("items").put("type", "string");
        s.putArray("required").add("errorCode").add("errorMessage").add("correlationId");
        return s;
    }

    private static ObjectNode adminReload() {
        ObjectNode item = F.objectNode();
        ObjectNode op = item.putObject("post");
        op.putArray("tags").add("Admin");
        op.put("operationId", "reloadConfig");
        op.put("summary", "Reload the configuration from the database");
        op.putArray("security").addObject().putArray("adminToken");
        ObjectNode responses = op.putObject("responses");
        ObjectNode ok = responses.putObject("200");
        ok.put("description", "New configuration is live");
        ObjectNode okProps = ok.putObject("content").putObject(JSON).putObject("schema").put("type", "object")
                .putObject("properties");
        okProps.putObject("flows").put("type", "integer");
        okProps.putObject("loadedAt").put("type", "string").put("format", "date-time");
        errors(responses, "401", "Missing or wrong X-Admin-Token");
        errors(responses, "422", "Configuration is invalid; the previous configuration stays live");
        errors(responses, "503", "Database unavailable; the previous configuration stays live");
        return item;
    }

    private static void errors(ObjectNode responses, String status, String description) {
        ObjectNode r = responses.putObject(status);
        r.put("description", description);
        r.putObject("content").putObject(JSON).putObject("schema").put("type", "object")
                .putObject("properties").putObject("errors").put("type", "array").putObject("items").put("type", "string");
    }

    /** An OpenAPI path template and its variables (name to regex, null when unconstrained). */
    private record Template(String path, Map<String, String> variables) {}

    /** {@code /a/{id:[0-9]+}/{*rest}} becomes {@code /a/{id}/{rest}} with {@code id} constrained to {@code [0-9]+}. */
    static Template template(String pattern) {
        StringBuilder path = new StringBuilder();
        Map<String, String> vars = new LinkedHashMap<>();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c != '{') {
                path.append(c);
                i++;
                continue;
            }
            int depth = 0;
            int end = i;
            for (; end < pattern.length(); end++) {
                if (pattern.charAt(end) == '{') {
                    depth++;
                } else if (pattern.charAt(end) == '}' && --depth == 0) {
                    break;
                }
            }
            String inner = pattern.substring(i + 1, end);
            if (inner.startsWith("*")) {
                inner = inner.substring(1);
            }
            int colon = inner.indexOf(':');
            String name = colon < 0 ? inner : inner.substring(0, colon);
            vars.put(name, colon < 0 ? null : inner.substring(colon + 1));
            path.append('{').append(name).append('}');
            i = end + 1;
        }
        return new Template(path.toString(), vars);
    }
}
