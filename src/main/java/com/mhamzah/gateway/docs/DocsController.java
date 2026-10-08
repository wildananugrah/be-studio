package com.mhamzah.gateway.docs;

import com.mhamzah.gateway.config.FlowRegistryHolder;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.logging.SkipBodyLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.MediaType;
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

    @GetMapping(path = "/docs/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public String openApi() {
        return OpenApiGenerator.generate(holder.current(), apiBasePath, title).toString();
    }

    @GetMapping(path = {"/docs", "/docs/"}, produces = MediaType.TEXT_HTML_VALUE)
    public String swaggerUi(HttpServletRequest request) {
        String ctx = HtmlUtils.htmlEscape(request.getContextPath());
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
                  <script>
                    window.ui = SwaggerUIBundle({ url: '%s/docs/openapi.json', dom_id: '#swagger-ui', deepLinking: true });
                  </script>
                </body>
                </html>
                """.formatted(HtmlUtils.htmlEscape(title), ctx, ctx, ctx);
    }
}
