package com.mhamzah.gateway.logging;

import com.mhamzah.gateway.mapping.JsonValues;
import com.mhamzah.gateway.masking.Masker;
import com.mhamzah.gateway.routing.GatewayController;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Standard request/response log format:
 * <pre>
 * REQUEST method=[POST] path=[/api/v1/transfers] headers=[{...}] parameters=[{...}] body=[{...}]
 * RESPONSE method=[POST] path=[/api/v1/transfers] responseHeaders=[{...}] responseBody=[{...}]
 * </pre>
 * Headers and JSON bodies are masked with {@code gateway.masking.fields}.
 */
@Component
public class LoggingServiceImpl implements LoggingService {

    private static final Logger log = LoggerFactory.getLogger(LoggingServiceImpl.class);

    private final Masker masker;

    public LoggingServiceImpl(Masker masker) {
        this.masker = masker;
    }

    @Override
    public void logRequest(HttpServletRequest request, Object body) {
        if (isActuator(request)) {
            return;
        }
        String correlationId = CorrelationId.of(request);
        MDC.put(CorrelationId.MDC_KEY, correlationId);

        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, request.getHeader(name));
        }
        headers.put(CorrelationId.HEADER, correlationId);
        // from the query string only: request.getParameterMap() would consume a form-encoded body
        Map<String, String> parameters = GatewayController.parseQuery(request.getQueryString());

        StringBuilder message = new StringBuilder("REQUEST ");
        message.append("method=[").append(request.getMethod()).append("] ");
        message.append("path=[").append(request.getRequestURI()).append("] ");
        message.append("headers=[").append(masker.maskHeaders(headers)).append("] ");
        if (!parameters.isEmpty()) {
            message.append("parameters=[").append(masker.maskHeaders(parameters)).append("] ");
        }
        if (body != null) {
            message.append("body=[").append(render(body)).append("]");
        }
        log.info(message.toString().stripTrailing());
    }

    @Override
    public void logResponse(HttpServletRequest request, HttpHeaders responseHeaders, Object body) {
        if (isActuator(request)) {
            return;
        }
        String correlationId = CorrelationId.of(request);
        // the flow has already removed its MDC entries by the time the response is written
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        if (!responseHeaders.containsHeader(CorrelationId.HEADER)) {
            responseHeaders.set(CorrelationId.HEADER, correlationId);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        responseHeaders.forEach((name, values) -> headers.put(name, String.join(",", values)));

        StringBuilder message = new StringBuilder("RESPONSE ");
        message.append("method=[").append(request.getMethod()).append("] ");
        message.append("path=[").append(request.getRequestURI()).append("] ");
        message.append("responseHeaders=[").append(masker.maskHeaders(headers)).append("] ");
        if (body != null) {
            message.append("responseBody=[").append(render(body)).append("]");
        }
        log.info(message.toString().stripTrailing());
    }

    private static boolean isActuator(HttpServletRequest request) {
        return request.getRequestURI().toLowerCase().contains("actuator");
    }

    /** JSON (as text or as an object) is masked and written compactly; anything else is written as-is. */
    private String render(Object body) {
        try {
            JsonNode json = body instanceof String text
                    ? JsonValues.MAPPER.readTree(text)
                    : JsonValues.MAPPER.valueToTree(body);
            return json == null || json.isMissingNode() ? String.valueOf(body) : masker.mask(json).toString();
        } catch (JacksonException | IllegalArgumentException e) {
            return String.valueOf(body);
        }
    }
}
