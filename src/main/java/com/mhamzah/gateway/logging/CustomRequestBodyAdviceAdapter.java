package com.mhamzah.gateway.logging;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Logs requests of handlers that take a {@code @RequestBody}; the others are logged by {@link LogInterceptor}. */
@ControllerAdvice
public class CustomRequestBodyAdviceAdapter extends RequestBodyAdviceAdapter {

    private final LoggingService loggingService;
    private final HttpServletRequest request;

    /** {@code request} is Spring's proxy to the current request. */
    public CustomRequestBodyAdviceAdapter(LoggingService loggingService, HttpServletRequest request) {
        this.loggingService = loggingService;
        this.request = request;
    }

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
            Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        boolean skip = parameter.getContainingClass().isAnnotationPresent(SkipBodyLogging.class);
        loggingService.logRequest(request, skip ? null : body);
        return body;
    }

    @Override
    public Object handleEmptyBody(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
            Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        loggingService.logRequest(request, null);
        return body;
    }
}
