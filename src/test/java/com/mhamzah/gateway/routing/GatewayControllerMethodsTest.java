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
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockPart;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Read-only and state-changing methods have separate mappings; every one still reaches the flow router, under the
 * {@code gateway.api-base-path} prefix that {@link GatewayWebConfiguration} adds.
 */
class GatewayControllerMethodsTest {

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class Mvc {}

    private InboundRequest received;

    private MockMvc mvc(String basePath) {
        GatewayProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "gateway.api-base-path", basePath, "gateway.audit.enabled", "false")))
                .bindOrCreate("gateway", Bindable.of(GatewayProperties.class));
        GatewayService service = new GatewayService(null, null, null, properties) {
            @Override
            public ExecutionResult handle(InboundRequest request, String correlationId) {
                received = request;
                return new ExecutionResult(GatewayResponse.of(200, JsonNodeFactory.instance.objectNode()), null);
            }
        };
        GenericWebApplicationContext context = new GenericWebApplicationContext(new MockServletContext());
        AnnotationConfigUtils.registerAnnotationConfigProcessors(context);
        context.registerBean(Mvc.class);
        context.registerBean(GatewayWebConfiguration.class);
        context.registerBean(GatewayProperties.class, () -> properties);
        context.registerBean(GatewayController.class, () -> new GatewayController(service, properties));
        context.refresh();
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private MockMvc mvc() {
        return mvc("/api");
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
    void onlyPathsUnderTheBasePathAreRouted() throws Exception {
        MockMvc mvc = mvc("/gw/v1");
        mvc.perform(request(HttpMethod.GET, "/gw/v1/accounts/1")).andExpect(status().isOk());
        assertThat(received.path()).isEqualTo("/accounts/1");

        received = null;
        mvc.perform(request(HttpMethod.GET, "/api/accounts/1")).andExpect(status().isNotFound());
        mvc.perform(request(HttpMethod.POST, "/accounts/1")).andExpect(status().isNotFound());
        assertThat(received).isNull();
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
