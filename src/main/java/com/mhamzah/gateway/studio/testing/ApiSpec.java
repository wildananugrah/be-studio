package com.mhamzah.gateway.studio.testing;

import com.mhamzah.gateway.config.FlowDefinition;
import com.mhamzah.gateway.config.StepDefinition;
import com.mhamzah.gateway.extension.ErrorType;
import com.mhamzah.gateway.mapping.CompiledRule;
import com.mhamzah.gateway.mapping.JsonValues;
import com.mhamzah.gateway.studio.testing.TestReport.Block;
import com.mhamzah.gateway.studio.testing.TestReport.Code;
import com.mhamzah.gateway.studio.testing.TestReport.Fields;
import com.mhamzah.gateway.studio.testing.TestReport.Heading;
import com.mhamzah.gateway.studio.testing.TestReport.PageBreak;
import com.mhamzah.gateway.studio.testing.TestReport.Paragraph;
import com.mhamzah.gateway.studio.testing.TestReport.Table;
import com.mhamzah.gateway.studio.testing.TestReport.Title;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * An API specification document (Markdown, Word or PDF through {@link ReportRenderers}) built from the API description
 * (the same OpenAPI document as {@code /docs/openapi.json}): per endpoint its URL, parameters, request and response
 * fields with type, mandatory and constraints, an example request and response, and the errors it can return.
 */
public final class ApiSpec {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.ROOT);
    private static final int MAX_DEPTH = 8;

    private ApiSpec() {}

    /** An endpoint: its flow, its operation in the API description and the path it is under. */
    public record Endpoint(FlowDefinition flow, String name, String path, JsonNode operation) {}

    /** The endpoints of {@code flows} (in that order) found in {@code openApi}; flows not in it are left out. */
    public static List<Endpoint> endpoints(JsonNode openApi, List<FlowDefinition> flows, Map<String, String> names) {
        List<Endpoint> out = new ArrayList<>();
        for (FlowDefinition flow : flows) {
            String method = flow.method().name().toLowerCase(Locale.ROOT);
            for (Map.Entry<String, JsonNode> path : openApi.path("paths").properties()) {
                JsonNode op = path.getValue().get(method);
                if (op != null && flow.code().equals(op.path("operationId").asString(null))) {
                    String name = names.get(flow.code());
                    out.add(new Endpoint(flow, name == null || name.isBlank() ? flow.code() : name, path.getKey(), op));
                }
            }
        }
        return out;
    }

    /**
     * @param baseUrl scheme, host and port the clients call (the API paths already start with the base path)
     */
    public static List<Block> build(JsonNode openApi, List<Endpoint> endpoints, String title, String baseUrl,
            ZonedDateTime at) {
        List<Block> out = new ArrayList<>();
        String subtitle = endpoints.size() == 1
                ? endpoints.getFirst().name() + " · " + endpoints.getFirst().flow().method() + " " + endpoints.getFirst().path()
                : endpoints.size() + " endpoints";
        out.add(new Title("API Specification - " + title, subtitle));
        out.add(new Fields(List.of(
                row("Generated", at.format(WHEN) + ", from the live gateway configuration"),
                row("Base URL", baseUrl),
                row("Content type", "application/json; charset=UTF-8 unless an endpoint says otherwise"),
                row("Correlation ID", "Optional X-Correlation-Id request header (1-64 of A-Z a-z 0-9 . _ : -); generated"
                        + " when absent and returned in the X-Correlation-Id response header"),
                row("Errors", "JSON error body, see 'Error response format' at the end"))));
        if (endpoints.size() > 1) {
            out.add(new Heading(1, "Endpoints"));
            List<List<String>> rows = new ArrayList<>();
            int n = 1;
            for (Endpoint e : endpoints) {
                rows.add(List.of(String.valueOf(n++), e.flow().method().name(), e.path(), e.name()));
            }
            out.add(new Table(List.of("#", "Method", "Path", "Name"), rows));
        }
        int n = 1;
        boolean several = endpoints.size() > 1; // one endpoint: one flowing document, no page breaks
        for (Endpoint e : endpoints) {
            if (several) {
                out.add(new PageBreak());
            }
            endpoint(out, openApi, e, several ? (n++) + ". " : "", baseUrl);
        }
        if (several) {
            out.add(new PageBreak());
        }
        errorFormat(out);
        return out;
    }

    private static void endpoint(List<Block> out, JsonNode doc, Endpoint e, String number, String baseUrl) {
        FlowDefinition flow = e.flow();
        JsonNode op = e.operation();
        out.add(new Heading(1, number + e.name()));
        List<String[]> facts = new ArrayList<>();
        facts.add(row("Method", flow.method().name()));
        facts.add(row("URL", baseUrl + e.path()));
        facts.add(row("Flow", flow.code()));
        facts.add(row("Success status", String.valueOf(flow.successStatus())));
        facts.add(row("Timeout", flow.timeout().toMillis() + " ms"));
        if (flow.requestSchema() != null) {
            facts.add(row("Request schema", flow.requestSchema().code() + " (validated)"));
        }
        if (flow.responseSchema() != null) {
            facts.add(row("Response schema", flow.responseSchema().code()));
        }
        out.add(new Fields(facts));

        // ---- request
        out.add(new Heading(2, "Request"));
        List<List<String>> params = new ArrayList<>();
        Map<String, String> exampleQuery = new LinkedHashMap<>();
        Map<String, String> exampleHeaders = new LinkedHashMap<>();
        String examplePath = e.path();
        for (JsonNode p : op.path("parameters")) {
            String name = p.path("name").asString();
            String in = p.path("in").asString();
            JsonNode schema = TestCaseGenerator.resolve(doc, p.path("schema"));
            params.add(List.of(name, in, type(schema), p.path("required").asBoolean(false) ? "M" : "O",
                    notes(schema, p.path("description").asString(""))));
            if ("X-Correlation-Id".equalsIgnoreCase(name)) {
                continue;
            }
            String value = TestCaseGenerator.guessText(name, schema);
            switch (in) {
                case "path" -> examplePath = examplePath.replace("{" + name + "}", value);
                case "query" -> exampleQuery.put(name, value);
                case "header" -> exampleHeaders.put(name, value);
                default -> { }
            }
        }
        if (!params.isEmpty()) {
            out.add(new Paragraph("Parameters (M = mandatory, O = optional):"));
            out.add(new Table(List.of("Name", "In", "Type", "M/O", "Notes"), params));
        }
        JsonNode requestBody = op.path("requestBody");
        String contentType = null;
        JsonNode requestExample = null;
        if (!requestBody.isMissingNode()) {
            Map.Entry<String, JsonNode> content = first(requestBody.path("content"));
            if (content != null) {
                contentType = content.getKey();
                JsonNode schema = TestCaseGenerator.resolve(doc, content.getValue().path("schema"));
                List<List<String>> fields = new ArrayList<>();
                fields(doc, schema, "", true, fields, 0);
                out.add(new Paragraph("Body (" + contentType + (requestBody.path("required").asBoolean(false)
                        ? ", required" : "") + "):"));
                if (fields.isEmpty()) {
                    out.add(new Paragraph(contentType.contains("octet-stream")
                            ? "The whole body is the file; send its name in the X-File-Name header."
                            : "No documented fields."));
                } else {
                    out.add(new Table(List.of("Field", "Type", "M/O", "Notes"), fields));
                    if (flow.requestSchema() == null && contentType.equals("application/json")) {
                        out.add(new Paragraph("These fields are the ones the flow reads; without a request schema their"
                                + " types and lengths are not declared (attach one to the flow to document and validate them)."));
                    }
                }
                if (contentType.equals("application/json")) {
                    requestExample = TestCaseGenerator.example(doc, schema, "body");
                }
            }
        } else {
            out.add(new Paragraph("No request body."));
        }
        StringBuilder req = new StringBuilder(flow.method().name()).append(' ').append(examplePath);
        if (!exampleQuery.isEmpty()) {
            req.append('?');
            exampleQuery.forEach((k, v) -> req.append(k).append('=').append(v).append('&'));
            req.setLength(req.length() - 1);
        }
        req.append(" HTTP/1.1\n");
        if (contentType != null) {
            req.append("Content-Type: ").append(contentType.equals("multipart/form-data")
                    ? "multipart/form-data; boundary=..." : contentType).append('\n');
        }
        exampleHeaders.forEach((k, v) -> req.append(k).append(": ").append(v).append('\n'));
        req.append("X-Correlation-Id: REQ-0001\n");
        if (requestExample != null) {
            req.append('\n').append(JsonValues.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(requestExample));
        }
        out.add(new Paragraph("Example request:"));
        out.add(new Code(req.toString().stripTrailing()));

        // ---- response
        out.add(new Heading(2, "Response"));
        JsonNode ok = op.path("responses").path(String.valueOf(flow.successStatus()));
        List<List<String>> headers = new ArrayList<>();
        ok.path("headers").properties().forEach(h -> headers.add(List.of(h.getKey(),
                type(TestCaseGenerator.resolve(doc, h.getValue().path("schema"))))));
        out.add(new Paragraph("HTTP " + flow.successStatus() + " with these headers:"));
        out.add(new Table(List.of("Header", "Type"), headers));
        JsonNode responseSchema = TestCaseGenerator.resolve(doc, ok.path("content").path("application/json").path("schema"));
        List<List<String>> fields = new ArrayList<>();
        fields(doc, responseSchema, "", false, fields, 0);
        if (fields.isEmpty()) {
            out.add(new Paragraph("No documented body fields."));
        } else {
            out.add(new Paragraph("Body (application/json):"));
            out.add(new Table(List.of("Field", "Type", "Notes"), fields));
            if (flow.responseSchema() == null) {
                out.add(new Paragraph("These fields are the ones the flow writes; without a response schema their types"
                        + " are not declared, so the example values below are placeholders."));
            }
            out.add(new Paragraph("Example response:"));
            out.add(new Code("HTTP/1.1 " + flow.successStatus() + "\nContent-Type: application/json\nX-Correlation-Id: REQ-0001\n\n"
                    + JsonValues.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                            TestCaseGenerator.example(doc, responseSchema, "body"))));
        }

        // ---- errors
        out.add(new Heading(2, "Errors"));
        List<List<String>> errors = new ArrayList<>();
        for (Map.Entry<String, String[]> err : errors(flow).entrySet()) {
            errors.add(List.of(err.getValue()[0], err.getKey(), err.getValue()[1]));
        }
        out.add(new Table(List.of("HTTP", "errorCode", "When"), errors));
        if (flow.errorHandler() != null && flow.errorHandlerName() != null
                && !"defaultErrorHandler".equals(flow.errorHandlerName())) {
            out.add(new Paragraph("This endpoint has a custom error handler (" + flow.errorHandlerName()
                    + "): its error responses may differ from the default format."));
        }
    }

    /** One row per field (dotted path, {@code []} for array items), parents before their children. */
    private static void fields(JsonNode doc, JsonNode schema, String prefix, boolean withRequired, List<List<String>> out,
            int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        Set<String> required = new LinkedHashSet<>();
        schema.path("required").forEach(r -> required.add(r.asString()));
        for (Map.Entry<String, JsonNode> prop : schema.path("properties").properties()) {
            JsonNode s = TestCaseGenerator.resolve(doc, prop.getValue());
            String name = prefix + prop.getKey();
            String notes = notes(s, s.path("description").asString(""));
            out.add(withRequired ? List.of(name, type(s), required.contains(prop.getKey()) ? "M" : "O", notes)
                    : List.of(name, type(s), notes));
            String t = TestCaseGenerator.typeOf(s);
            if ("object".equals(t) || s.has("properties")) {
                fields(doc, s, name + ".", withRequired, out, depth + 1);
            } else if ("array".equals(t)) {
                JsonNode items = TestCaseGenerator.resolve(doc, s.path("items"));
                if (items.has("properties")) {
                    fields(doc, items, name + "[].", withRequired, out, depth + 1);
                }
            }
        }
    }

    private static String type(JsonNode s) {
        String t = TestCaseGenerator.typeOf(s);
        if (t == null) {
            return "any";
        }
        if ("array".equals(t)) {
            String items = TestCaseGenerator.typeOf(s.path("items"));
            return "array of " + (items == null ? "any" : items);
        }
        String format = s.path("format").asString("");
        return format.isEmpty() ? t : t + " (" + format + ")";
    }

    /** The schema's description and constraints, in words. */
    private static String notes(JsonNode s, String description) {
        List<String> parts = new ArrayList<>();
        if (!description.isBlank()) {
            parts.add(description.strip());
        }
        if (s.path("enum").isArray()) {
            List<String> values = new ArrayList<>();
            s.get("enum").forEach(v -> values.add(v.isString() ? v.asString() : v.toString()));
            parts.add("one of " + String.join(", ", values));
        }
        if (s.has("const")) {
            parts.add("always " + (s.get("const").isString() ? s.get("const").asString() : s.get("const").toString()));
        }
        if (s.has("minLength") || s.has("maxLength")) {
            parts.add(s.has("minLength") && s.has("maxLength")
                    ? (s.get("minLength").asInt() == s.get("maxLength").asInt() ? "length " + s.get("maxLength").asInt()
                            : "length " + s.get("minLength").asInt() + "-" + s.get("maxLength").asInt())
                    : s.has("minLength") ? "at least " + characters(s.get("minLength").asInt())
                    : "at most " + characters(s.get("maxLength").asInt()));
        }
        if (s.has("minimum")) {
            parts.add("minimum " + s.get("minimum").asString());
        }
        if (s.has("exclusiveMinimum") && s.get("exclusiveMinimum").isNumber()) {
            parts.add("greater than " + s.get("exclusiveMinimum").asString());
        }
        if (s.has("maximum")) {
            parts.add("maximum " + s.get("maximum").asString());
        }
        if (s.has("exclusiveMaximum") && s.get("exclusiveMaximum").isNumber()) {
            parts.add("less than " + s.get("exclusiveMaximum").asString());
        }
        if (s.has("minItems") || s.has("maxItems")) {
            parts.add((s.has("minItems") ? s.get("minItems").asInt() : 0) + "-"
                    + (s.has("maxItems") ? s.get("maxItems").asString() : "n") + " items");
        }
        if (s.has("pattern")) {
            parts.add("pattern " + s.get("pattern").asString());
        }
        if (s.has("default")) {
            parts.add("default " + (s.get("default").isString() ? s.get("default").asString() : s.get("default").toString()));
        }
        return String.join("; ", parts);
    }

    private static String characters(int n) {
        return n == 1 ? "1 character" : n + " characters";
    }

    /** The errors this flow can return (default error handler), by error code. */
    private static Map<String, String[]> errors(FlowDefinition flow) {
        Map<String, String[]> out = new LinkedHashMap<>();
        boolean hasBody = flow.method() != org.springframework.http.HttpMethod.GET;
        List<StepDefinition> steps = flow.allSteps();
        if (hasBody) {
            put(out, ErrorType.INVALID_JSON, "The body is not valid JSON");
        }
        if (flow.requestSchema() != null) {
            put(out, ErrorType.REQUEST_SCHEMA_INVALID, "The request does not match schema " + flow.requestSchema().code()
                    + "; 'details' lists each problem");
        }
        boolean requiredInputs = steps.stream().flatMap(s -> s.requestRules().stream()).anyMatch(ApiSpec::readsRequest)
                || flow.responseRules().stream().anyMatch(ApiSpec::readsRequest);
        if (requiredInputs) {
            out.put("GW-400-MAPPING", new String[] {"400", "A mandatory field or parameter is missing or has a value that"
                    + " cannot be converted"});
        }
        if (steps.stream().anyMatch(s -> s.success() != null)) {
            put(out, ErrorType.DOWNSTREAM_BUSINESS_ERROR, "The downstream system refused the request (its result code"
                    + " is not a success)");
        }
        if (steps.stream().anyMatch(s -> !s.isSql() && !s.isStorage())) {
            put(out, ErrorType.DOWNSTREAM_HTTP_ERROR, "The downstream system answered with an HTTP error");
            put(out, ErrorType.DOWNSTREAM_CONNECTION, "The downstream system cannot be reached");
            put(out, ErrorType.DOWNSTREAM_INVALID_RESPONSE, "The downstream answer cannot be read");
            put(out, ErrorType.DOWNSTREAM_TIMEOUT, "The downstream system did not answer in time");
        }
        if (steps.stream().anyMatch(StepDefinition::isSql)) {
            put(out, ErrorType.DATABASE_ERROR, "The database query failed");
        }
        if (steps.stream().anyMatch(StepDefinition::isStorage)) {
            put(out, ErrorType.FILE_TOO_LARGE, "The file is over the size limit");
            put(out, ErrorType.FILE_REJECTED, "The file type is not accepted, or no file was sent");
            put(out, ErrorType.STORAGE_ERROR, "The file could not be stored");
        }
        put(out, ErrorType.FLOW_TIMEOUT, "The whole request took longer than " + flow.timeout().toMillis() + " ms");
        put(out, ErrorType.INTERNAL, "Unexpected gateway error");
        return out;
    }

    private static boolean readsRequest(CompiledRule r) {
        return r.required() && r.sourcedFromRequest();
    }

    private static void put(Map<String, String[]> out, ErrorType type, String when) {
        out.put(type.defaultCode(), new String[] {String.valueOf(type.defaultStatus()), when});
    }

    private static void errorFormat(List<Block> out) {
        out.add(new Heading(1, "Error response format"));
        out.add(new Paragraph("Every error (unless an endpoint has a custom error handler) is a JSON body with the HTTP"
                + " status of the error:"));
        out.add(new Table(List.of("Field", "Type", "Notes"), List.of(
                List.of("errorCode", "string", "Stable code such as GW-400-SCHEMA; see each endpoint's Errors table"),
                List.of("errorMessage", "string", "Human readable message"),
                List.of("correlationId", "string", "Same as the X-Correlation-Id response header; quote it to support"),
                List.of("step", "string", "Present when a downstream step failed: that step's name"),
                List.of("details", "array of string", "Present for validation errors: one entry per problem"))));
        out.add(new Paragraph("Example:"));
        out.add(new Code("HTTP/1.1 400\nContent-Type: application/json\n\n{\n  \"errorCode\": \"GW-400-SCHEMA\",\n"
                + "  \"errorMessage\": \"Request validation failed\",\n  \"correlationId\": \"REQ-0001\",\n"
                + "  \"details\": [\"...one entry per problem...\"]\n}"));
    }

    private static Map.Entry<String, JsonNode> first(JsonNode content) {
        for (Map.Entry<String, JsonNode> e : content.properties()) {
            return e;
        }
        return null;
    }

    private static String[] row(String key, String value) {
        return new String[] {key, value};
    }
}
