package com.mhamzah.gateway.routing;

import com.mhamzah.gateway.extension.ErrorType;
import com.mhamzah.gateway.logging.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * An upload over the limit ({@code spring.servlet.multipart.*} for multipart, {@code gateway.files.max-size} for a
 * raw body) is refused before any flow runs: {@code 413 GW-413-FILE} in the standard error body.
 */
@RestControllerAdvice
public class UploadLimitAdvice {

    private static final Logger log = LoggerFactory.getLogger(UploadLimitAdvice.class);

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<String> tooLarge(MaxUploadSizeExceededException e, HttpServletRequest request) {
        String correlationId = CorrelationId.of(request);
        log.warn("{} {} refused: upload over the limit ({})", request.getMethod(), request.getRequestURI(), e.getMessage());
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("errorCode", ErrorType.FILE_TOO_LARGE.defaultCode());
        body.put("errorMessage", ErrorType.FILE_TOO_LARGE.defaultMessage());
        body.put("correlationId", correlationId);
        if (e.getMaxUploadSize() > 0) {
            body.putArray("details").add("maximum " + e.getMaxUploadSize() + " bytes");
        }
        return ResponseEntity.status(ErrorType.FILE_TOO_LARGE.defaultStatus())
                .header("X-Correlation-Id", correlationId)
                .contentType(MediaType.APPLICATION_JSON).body(body.toString());
    }
}
