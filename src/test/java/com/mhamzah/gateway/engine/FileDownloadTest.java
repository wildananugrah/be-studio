package com.mhamzah.gateway.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mhamzah.gateway.codec.FileCodec;
import com.mhamzah.gateway.config.ConfigCompiler;
import com.mhamzah.gateway.config.ConfigValidationException;
import com.mhamzah.gateway.config.FlowRegistry;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.config.Rows;
import com.mhamzah.gateway.extension.DefaultErrorHandler;
import com.mhamzah.gateway.extension.snap.SnapErrorHandler;
import com.mhamzah.gateway.extension.snap.SnapResponseHandler;
import com.mhamzah.gateway.invoke.DownstreamResponse;
import com.mhamzah.gateway.mapping.MappingEngine;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** A step with body_codec fileCodec downloads a file; a FILE rule sends it to the client as received. */
class FileDownloadTest {

    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1', '.', '7', '\n', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF,
            (byte) 0xD3, 0, (byte) 0xFF, (byte) 0x80};

    private final FakeDownstream downstream = new FakeDownstream();
    private final AtomicReference<FlowRegistry> registry = new AtomicReference<>();
    private final Map<String, Object> beans = Map.of(
            "defaultErrorHandler", new DefaultErrorHandler(),
            SnapErrorHandler.BEAN_NAME, new SnapErrorHandler(registry::get, new MappingEngine()),
            SnapResponseHandler.BEAN_NAME, new SnapResponseHandler(registry::get, new MappingEngine()));
    private final FlowExecutor executor = new FlowExecutor(downstream, new MappingEngine(), new DefaultErrorHandler(), 1024);
    private final Rows rows = new Rows();

    @AfterEach
    void tearDown() {
        executor.close();
    }

    private FlowRegistry compile() {
        ConfigCompiler compiler = new ConfigCompiler(
                new ConfigCompiler.HandlerLookup() {
                    @Override
                    public <T> T find(String name, Class<T> type) {
                        Object b = beans.get(name);
                        return type.isInstance(b) ? type.cast(b) : null;
                    }
                },
                Map.of("CORE", new GatewayProperties.TargetSystem("http://core", 1000, 2000, Map.of(), null)),
                java.util.function.UnaryOperator.identity(),
                Duration.ofSeconds(5), Duration.ofSeconds(2));
        FlowRegistry reg = compiler.compile(rows.build());
        registry.set(reg);
        return reg;
    }

    private ExecutionResult post() {
        FlowRegistry reg = compile();
        var match = reg.match(HttpMethod.POST, "/download").orElseThrow();
        var in = new InboundRequest(HttpMethod.POST, "/download", Map.of(), Map.of(), "{\"uploadId\":\"U1\"}");
        return executor.execute(reg, match, in, "corr-1");
    }

    /** A SNAP flow (service code 24) whose step "backbone" downloads the document and whose response is that file. */
    private void downloadFlow() {
        var flow = rows.flow("DOWNLOAD", "POST", "/download", f -> f
                .withHandlers(null, SnapResponseHandler.BEAN_NAME, SnapErrorHandler.BEAN_NAME).withServiceCode("24"));
        var step = rows.step(flow, "backbone", 1, s -> s.withPathTemplate("/documents/{id}/content")
                .withBodyCodec(FileCodec.BEAN_NAME));
        rows.rule(flow.id(), step.id(), "STEP_REQUEST", "PATH", "id", "$.request.body.uploadId", null);
        rows.rule(flow.id(), null, "FLOW_RESPONSE", "FILE", null, "$.steps.backbone", null);
        rows.lookup("SNAP_ERR_CORE", "NOT_FOUND", "{\"snap\": \"404.01\", \"messagePath\": \"$.body.message\"}");
    }

    @Test
    void theDownloadedFileIsSentAsReceivedWithItsHeaders() {
        downloadFlow();
        downstream.on("/documents/U1/content", r -> DownstreamResponse.of(200, Map.of("content-type", "application/pdf",
                "content-disposition", "attachment; filename=\"bg-001.pdf\""), PDF));

        ExecutionResult result = post();

        assertThat(result.response().status()).isEqualTo(200);
        assertThat(result.response().file()).isNotNull();
        assertThat(result.response().file().bytes()).isEqualTo(PDF);
        assertThat(result.response().headers()).containsEntry("Content-Type", "application/pdf")
                .containsEntry("Content-Disposition", "attachment; filename=\"bg-001.pdf\"");
        assertThat(downstream.last("/documents/U1/content").headers()).containsEntry("Accept", "*/*");
        // the audit trail keeps the description, never the content
        assertThat(result.audit().responsePayload().path("filename").asString()).isEqualTo("bg-001.pdf");
        assertThat(result.audit().responsePayload().path("size").asInt()).isEqualTo(PDF.length);
        assertThat(result.audit().steps().getFirst().responsePayload().path("contentType").asString())
                .isEqualTo("application/pdf");
    }

    @Test
    void withoutContentDispositionTheFileNameComesFromTheStep() {
        downloadFlow();
        downstream.on("/documents/U1/content", r -> DownstreamResponse.of(200, Map.of("content-type", "image/png"), PDF));

        ExecutionResult result = post();

        assertThat(result.response().headers()).containsEntry("Content-Type", "image/png")
                .containsEntry("Content-Disposition", "attachment; filename=\"upload\"");
    }

    @Test
    void aJsonErrorIsTranslatedByTheSnapLookup() {
        downloadFlow();
        downstream.on("/documents/U1/content", r -> new DownstreamResponse(404, Map.of("content-type", "application/json"),
                "{\"message\":\"Upload tidak ditemukan: U1\",\"errorCode\":\"NOT_FOUND\"}"));

        ExecutionResult result = post();

        assertThat(result.response().file()).isNull();
        assertThat(result.response().status()).isEqualTo(404);
        assertThat(result.response().body().path("responseCode").asString()).isEqualTo("4042401");
        assertThat(result.response().body().path("responseMessage").asString()).isEqualTo("Upload tidak ditemukan: U1");
    }

    @Test
    void aJsonSuccessIsAnsweredAsUsual() {
        downloadFlow();
        downstream.on("/documents/U1/content", r -> new DownstreamResponse(200, Map.of("content-type", "application/json"),
                "{\"status\":\"PENDING\"}"));

        ExecutionResult result = post();

        assertThat(result.response().file()).isNull();
        assertThat(result.response().status()).isEqualTo(200);
        assertThat(result.response().body().path("responseCode").asString()).isEqualTo("2002400");
    }

    @Test
    void aFileLargerThanTheLimitFails() {
        downloadFlow();
        downstream.on("/documents/U1/content", r -> DownstreamResponse.of(200, Map.of("content-type", "application/pdf"),
                new byte[2048]));

        ExecutionResult result = post();

        assertThat(result.response().file()).isNull();
        assertThat(result.audit().errorType()).isEqualTo("DOWNSTREAM_INVALID_RESPONSE");
    }

    @Test
    void aFileRuleNeedsAFileCodecStep() {
        var flow = rows.flow("DOWNLOAD", "POST", "/download");
        rows.step(flow, "backbone", 1);
        rows.rule(flow.id(), null, "FLOW_RESPONSE", "FILE", null, "$.steps.backbone", null);

        assertThatThrownBy(this::compile).isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("does not download files");
    }

    @Test
    void aFileRuleTakesAStepAndOnlyOnce() {
        var flow = rows.flow("DOWNLOAD", "POST", "/download");
        rows.step(flow, "backbone", 1, s -> s.withBodyCodec(FileCodec.BEAN_NAME));
        rows.rule(flow.id(), null, "FLOW_RESPONSE", "FILE", null, "$.steps.backbone.body", null);
        rows.rule(flow.id(), null, "FLOW_RESPONSE", "FILE", null, "$.steps.backbone", null);
        rows.rule(flow.id(), null, "FLOW_RESPONSE", "FILE", null, "$.steps.backbone", null);

        assertThatThrownBy(this::compile).isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("source_path $.steps.<step>")
                .hasMessageContaining("at most one file");
    }
}
