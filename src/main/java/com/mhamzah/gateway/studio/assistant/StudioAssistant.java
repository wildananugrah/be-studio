package com.mhamzah.gateway.studio.assistant;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.mhamzah.gateway.mapping.JsonValues;
import tools.jackson.databind.JsonNode;
import com.mhamzah.gateway.studio.StudioConfig;
import com.mhamzah.gateway.studio.StudioService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The Gateway Studio assistant: answers developers' questions about setting up, developing and using this gateway.
 * Calls Claude through the official Anthropic SDK. The system prompt is the instructions plus the project's own
 * documentation (README, docs/, Makefile, HTTP examples, the Studio guide), cached, followed by a snapshot of the
 * live configuration and of what the developer has open in Studio.
 */
public class StudioAssistant {

    private static final Logger log = LoggerFactory.getLogger(StudioAssistant.class);
    private static final int MAX_CONTEXT_CHARS = 60_000;
    private static final int MAX_MESSAGE_CHARS = 20_000;

    private static final String INSTRUCTIONS = """
            You are the Gateway Studio assistant for this project: a database-configured JSON transformation and
            orchestration gateway (Spring Boot, PostgreSQL/Oracle, Liquibase) with Gateway Studio, its browser UI.
            Developers ask you how to set it up, run it, configure flows, write custom classes, use Studio, test, and
            troubleshoot.

            How to answer:
            - Base every answer on the project documentation below and on the live snapshot that follows it. They are
              the source of truth; do not invent table columns, properties, bean names, endpoints, make targets or menu
              items. If something is not covered, say so plainly and suggest where to look or what to try.
            - For how-to questions, give numbered steps naming the exact menus, tabs, buttons and fields as they appear
              in Studio (e.g. **Flows** > **+ New flow**, the **Mapping** tab, **Save & reload**), or the exact
              commands, files and settings. Show commands, SQL, YAML and Java in fenced code blocks with a language.
            - When you point to a Studio screen, add a navigation link so the developer can jump there. Supported links:
              [Flows](studio:flows), [Target systems](studio:targets), [Lookups](studio:lookups),
              [Schemas](studio:schemas), and for a flow [label](studio:flow/FLOW_CODE/TAB) where TAB is pipeline,
              mapping, tests or rows. Only link flow codes that exist in the live snapshot.
            - Use the live snapshot to make answers concrete (actual flow codes, targets, lookups, handler beans), and
              mention when the developer's open flow has unsaved changes or validation problems that matter.
            - Keep answers focused: lead with the answer, then the steps; no filler. Use the developer's language
              (reply in Indonesian when asked in Indonesian).
            - If the live snapshot's settings.studioMode is "view-only", this Studio cannot save changes or run tests:
              still explain the steps, but say they need an editable Studio (gateway.studio.mode=edit, e.g. in dev)
              or a Liquibase changeset / SQL for this environment.
            - Never ask for, repeat or guess secrets (admin token, AI keys, database passwords, API keys in target
              headers). Refer to them by setting name only.
            """;

    private final AssistantSettings settings;
    private final StudioService studio;
    private final int maxTokens;
    private final int historyMessages;
    private final AnthropicClient client;
    private final String knowledge;

    public StudioAssistant(AssistantSettings settings, StudioService studio, int maxTokens, int historyMessages) {
        this.settings = settings;
        this.studio = studio;
        this.maxTokens = maxTokens;
        this.historyMessages = historyMessages;
        this.knowledge = INSTRUCTIONS + "\n\n" + loadKnowledge();
        if (settings.configured()) {
            AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                    .timeout(Duration.ofMinutes(10))
                    .maxRetries(2);
            if (settings.bearer()) {
                builder.authToken(settings.authKey());
            } else {
                builder.apiKey(settings.authKey());
            }
            if (settings.baseUrl() != null) {
                builder.baseUrl(settings.baseUrl());
            }
            this.client = builder.build();
        } else {
            this.client = null;
        }
        log.info("Gateway Studio assistant: {} (knowledge {} chars)", settings, knowledge.length());
    }

    public AssistantSettings settings() {
        return settings;
    }

    /** One turn of the conversation as the browser sends it. */
    public record Turn(String role, String content) {}

    /** What the developer has open in Studio; {@code flow} is the draft of the open flow (may be unsaved). */
    public record UiContext(String screen, String tab, String flowCode, Object flow, Boolean unsaved, List<String> problems) {}

    /** Receives the answer as it streams. */
    public interface Sink {
        void text(String delta) throws IOException;

        void done(String stopReason, Map<String, Object> usage) throws IOException;

        void error(String message) throws IOException;
    }

    /**
     * Streams Claude's answer to the last user turn into {@code sink}. {@code cancelled} is checked between events so
     * a closed browser stops the request (and its billing).
     */
    public void answer(List<Turn> history, UiContext ui, Sink sink, BooleanSupplier cancelled) throws IOException {
        if (client == null) {
            sink.error("The assistant is not configured: set AI_AUTH_KEY (and AI_BASE_URL, AI_MODEL) in .env or the "
                    + "environment, then restart the gateway.");
            return;
        }
        List<Turn> turns = normalize(history);
        if (turns.isEmpty()) {
            sink.error("Ask a question first.");
            return;
        }
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(settings.model())
                .maxTokens(maxTokens)
                .systemOfTextBlockParams(List.of(
                        // stable prefix, cached: instructions + project docs
                        TextBlockParam.builder().text(knowledge)
                                .cacheControl(CacheControlEphemeral.builder().build()).build(),
                        // volatile: live configuration and the open screen
                        TextBlockParam.builder().text(liveContext(ui)).build()));
        if (settings.effort() != null) {
            params.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(settings.effort())).build());
        }
        for (Turn t : turns) {
            if ("assistant".equals(t.role())) {
                params.addAssistantMessage(t.content());
            } else {
                params.addUserMessage(t.content());
            }
        }

        Map<String, Object> usage = new LinkedHashMap<>();
        String stopReason = null;
        try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params.build())) {
            var it = stream.stream().iterator();
            while (it.hasNext()) {
                if (cancelled.getAsBoolean()) {
                    log.info("Assistant answer cancelled by the client");
                    return;
                }
                RawMessageStreamEvent event = it.next();
                event.messageStart().ifPresent(s -> {
                    usage.put("inputTokens", s.message().usage().inputTokens());
                    s.message().usage().cacheReadInputTokens().ifPresent(v -> usage.put("cacheReadInputTokens", v));
                    s.message().usage().cacheCreationInputTokens().ifPresent(v -> usage.put("cacheCreationInputTokens", v));
                });
                var delta = event.contentBlockDelta().flatMap(d -> d.delta().text());
                if (delta.isPresent()) {
                    sink.text(delta.get().text());
                }
                if (event.messageDelta().isPresent()) {
                    var md = event.messageDelta().get();
                    usage.put("outputTokens", md.usage().outputTokens());
                    stopReason = md.delta().stopReason().map(StopReason::asString).orElse(stopReason);
                    if (md.delta().stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
                        String why = md.delta().stopDetails().flatMap(d -> d.explanation()).orElse("");
                        sink.text("\n\n_The model declined to answer this request." + (why.isBlank() ? "" : " " + why) + "_");
                    } else if (md.delta().stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
                        sink.text("\n\n_The answer was cut off at the token limit (gateway.assistant.max-tokens)._");
                    }
                }
            }
        } catch (UnauthorizedException e) {
            sink.error("The AI endpoint " + endpoint() + " rejected AI_AUTH_KEY (401), sent as "
                    + (settings.bearer() ? "Authorization: Bearer" : "x-api-key") + ". Check that the key belongs to "
                    + "this endpoint (an OpenRouter key needs AI_BASE_URL=https://openrouter.ai/api), or set "
                    + "AI_AUTH_TYPE=api-key / bearer in .env, then restart.");
            return;
        } catch (PermissionDeniedException e) {
            sink.error("The AI key is not allowed to use this model or endpoint (403).");
            return;
        } catch (NotFoundException e) {
            sink.error("Not found (404): check AI_BASE_URL (it must serve the Anthropic Messages API at /v1/messages) "
                    + "and AI_MODEL '" + settings.model() + "'.");
            return;
        } catch (RateLimitException e) {
            sink.error("The AI endpoint is rate limiting (429). Try again in a moment.");
            return;
        } catch (AnthropicServiceException e) {
            log.warn("Assistant request failed: HTTP {} {}", e.statusCode(), e.getMessage());
            sink.error(describe(e));
            return;
        } catch (AnthropicIoException e) {
            log.warn("Assistant request failed: {}", e.getMessage());
            sink.error("Could not reach the AI endpoint (" + e.getMessage() + "). Check AI_BASE_URL and the network.");
            return;
        }
        log.info("Assistant answered: model={} stop={} usage={}", settings.model(), stopReason, usage);
        sink.done(stopReason, usage);
    }

    /** The last {@code historyMessages} turns, starting with a user turn, roles alternating, sizes capped. */
    List<Turn> normalize(List<Turn> history) {
        List<Turn> out = new ArrayList<>();
        if (history == null) {
            return out;
        }
        for (Turn t : history) {
            if (t == null || t.content() == null || t.content().isBlank()) {
                continue;
            }
            String role = "assistant".equals(t.role()) ? "assistant" : "user";
            String content = t.content().length() > MAX_MESSAGE_CHARS ? t.content().substring(0, MAX_MESSAGE_CHARS) : t.content();
            if (!out.isEmpty() && out.getLast().role().equals(role)) {
                out.set(out.size() - 1, new Turn(role, out.getLast().content() + "\n\n" + content));
            } else {
                out.add(new Turn(role, content));
            }
        }
        while (out.size() > historyMessages) {
            out.removeFirst();
        }
        while (!out.isEmpty() && !"user".equals(out.getFirst().role())) {
            out.removeFirst();
        }
        if (!out.isEmpty() && !"user".equals(out.getLast().role())) {
            out.removeLast();
        }
        return out;
    }

    /** The live configuration and the open screen, as compact JSON (secrets in target headers are redacted). */
    String liveContext(UiContext ui) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        try {
            ctx.put("settings", studio.catalog());
            StudioConfig saved = studio.load();
            ctx.put("savedConfiguration", redact(saved));
        } catch (RuntimeException e) {
            ctx.put("savedConfiguration", "unavailable: " + e.getMessage());
        }
        if (ui != null) {
            Map<String, Object> open = new LinkedHashMap<>();
            open.put("screen", ui.screen());
            open.put("tab", ui.tab());
            open.put("flowCode", ui.flowCode());
            open.put("unsavedChanges", ui.unsaved());
            open.put("validationProblems", ui.problems());
            open.put("openFlowDraft", ui.flow());
            ctx.put("openInStudio", open);
        }
        String json = JsonValues.MAPPER.writeValueAsString(ctx);
        if (json.length() > MAX_CONTEXT_CHARS) {
            json = json.substring(0, MAX_CONTEXT_CHARS) + "…(truncated)";
        }
        return "# Live snapshot (this gateway, now)\n\nThe saved configuration from the database, the gateway settings and "
                + "catalog of handler beans, and what the developer has open in Studio:\n\n```json\n" + json + "\n```";
    }

    private static StudioConfig redact(StudioConfig c) {
        List<StudioConfig.Target> targets = c.targets().stream().map(t -> new StudioConfig.Target(t.code(), t.baseUrl(),
                t.connectTimeoutMs(), t.readTimeoutMs(), t.bodyCodec(), t.enabled(),
                t.headers().stream().map(h -> new StudioConfig.Header(h.name(), secret(h.value()))).toList(),
                new StudioConfig.Tls(t.tls().mode(), store(t.tls().trustStore()), secret(t.tls().trustStorePassword()),
                        store(t.tls().keyStore()), secret(t.tls().keyStorePassword())))).toList();
        return new StudioConfig(c.version(), c.flows(), targets, c.lookups(), c.schemas());
    }

    /** Placeholders show where a secret comes from; literal secrets are masked. */
    private static String secret(String value) {
        return value == null ? null : value.startsWith("${") ? value : "****";
    }

    /** File paths and placeholders are fine to show; inline PEM (possibly a private key) is not. */
    private static String store(String value) {
        return value == null ? null : value.contains("-----BEGIN") ? "(inline PEM)" : value;
    }

    private static String loadKnowledge() {
        StringBuilder b = new StringBuilder("# Project documentation\n");
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:assistant/**/*");
            List<Resource> sorted = new ArrayList<>(List.of(resources));
            sorted.removeIf(r -> r.getFilename() == null || r.getFilename().isEmpty() || !r.isReadable());
            sorted.sort((a, c) -> path(a).compareTo(path(c)));
            for (Resource r : sorted) {
                try (InputStream in = r.getInputStream()) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    b.append("\n\n<document path=\"").append(path(r)).append("\">\n").append(text).append("\n</document>");
                }
            }
        } catch (IOException e) {
            log.warn("Assistant knowledge could not be read: {}", e.getMessage());
        }
        return b.toString();
    }

    private static String path(Resource r) {
        try {
            String url = r.getURL().toString();
            int i = url.lastIndexOf("assistant/");
            String p = i < 0 ? r.getFilename() : url.substring(i + "assistant/".length());
            return p.startsWith("project/") ? p.substring("project/".length()) : p;
        } catch (IOException e) {
            return String.valueOf(r.getFilename());
        }
    }

    /**
     * A readable message for an API error. An error event in the middle of a stream arrives with HTTP 200 and the
     * Anthropic error body ({"type":"error","error":{"type":...,"message":...}}) as the exception message.
     */
    String describe(AnthropicServiceException e) {
        String raw = String.valueOf(e.getMessage());
        String type = null;
        String message = raw;
        int brace = raw.indexOf('{');
        if (brace >= 0) {
            try {
                JsonNode error = JsonValues.MAPPER.readTree(raw.substring(brace)).path("error");
                if (error.isObject()) {
                    type = error.path("type").asString(null);
                    message = error.path("message").asString(raw);
                }
            } catch (RuntimeException ignored) {
                // not JSON: keep the raw text
            }
        }
        StringBuilder out = new StringBuilder("The AI endpoint ").append(endpoint()).append(" reported ")
                .append(type != null ? type : "an error").append(e.statusCode() != 200 ? " (HTTP " + e.statusCode() + ")" : "")
                .append(": ").append(message).append('.');
        if (message.contains("credential")) {
            out.append(" The key was accepted, but the gateway has no upstream provider credential for model '")
                    .append(settings.model()).append("'. Add one for that model in the gateway's console, or set another ")
                    .append("AI_MODEL in .env and restart.");
        } else if ("overloaded_error".equals(type) || e.statusCode() == 529) {
            out.append(" The model is overloaded; try again in a moment.");
        }
        return out.toString();
    }

    private String endpoint() {
        return settings.baseUrl() == null ? "https://api.anthropic.com" : settings.baseUrl();
    }

    /** For tests and the status endpoint. */
    int knowledgeLength() {
        return knowledge.length();
    }
}
