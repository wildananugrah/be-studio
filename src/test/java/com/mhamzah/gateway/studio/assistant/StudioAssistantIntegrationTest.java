package com.mhamzah.gateway.studio.assistant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mhamzah.gateway.TestcontainersConfiguration;
import com.mhamzah.gateway.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The assistant end to end, with a fake Anthropic Messages API (WireMock) streaming the answer. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "gateway.admin.token=assistant-token",
    "gateway.studio.enabled=true",
    "gateway.assistant.enabled=true",
    "gateway.assistant.env-file=does-not-exist.env",
})
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, WireMockConfiguration.class})
class StudioAssistantIntegrationTest {

    private static final WireMockServer AI = new WireMockServer(wireMockConfig().dynamicPort());
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static {
        AI.start();
        AI.stubFor(post(urlEqualTo("/v1/messages")).atPriority(5).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody(sse(
                        "message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":12,\"output_tokens\":1,\"cache_creation_input_tokens\":9000,\"cache_read_input_tokens\":0}}}",
                        "content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                        "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Open [Flows](studio:flows) \"}}",
                        "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"and click **+ New flow**.\"}}",
                        "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}",
                        "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":14}}",
                        "message_stop", "{\"type\":\"message_stop\"}"))));
        AI.stubFor(post(urlEqualTo("/v1/messages")).atPriority(1).withRequestBody(containing("trigger-no-credential"))
                .willReturn(aResponse().withHeader("Content-Type", "text/event-stream").withBody(sse("error",
                        "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"no eligible credential for model \\\"sonnet\\\"\"}}"))));
        AI.stubFor(post(urlEqualTo("/v1/messages")).atPriority(1).withRequestBody(containing("trigger-401"))
                .willReturn(aResponse().withStatus(401).withHeader("Content-Type", "application/json")
                        .withBody("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid API key\"}}")));
    }

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry registry) {
        registry.add("AI_BASE_URL", AI::baseUrl);
        registry.add("AI_AUTH_KEY", () -> "test-ai-key");
        registry.add("AI_AUTH_TYPE", () -> "api-key");
        registry.add("AI_MODEL", () -> "claude-opus-5-5");
    }

    @AfterAll
    static void stop() {
        AI.stop();
    }

    @LocalServerPort
    int port;

    private static String sse(String... nameData) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < nameData.length; i += 2) {
            b.append("event: ").append(nameData[i]).append("\ndata: ").append(nameData[i + 1]).append("\n\n");
        }
        return b.toString();
    }

    private HttpResponse<String> chat(String question, String token) throws Exception {
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"" + question + "\"}],"
                + "\"context\":{\"screen\":\"flows\",\"unsaved\":false,\"problems\":[]}}";
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/studio/api/assistant/chat"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .header("X-Admin-Token", token).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void streamsTheAnswerWithTheDocsAsACachedSystemPrompt() throws Exception {
        HttpResponse<String> r = chat("How do I create a flow?", "assistant-token");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("event:delta", "Open [Flows](studio:flows) ", "click **+ New flow**.",
                "event:done", "end_turn", "\"cacheCreationInputTokens\":9000");

        List<LoggedRequest> sent = AI.findAll(postRequestedFor(urlEqualTo("/v1/messages"))
                .withHeader("x-api-key", equalTo("test-ai-key")));
        assertThat(sent).isNotEmpty();
        String request = sent.getLast().getBodyAsString();
        assertThat(request).contains("\"model\":\"claude-opus-5-5\"", "\"stream\":true", "\"cache_control\"",
                "Gateway Studio user guide", "docs/CUSTOM-CLASSES.md", "Live snapshot", "ACCOUNT_INQUIRY",
                "How do I create a flow?");
        assertThat(request).doesNotContain("assistant-token");
    }

    @Test
    void reportsAnEndpointErrorAsAnErrorEvent() throws Exception {
        HttpResponse<String> r = chat("trigger-401", "assistant-token");
        assertThat(r.body()).contains("event:error", "rejected AI_AUTH_KEY");
    }

    @Test
    void explainsAnErrorEventSentInTheStream() throws Exception {
        HttpResponse<String> r = chat("trigger-no-credential", "assistant-token");
        assertThat(r.body()).contains("event:error", "reported overloaded_error: no eligible credential for model",
                "has no upstream provider credential").doesNotContain("HTTP 200");
    }

    @Test
    void needsTheAdminToken() throws Exception {
        assertThat(chat("hi", "wrong").statusCode()).isEqualTo(401);
        HttpResponse<String> status = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/studio/api/assistant"))
                .header("X-Admin-Token", "assistant-token").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(status.body()).contains("\"configured\":true", "\"model\":\"claude-opus-5-5\"");
    }
}
