package com.mhamzah.gateway.studio.assistant;

import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.logging.SkipBodyLogging;
import com.mhamzah.gateway.mapping.JsonValues;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The Gateway Studio assistant API, only when {@code gateway.studio.enabled} and {@code gateway.assistant.enabled}
 * are true; needs {@code X-Admin-Token}.
 *
 * <pre>
 * GET  /studio/api/assistant        {configured, model, baseUrl}
 * POST /studio/api/assistant/chat   {messages: [{role, content}], context: {...}}
 *      -> text/event-stream: "delta" {text} ... then "done" {stopReason, usage} or "error" {message}
 * </pre>
 */
@RestController
@SkipBodyLogging
@RequestMapping("/studio/api/assistant")
@ConditionalOnBooleanProperty("gateway.studio.enabled")
@ConditionalOnBooleanProperty(name = "gateway.assistant.enabled", matchIfMissing = true)
public class StudioAssistantController {

    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    private final StudioAssistant assistant;
    private final byte[] token;

    public StudioAssistantController(StudioAssistant assistant, GatewayProperties properties) {
        this.assistant = assistant;
        String configured = properties.admin().token();
        this.token = configured == null || configured.isBlank() ? null : configured.getBytes(StandardCharsets.UTF_8);
    }

    public record ChatRequest(List<StudioAssistant.Turn> messages, StudioAssistant.UiContext context) {}

    @GetMapping
    public ResponseEntity<Object> status(@RequestHeader(name = "X-Admin-Token", required = false) String supplied) {
        if (!authorized(supplied)) {
            return ResponseEntity.status(401).body(Map.of("errors", List.of("unauthorized")));
        }
        AssistantSettings s = assistant.settings();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", s.configured());
        out.put("model", s.model());
        out.put("baseUrl", s.baseUrl() == null ? "https://api.anthropic.com" : s.baseUrl());
        out.put("auth", s.bearer() ? "bearer" : "api-key");
        return ResponseEntity.ok(out);
    }

    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object chat(@RequestHeader(name = "X-Admin-Token", required = false) String supplied,
            @RequestBody ChatRequest request) {
        if (!authorized(supplied)) {
            return ResponseEntity.status(401).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("errors", List.of("unauthorized")));
        }
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        AtomicBoolean closed = new AtomicBoolean();
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));
        Thread.ofVirtual().name("studio-assistant").start(() -> {
            StudioAssistant.Sink sink = new StudioAssistant.Sink() {
                @Override
                public void text(String delta) throws IOException {
                    send(emitter, "delta", Map.of("text", delta));
                }

                @Override
                public void done(String stopReason, Map<String, Object> usage) throws IOException {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("stopReason", stopReason);
                    data.put("usage", usage);
                    send(emitter, "done", data);
                }

                @Override
                public void error(String message) throws IOException {
                    send(emitter, "error", Map.of("message", message));
                }
            };
            try {
                assistant.answer(request.messages(), request.context(), sink, closed::get);
                emitter.complete();
            } catch (IOException | IllegalStateException e) {
                closed.set(true); // the browser went away
                emitter.completeWithError(e);
            } catch (RuntimeException e) {
                try {
                    sink.error("Assistant failed: " + e.getMessage());
                    emitter.complete();
                } catch (IOException | IllegalStateException ignored) {
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, String event, Map<String, ?> data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(JsonValues.MAPPER.writeValueAsString(data), MediaType.TEXT_PLAIN));
    }

    private boolean authorized(String supplied) {
        return token != null && supplied != null
                && MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8));
    }
}
