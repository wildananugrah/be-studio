package com.mhamzah.gateway.routing;

import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.engine.ExecutionResult;
import com.mhamzah.gateway.engine.InboundRequest;
import com.mhamzah.gateway.extension.GatewayResponse;
import com.mhamzah.gateway.logging.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Catch-all endpoint under {@code gateway.api-base-path}; every request is routed by method + path. */
@RestController
public class GatewayController {

    private final GatewayService service;
    private final String basePath;

    public GatewayController(GatewayService service, GatewayProperties properties) {
        this.service = service;
        this.basePath = properties.apiBasePath();
    }

    @RequestMapping("${gateway.api-base-path:/api}/**")
    public ResponseEntity<String> handle(HttpServletRequest request, @RequestBody(required = false) String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        Map<String, String> query = parseQuery(request.getQueryString());
        String path = request.getRequestURI().substring(request.getContextPath().length() + basePath.length());
        InboundRequest inbound = new InboundRequest(HttpMethod.valueOf(request.getMethod()),
                path.isEmpty() ? "/" : path, headers, query, body);

        ExecutionResult result = service.handle(inbound, CorrelationId.of(request));
        return toResponseEntity(result.response());
    }

    /** First value per name. Parsed from the raw query string so a form-encoded body is never consumed. */
    public static Map<String, String> parseQuery(String queryString) {
        Map<String, String> query = new LinkedHashMap<>();
        if (queryString == null || queryString.isEmpty()) {
            return query;
        }
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            query.putIfAbsent(name, value);
        }
        return query;
    }

    private static ResponseEntity<String> toResponseEntity(GatewayResponse response) {
        HttpHeaders headers = new HttpHeaders();
        response.headers().forEach(headers::set);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.status(response.status())
                .headers(headers)
                .body(response.body() == null ? null : response.body().toString());
    }
}
