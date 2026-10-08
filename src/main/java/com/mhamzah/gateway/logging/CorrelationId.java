package com.mhamzah.gateway.logging;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.regex.Pattern;

/** The request's correlation ID: resolved once per request and shared by logs, the flow and the response. */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final String ATTRIBUTE = CorrelationId.class.getName();
    /** Client-supplied correlation IDs are only trusted when short and free of control characters. */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:\\-]{1,64}");

    private CorrelationId() {}

    /** The client's {@code X-Correlation-Id} when safe, otherwise a new UUID; the same value for the whole request. */
    public static String of(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof String id) {
            return id;
        }
        String supplied = request.getHeader(HEADER);
        String id = supplied != null && SAFE.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        return id;
    }
}
