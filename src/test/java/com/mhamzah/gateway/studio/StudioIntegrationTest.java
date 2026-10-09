package com.mhamzah.gateway.studio;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.TestcontainersConfiguration;
import com.mhamzah.gateway.WireMockConfiguration;
import com.mhamzah.gateway.config.FlowRegistryHolder;
import com.mhamzah.gateway.mapping.JsonValues;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "gateway.admin.token=studio-token",
    "gateway.studio.enabled=true",
    "gateway.assistant.enabled=false",
})
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, WireMockConfiguration.class})
class StudioIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    FlowRegistryHolder holder;

    record Response(int status, String text) {
        JsonNode json() {
            return JsonValues.MAPPER.readTree(text);
        }
    }

    Response call(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JsonValues.MAPPER.writeValueAsString(body)))
                .header("Content-Type", "application/json");
        if (token != null) {
            b.header("X-Admin-Token", token);
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), r.body());
    }

    ObjectNode config() throws Exception {
        Response r = call("GET", "/studio/api/config", "studio-token", null);
        assertThat(r.status()).isEqualTo(200);
        return (ObjectNode) r.json();
    }

    static ObjectNode flow(JsonNode config, String code) {
        for (JsonNode f : config.get("flows")) {
            if (code.equals(f.get("code").asString())) {
                return (ObjectNode) f;
            }
        }
        throw new AssertionError("no flow " + code);
    }

    static ObjectNode lookupEntry(JsonNode config, String code, String source) {
        for (JsonNode l : config.get("lookups")) {
            if (code.equals(l.get("code").asString())) {
                for (JsonNode e : l.get("entries")) {
                    if (source.equals(e.get("source").asString())) {
                        return (ObjectNode) e;
                    }
                }
            }
        }
        throw new AssertionError("no lookup entry " + code + "/" + source);
    }

    static ObjectNode save(String baseVersion, JsonNode config) {
        ObjectNode body = JsonValues.MAPPER.createObjectNode();
        body.put("baseVersion", baseVersion);
        body.set("config", config);
        return body;
    }

    @Test
    void servesThePageAndGuardsTheApi() throws Exception {
        Response page = call("GET", "/studio/", null, null);
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.text()).contains("<title>Gateway Studio</title>");
        assertThat(call("GET", "/studio/vendor/htm-preact.js", null, null).status()).isEqualTo(200);

        assertThat(call("GET", "/studio/api/config", null, null).status()).isEqualTo(401);
        assertThat(call("GET", "/studio/api/config", "wrong", null).status()).isEqualTo(401);
    }

    @Test
    void assistantCanBeSwitchedOffOnItsOwn() throws Exception {
        assertThat(call("GET", "/studio/api/assistant", "studio-token", null).status()).isEqualTo(404);
        assertThat(call("GET", "/studio/api/catalog", "studio-token", null).json().get("assistantEnabled").asBoolean())
                .isFalse();
        assertThat(call("GET", "/studio/", null, null).status()).isEqualTo(200);
    }

    @Test
    void loadsTheStoredConfigurationAndTheCatalog() throws Exception {
        ObjectNode config = config();
        assertThat(config.get("version").asString()).hasSize(16);
        ObjectNode inquiry = flow(config, "ACCOUNT_INQUIRY");
        assertThat(inquiry.get("path").asString()).isEqualTo("/v1/accounts/{accountNo}");
        assertThat(inquiry.get("steps").get(0).get("rules").get(0).get("type").asString()).isEqualTo("PATH");

        JsonNode catalog = call("GET", "/studio/api/catalog", "studio-token", null).json();
        assertThat(catalog.get("messageHandlers").toString()).contains("channelGuard", "requestSigner");
        assertThat(catalog.get("errorHandlers").toString()).contains("\"lookupCode\":\"CORE_BANKING_ERRORS\"");
        assertThat(config.get("schemas").toString())
                .contains("\"code\":\"TRANSFER_REQUEST\"", "\"description\":\"Inbound transfer request\"");
    }

    @Test
    void previewRunsTheRealRulesAgainstASample() throws Exception {
        ObjectNode body = JsonValues.MAPPER.createObjectNode();
        body.set("config", config());
        body.put("flow", "ACCOUNT_INQUIRY");
        body.set("sample", JsonValues.MAPPER.readTree("""
                {"request": {"path": {"accountNo": "1001"}},
                 "steps": {"inquiry": {"outcome": "SUCCESS", "status": 200,
                   "body": {"acctNo": "1001", "acctName": "BUDI", "availBal": "1500.50", "statusCd": "A"}}}}
                """));

        JsonNode preview = call("POST", "/studio/api/preview", "studio-token", body).json();

        assertThat(preview.get("errors")).isEmpty();
        assertThat(preview.get("output").get("body").get("accountName").asString()).isEqualTo("BUDI");
        assertThat(preview.get("output").get("body").get("status").asString()).isEqualTo("ACTIVE");
        assertThat(preview.get("rules").get(0).get("written").asBoolean()).isTrue();

        body.put("step", "inquiry");
        JsonNode step = call("POST", "/studio/api/preview", "studio-token", body).json();
        assertThat(step.get("output").get("path").get("acc").asString()).isEqualTo("1001");
    }

    @Test
    void rejectsInvalidAndStaleSavesWithoutWriting() throws Exception {
        ObjectNode config = config();
        String version = config.get("version").asString();
        ((ObjectNode) flow(config, "ACCOUNT_INQUIRY").get("steps").get(0)).put("targetSystem", "NOWHERE");

        JsonNode validate = call("POST", "/studio/api/validate", "studio-token", config).json();
        assertThat(validate.get("errors").toString()).contains("NOWHERE");

        Response invalid = call("PUT", "/studio/api/config", "studio-token", save(version, config));
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(invalid.json().get("errors").toString()).contains("NOWHERE");

        Response stale = call("PUT", "/studio/api/config", "studio-token", save("0000000000000000", config()));
        assertThat(stale.status()).isEqualTo(409);

        assertThat(config().get("version").asString()).isEqualTo(version);
    }

    @Test
    void savesReloadsAndReadsBackTheSameDocument() throws Exception {
        ObjectNode original = config();
        ObjectNode changed = original.deepCopy();
        lookupEntry(changed, "ACCOUNT_STATUS", "A").put("target", "OPEN");
        ObjectNode inquiry = flow(changed, "ACCOUNT_INQUIRY");
        ((ArrayNode) inquiry.get("response")).addObject()
                .put("type", "HEADER").put("target", "X-Studio").put("constant", "yes");

        Response saved = call("PUT", "/studio/api/config", "studio-token", save(original.get("version").asString(), changed));
        assertThat(saved.status()).as(saved.text()).isEqualTo(200);
        assertThat(holder.current().lookups().get("ACCOUNT_STATUS").entries().get("A").asString()).isEqualTo("OPEN");

        ObjectNode reread = config();
        assertThat(reread.get("version").asString()).isEqualTo(saved.json().get("version").asString());
        assertThat(lookupEntry(reread, "ACCOUNT_STATUS", "A").get("target").asString()).isEqualTo("OPEN");
        assertThat(flow(reread, "ACCOUNT_INQUIRY").get("response").toString()).contains("X-Studio");
        assertThat(reread.get("flows").size()).isEqualTo(original.get("flows").size());

        // put the demo configuration back; the next save must be based on the new version
        Response restored = call("PUT", "/studio/api/config", "studio-token", save(reread.get("version").asString(), original));
        assertThat(restored.status()).as(restored.text()).isEqualTo(200);
        assertThat(restored.json().get("version").asString()).isEqualTo(original.get("version").asString());
    }

    @Test
    void addsASchemaAndRejectsOneThatIsNotDraft202012() throws Exception {
        ObjectNode original = config();
        ObjectNode changed = original.deepCopy();
        ((ArrayNode) changed.get("schemas")).addObject()
                .put("code", "INQUIRY_RESPONSE").put("description", "made in the studio")
                .put("text", "{\"type\":\"object\",\"required\":[\"accountNo\"]}");
        flow(changed, "ACCOUNT_INQUIRY").put("responseSchema", "INQUIRY_RESPONSE");

        ObjectNode broken = changed.deepCopy();
        ((ObjectNode) broken.get("schemas").get(broken.get("schemas").size() - 1)).put("text", "{\"type\":\"nope\"}");
        Response rejected = call("PUT", "/studio/api/config", "studio-token", save(original.get("version").asString(), broken));
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(rejected.json().get("errors").toString()).contains("INQUIRY_RESPONSE", "draft 2020-12");

        Response saved = call("PUT", "/studio/api/config", "studio-token", save(original.get("version").asString(), changed));
        assertThat(saved.status()).as(saved.text()).isEqualTo(200);
        ObjectNode reread = config();
        assertThat(reread.get("schemas").toString()).contains("made in the studio");
        assertThat(flow(reread, "ACCOUNT_INQUIRY").get("responseSchema").asString()).isEqualTo("INQUIRY_RESPONSE");

        Response restored = call("PUT", "/studio/api/config", "studio-token", save(reread.get("version").asString(), original));
        assertThat(restored.status()).as(restored.text()).isEqualTo(200);
        assertThat(config().get("schemas").toString()).doesNotContain("INQUIRY_RESPONSE");
    }

    @Test
    void generatesRunsAndDocumentsUnitTests() throws Exception {
        JsonNode transfer = call("GET", "/studio/api/tests/TRANSFER/cases", "studio-token", null).json();
        assertThat(transfer.get("cases").toString())
                .contains("Happy path - all fields", "Missing required field 'amount'", "Wrong type for 'amount'");

        JsonNode cases = call("GET", "/studio/api/tests/ACCOUNT_INQUIRY/cases", "studio-token", null).json().get("cases");
        assertThat(cases.get(0).get("path").asString()).isEqualTo("/v1/accounts/1001");
        ObjectNode body = JsonValues.MAPPER.createObjectNode();
        body.set("cases", cases);

        Response ran = call("POST", "/studio/api/tests/ACCOUNT_INQUIRY/runs", "studio-token", body);
        assertThat(ran.status()).as(ran.text()).isEqualTo(200);
        JsonNode run = ran.json();
        JsonNode first = run.get("results").get(0);
        assertThat(first.get("passed").asBoolean()).as(first.toString()).isTrue();
        assertThat(first.get("incomingRequest").get("url").asString()).endsWith("/api/v1/accounts/1001");
        assertThat(first.get("downstream").get(0).get("targetSystem").asString()).isEqualTo("CORE_BANKING");
        assertThat(first.get("downstream").get(0).get("request").get("url").asString()).contains("/core/accounts/1001");
        assertThat(first.get("downstream").get(0).get("response").get("status").asInt()).isEqualTo(200);
        assertThat(first.get("outgoingResponse").get("status").asInt()).isEqualTo(200);
        assertThat(first.get("audit").get("enabled").asBoolean()).isTrue();
        assertThat(first.get("audit").get("transaction").get("correlation_id").asString())
                .isEqualTo(first.get("correlationId").asString());
        assertThat(first.get("audit").get("steps").get(0).get("step_name").asString()).isEqualTo("inquiry");
        assertThat(first.get("logs").toString()).contains("step=inquiry");

        String id = run.get("runId").asString();
        Response md = call("GET", "/studio/api/tests/runs/" + id + "/report?format=md", "studio-token", null);
        assertThat(md.status()).isEqualTo(200);
        assertThat(md.text()).contains("# Unit Test Report - ACCOUNT_INQUIRY", "Incoming request (client to gateway)",
                "Outgoing request (gateway to downstream)", "Incoming response (downstream to gateway)",
                "Outgoing response (gateway to client)", "Audit trail", "Logs", first.get("correlationId").asString());
        assertThat(call("GET", "/studio/api/tests/runs/" + id + "/report?format=docx", "studio-token", null).text())
                .startsWith("PK");
        assertThat(call("GET", "/studio/api/tests/runs/" + id + "/report?format=pdf", "studio-token", null).text())
                .startsWith("%PDF");
        assertThat(call("GET", "/studio/api/tests/runs/" + id + "/report?format=pdf", null, null).status()).isEqualTo(401);
    }

    @Test
    void auditTrailShowsEachCallWithStepsPayloadsAndLogs() throws Exception {
        String id = "AUDIT-TRAIL-" + System.nanoTime();
        HttpResponse<String> ok = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/accounts/1001"))
                .header("X-Correlation-Id", id).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(ok.statusCode()).isEqualTo(200);
        HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/accounts/9999"))
                .header("X-Correlation-Id", id + "-ERR").build(), HttpResponse.BodyHandlers.ofString());

        // the audit writer is asynchronous
        JsonNode list = null;
        for (int i = 0; i < 50; i++) {
            list = call("GET", "/studio/api/audit?q=" + id, "studio-token", null).json();
            if (list.get("totalElements").asLong() > 0) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(list.get("content").get(0).get("flow_code").asString()).isEqualTo("ACCOUNT_INQUIRY");
        assertThat(list.get("content").get(0).get("client_status").asInt()).isEqualTo(200);
        assertThat(list.get("content").get(0).has("request_payload")).isFalse();

        JsonNode detail = call("GET", "/studio/api/audit/" + id, "studio-token", null).json();
        assertThat(detail.get("transaction").get("response_payload").asString()).contains("BUDI SANTOSO");
        assertThat(detail.get("steps").get(0).get("step_name").asString()).isEqualTo("inquiry");
        assertThat(detail.get("steps").get(0).get("url").asString()).contains("/core/accounts/1001");
        assertThat(detail.get("steps").get(0).get("response_payload").asString()).contains("acctNo");
        assertThat(detail.get("logs").toString()).contains("step=inquiry", "flow=ACCOUNT_INQUIRY");

        for (int i = 0; i < 50 && call("GET", "/studio/api/audit/" + id + "-ERR", "studio-token", null).status() != 200; i++) {
            Thread.sleep(100);
        }
        JsonNode errors = call("GET", "/studio/api/audit?status=errors&flow=ACCOUNT_INQUIRY", "studio-token", null).json();
        assertThat(errors.get("content").toString()).contains(id + "-ERR").doesNotContain("\"" + id + "\"");
        assertThat(call("GET", "/studio/api/audit?status=nope", "studio-token", null).status()).isEqualTo(400);
        assertThat(call("GET", "/studio/api/audit/does-not-exist", "studio-token", null).status()).isEqualTo(404);
        assertThat(call("GET", "/studio/api/audit", null, null).status()).isEqualTo(401);
        assertThat(call("GET", "/studio/api/audit/status", "studio-token", null).json().get("auditEnabled").asBoolean()).isTrue();
    }
}
