package com.mhamzah.gateway.docs;

import com.mhamzah.gateway.config.FlowRegistry;
import com.mhamzah.gateway.config.FlowRegistryHolder;
import com.mhamzah.gateway.mapping.JsonValues;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.node.ArrayNode;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.logging.SkipBodyLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * API documentation, only registered when {@code gateway.docs.enabled=true}: Swagger UI at {@code /docs} and the
 * OpenAPI description at {@code /docs/openapi.json}, generated from the live configuration on every request.
 */
@RestController
@SkipBodyLogging
@ConditionalOnBooleanProperty("gateway.docs.enabled")
public class DocsController {

    private final FlowRegistryHolder holder;
    private final String apiBasePath;
    private final String title;

    public DocsController(FlowRegistryHolder holder, GatewayProperties properties) {
        this.holder = holder;
        this.apiBasePath = properties.apiBasePath();
        this.title = properties.docs().title();
    }

    /** All flows, grouped by application; {@code ?application=CODE}: only that application's flows (404 if unknown). */
    @GetMapping(path = "/docs/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> openApi(@RequestParam(required = false) String application) {
        FlowRegistry registry = holder.current();
        if (application != null && registry.applications().stream().noneMatch(a -> a.code().equals(application))) {
            return ResponseEntity.status(404).body(JsonValues.MAPPER.createObjectNode()
                    .put("errorCode", "GW-404-APPLICATION").put("errorMessage", "No application " + application).toString());
        }
        return ResponseEntity.ok(OpenApiGenerator.generate(registry, apiBasePath, title, application).toString());
    }

    /**
     * Swagger UI with an application selector in its top bar: "All applications" (one section per application) and
     * one description per application that has flows. {@code /docs?urls.primaryName=<name>} opens one directly.
     */
    @GetMapping(path = {"/docs", "/docs/"}, produces = MediaType.TEXT_HTML_VALUE)
    public String swaggerUi(HttpServletRequest request) {
        String ctx = HtmlUtils.htmlEscape(request.getContextPath());
        FlowRegistry registry = holder.current();
        ArrayNode urls = JsonValues.MAPPER.createArrayNode();
        urls.addObject().put("url", request.getContextPath() + "/docs/openapi.json").put("name", "All applications");
        Set<String> withFlows = registry.flows().stream().map(registry::applicationOf).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        registry.applications().stream().filter(a -> withFlows.contains(a.code())).forEach(a -> urls.addObject()
                .put("url", request.getContextPath() + "/docs/openapi.json?application="
                        + URLEncoder.encode(a.code(), StandardCharsets.UTF_8))
                .put("name", a.name() == null || a.name().isBlank() ? a.code() : a.code() + " · " + a.name()));
        // JSON inside a <script>: no "</" that could close it
        String urlsJs = urls.toString().replace("</", "<\\/");
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s</title>
                  <link rel="stylesheet" href="%s/webjars/swagger-ui/swagger-ui.css">
                </head>
                <body>
                  <div id="swagger-ui"></div>
                  <script src="%s/webjars/swagger-ui/swagger-ui-bundle.js"></script>
                  <script src="%s/webjars/swagger-ui/swagger-ui-standalone-preset.js"></script>
                  <script>
                    window.ui = SwaggerUIBundle({
                      urls: %s, dom_id: '#swagger-ui', deepLinking: true, layout: 'StandaloneLayout',
                      presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset]
                    });
                  </script>
                </body>
                </html>
                """.formatted(HtmlUtils.htmlEscape(title), ctx, ctx, ctx, urlsJs);
    }
}
