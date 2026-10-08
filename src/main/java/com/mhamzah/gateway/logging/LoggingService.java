package com.mhamzah.gateway.logging;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

/** Writes the standard {@code REQUEST ...} / {@code RESPONSE ...} log lines for inbound HTTP calls. */
public interface LoggingService {

    void logRequest(HttpServletRequest request, Object body);

    /** {@code responseHeaders} are the headers about to be sent; the correlation ID header is added when missing. */
    void logResponse(HttpServletRequest request, HttpHeaders responseHeaders, Object body);
}
