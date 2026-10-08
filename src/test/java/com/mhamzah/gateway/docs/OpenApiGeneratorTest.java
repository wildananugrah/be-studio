package com.mhamzah.gateway.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.config.ConfigCompiler;
import com.mhamzah.gateway.config.ConfigRows.RuleRow;
import com.mhamzah.gateway.config.FlowRegistry;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.config.Rows;
import com.mhamzah.gateway.extension.DefaultErrorHandler;
import java.time.Duration;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class OpenApiGeneratorTest {

    private final Rows rows = new Rows();

    private JsonNode generate() {
        ConfigCompiler compiler = new ConfigCompiler(
                new ConfigCompiler.HandlerLookup() {
                    @Override
                    public <T> T find(String name, Class<T> type) {
                        return name.equals("defaultErrorHandler") ? type.cast(new DefaultErrorHandler()) : null;
                    }
                },
                Map.of("CORE", new GatewayProperties.TargetSystem("http://core", 1000, 2000, Map.of(), null)),
                UnaryOperator.identity(), Duration.ofSeconds(5), Duration.ofSeconds(2));
        FlowRegistry registry = compiler.compile(rows.build());
        return OpenApiGenerator.generate(registry, "/api", "Test API");
    }

    private RuleRow required(RuleRow r) {
        return new RuleRow(r.id(), r.flowId(), r.stepId(), r.phase(), r.seq(), r.targetType(), r.targetPath(),
                r.sourcePath(), r.constantValue(), r.defaultValue(), r.converter(), r.lookupCode(), r.fieldHandler(), true);
    }

    @Test
    void documentHeader() {
        JsonNode doc = generate();

        assertThat(doc.get("openapi").asString()).isEqualTo("3.1.0");
        assertThat(doc.get("info").get("title").asString()).isEqualTo("Test API");
        assertThat(doc.get("components").get("schemas").has("ErrorResponse")).isTrue();
    }

    @Test
    void eachFlowIsAnOperationUnderTheBasePath() {
        var flow = rows.flow("ACCOUNT_INQUIRY", "GET", "/v1/accounts/{accountNo}");
        rows.step(flow, "s", 1);
        rows.flow("TRANSFER", "POST", "/v1/accounts/{accountNo}", f -> f);

        JsonNode item = generate().get("paths").get("/api/v1/accounts/{accountNo}");

        assertThat(item.get("get").get("operationId").asString()).isEqualTo("ACCOUNT_INQUIRY");
        assertThat(item.get("post").get("operationId").asString()).isEqualTo("TRANSFER");
        assertThat(item.get("get").has("requestBody")).as("GET has no body").isFalse();
        JsonNode param = item.get("get").get("parameters").get(0);
        assertThat(param.get("name").asString()).isEqualTo("accountNo");
        assertThat(param.get("in").asString()).isEqualTo("path");
        assertThat(param.get("required").asBoolean()).isTrue();
    }

    @Test
    void patternSyntaxIsConvertedToOpenApiTemplates() {
        rows.flow("A", "GET", "/a/{id:[0-9]+}");
        rows.flow("B", "GET", "/b/{*rest}");

        JsonNode paths = generate().get("paths");

        JsonNode id = paths.get("/api/a/{id}").get("get").get("parameters").get(0);
        assertThat(id.get("schema").get("pattern").asString()).isEqualTo("^[0-9]+$");
        assertThat(paths.has("/api/b/{rest}")).isTrue();
    }

    @Test
    void schemasFromDatabaseAreReferencedWithInternalRefsRewritten() {
        rows.schema("TRANSFER_REQ", """
                {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                 "required":["amount"],"properties":{"amount":{"$ref":"#/$defs/money"}},
                 "$defs":{"money":{"type":"number","exclusiveMinimum":0}}}""");
        rows.schema("TRANSFER_RES", "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}");
        rows.flow("TRANSFER", "POST", "/transfers", f -> f.withSchemas("TRANSFER_REQ", "TRANSFER_RES"));

        JsonNode doc = generate();

        JsonNode op = doc.get("paths").get("/api/transfers").get("post");
        assertThat(op.get("requestBody").get("required").asBoolean()).isTrue();
        assertThat(op.get("requestBody").get("content").get("application/json").get("schema").get("$ref").asString())
                .isEqualTo("#/components/schemas/TRANSFER_REQ");
        assertThat(op.get("responses").get("200").get("content").get("application/json").get("schema").get("$ref")
                .asString()).isEqualTo("#/components/schemas/TRANSFER_RES");
        JsonNode req = doc.get("components").get("schemas").get("TRANSFER_REQ");
        assertThat(req.has("$schema")).isFalse();
        assertThat(req.get("properties").get("amount").get("$ref").asString())
                .isEqualTo("#/components/schemas/TRANSFER_REQ/$defs/money");
    }

    @Test
    void withoutSchemaTheBodyIsDerivedFromMappingRules() {
        var flow = rows.flow("TRANSFER", "POST", "/transfers");
        var step = rows.step(flow, "s", 1);
        rows.rule(required(rows.stepRule(step, "$.to", "$.request.body.toAccount")));
        rows.stepRule(step, "$.amt", "$.request.body.amount.value");
        rows.stepRule(step, "$.ids", "$.request.body.items[*].id");
        rows.responseRule(flow, "$.transactionId", "$.steps.s.body.id");
        rows.responseRule(flow, "$.account.name", "$.steps.s.body.name");
        rows.rule(flow.id(), null, "FLOW_RESPONSE", "HEADER", "X-Source", null, "\"CORE\"");

        JsonNode op = generate().get("paths").get("/api/transfers").get("post");

        JsonNode body = op.get("requestBody").get("content").get("application/json").get("schema");
        assertThat(body.toString()).isEqualTo("{\"type\":\"object\",\"properties\":{"
                + "\"toAccount\":{},"
                + "\"amount\":{\"type\":\"object\",\"properties\":{\"value\":{}}},"
                + "\"items\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"id\":{}}}}},"
                + "\"required\":[\"toAccount\"]}");
        assertThat(op.get("requestBody").get("required").asBoolean()).isTrue();
        JsonNode ok = op.get("responses").get("200");
        assertThat(ok.get("content").get("application/json").get("schema").toString()).isEqualTo(
                "{\"type\":\"object\",\"properties\":{\"transactionId\":{},"
                        + "\"account\":{\"type\":\"object\",\"properties\":{\"name\":{}}}}}");
        assertThat(ok.get("headers").has("X-Source")).isTrue();
        assertThat(ok.get("headers").has("X-Correlation-Id")).isTrue();
        assertThat(op.get("responses").get("default").get("content").get("application/json").get("schema")
                .get("$ref").asString()).isEqualTo("#/components/schemas/ErrorResponse");
    }

    @Test
    void queryAndHeaderParametersAreDiscoveredFromRulesAndConditions() {
        var flow = rows.flow("F", "GET", "/f");
        var step = rows.step(flow, "s", 1, s -> s.withCondition("${request.query.mode} == 'full'"));
        rows.rule(required(rows.rule(flow.id(), step.id(), "STEP_REQUEST", "QUERY", "lang", "$.request.query.lang", null)));
        rows.rule(flow.id(), step.id(), "STEP_REQUEST", "HEADER", "X-User", "$.request.headers['x-user-id']", null);

        JsonNode params = generate().get("paths").get("/api/f").get("get").get("parameters");

        assertThat(params.toString())
                .contains("{\"name\":\"lang\",\"in\":\"query\",\"required\":true,\"schema\":{\"type\":\"string\"}}")
                .contains("{\"name\":\"mode\",\"in\":\"query\",\"required\":false,\"schema\":{\"type\":\"string\"}}")
                .contains("{\"name\":\"x-user-id\",\"in\":\"header\",\"required\":false,\"schema\":{\"type\":\"string\"}}")
                .contains("\"name\":\"X-Correlation-Id\"");
    }

    @Test
    void successStatusIsUsed() {
        rows.flow("CREATE", "POST", "/c", f -> new com.mhamzah.gateway.config.ConfigRows.FlowRow(f.id(), f.code(), f.name(),
                f.httpMethod(), f.pathPattern(), null, null, null, null, null, 201, null, "INHERIT", true));

        JsonNode responses = generate().get("paths").get("/api/c").get("post").get("responses");

        assertThat(responses.has("201")).isTrue();
        assertThat(responses.has("200")).isFalse();
    }

    @Test
    void adminReloadIsDocumentedWithTokenSecurity() {
        JsonNode doc = generate();

        JsonNode op = doc.get("paths").get("/admin/config/reload").get("post");
        assertThat(op.get("security").toString()).isEqualTo("[{\"adminToken\":[]}]");
        assertThat(op.get("responses").has("422")).isTrue();
        JsonNode scheme = doc.get("components").get("securitySchemes").get("adminToken");
        assertThat(scheme.toString()).isEqualTo("{\"type\":\"apiKey\",\"in\":\"header\",\"name\":\"X-Admin-Token\"}");
    }
}
