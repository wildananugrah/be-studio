package com.mhamzah.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.engine.ExecutionResult;
import com.mhamzah.gateway.engine.InboundRequest;
import com.mhamzah.gateway.extension.GatewayResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.node.JsonNodeFactory;

/** Read-only and state-changing methods have separate mappings; every one still reaches the flow router. */
class GatewayControllerMethodsTest {

    private InboundRequest received;

    private MockMvc mvc() {
        GatewayProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "gateway.api-base-path", "/api", "gateway.audit.enabled", "false")))
                .bindOrCreate("gateway", Bindable.of(GatewayProperties.class));
        GatewayService service = new GatewayService(null, null, null, properties) {
            @Override
            public ExecutionResult handle(InboundRequest request, String correlationId) {
                received = request;
                return new ExecutionResult(GatewayResponse.of(200, JsonNodeFactory.instance.objectNode()), null);
            }
        };
        return MockMvcBuilders.standaloneSetup(new GatewayController(service, properties))
                .addPlaceholderValue("gateway.api-base-path", "/api")
                .build();
    }

    @Test
    void everyFlowMethodIsRouted() throws Exception {
        MockMvc mvc = mvc();
        for (HttpMethod method : new HttpMethod[] {HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.POST,
                HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE}) {
            received = null;
            mvc.perform(request(method, "/api/accounts/1")).andExpect(status().isOk());
            assertThat(received).as(method.name()).isNotNull();
            assertThat(received.method()).isEqualTo(method);
            assertThat(received.path()).isEqualTo("/accounts/1");
            assertThat(received.body()).isEmpty();
        }
    }

    @Test
    void jsonBodyIsPassedOn() throws Exception {
        mvc().perform(request(HttpMethod.POST, "/api/transfer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1}")).andExpect(status().isOk());
        assertThat(received.body()).isEqualTo("{\"amount\":1}");
        assertThat(received.form()).isNull();
    }

    @Test
    void uploadsStillReachTheirHandlers() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(multipart("/api/upload")
                .part(new MockPart("doc", "a.pdf", "%PDF".getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_PDF))
                .part(new MockPart("note", "hi".getBytes(StandardCharsets.UTF_8)))).andExpect(status().isOk());
        assertThat(received.files()).containsKey("doc");
        assertThat(received.form().get("note").asString()).isEqualTo("hi");

        mvc.perform(request(HttpMethod.PUT, "/api/upload").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("name=x")).andExpect(status().isOk());
        assertThat(received.form().get("name").asString()).isEqualTo("x");

        mvc.perform(request(HttpMethod.POST, "/api/upload").contentType(MediaType.APPLICATION_PDF)
                .content("%PDF".getBytes(StandardCharsets.UTF_8))).andExpect(status().isOk());
        assertThat(received.files()).containsKey("body");
    }
}
