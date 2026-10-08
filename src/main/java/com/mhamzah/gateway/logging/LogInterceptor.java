package com.mhamzah.gateway.logging;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Logs requests whose handler takes no {@code @RequestBody} (those are logged by
 * {@link CustomRequestBodyAdviceAdapter} once the body is read), and clears the correlation ID from the MDC
 * when the request completes.
 */
@Component
public class LogInterceptor implements HandlerInterceptor {

    private final LoggingService loggingService;

    public LogInterceptor(LoggingService loggingService) {
        this.loggingService = loggingService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() == DispatcherType.REQUEST && !readsBody(handler)) {
            loggingService.logRequest(request, null);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        MDC.remove(CorrelationId.MDC_KEY);
    }

    private static boolean readsBody(Object handler) {
        return handler instanceof HandlerMethod method && Arrays.stream(method.getMethodParameters())
                .anyMatch(p -> p.hasParameterAnnotation(RequestBody.class));
    }
}
