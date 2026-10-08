package com.mhamzah.gateway.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.masking.Masker;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(OutputCaptureExtension.class)
class LoggingServiceImplTest {

    private final LoggingService logging = new LoggingServiceImpl(new Masker(List.of("pin", "authorization"), "****"));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void requestLineHasTheStandardFormatWithMaskedHeadersAndBody(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/transfers");
        request.setQueryString("lang=id");
        request.addHeader("Authorization", "Bearer secret");
        request.addHeader("X-Correlation-Id", "corr-1");

        logging.logRequest(request, "{\"amount\":12500.50,\"pin\":\"123456\"}");

        assertThat(output).contains("REQUEST method=[POST] path=[/api/v1/transfers] "
                + "headers=[{Authorization=****, X-Correlation-Id=corr-1}] parameters=[{lang=id}] "
                + "body=[{\"amount\":12500.50,\"pin\":\"****\"}]");
        assertThat(output).doesNotContain("secret", "123456");
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isEqualTo("corr-1");
    }

    @Test
    void requestWithoutBodyOrParametersOmitsThoseParts(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts/1001");
        request.addHeader("X-Correlation-Id", "corr-2");

        logging.logRequest(request, null);

        assertThat(output).contains("REQUEST method=[GET] path=[/api/v1/accounts/1001] "
                + "headers=[{X-Correlation-Id=corr-2}]" + System.lineSeparator());
    }

    @Test
    void responseLineAddsTheCorrelationIdHeaderAndMasksTheBody(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/config/reload");
        request.addHeader("X-Correlation-Id", "corr-3");
        HttpHeaders headers = new HttpHeaders();

        logging.logResponse(request, headers, Map.of("pin", "9999"));

        assertThat(headers.getFirst("X-Correlation-Id")).isEqualTo("corr-3");
        assertThat(output).contains("RESPONSE method=[POST] path=[/admin/config/reload] "
                + "responseHeaders=[{X-Correlation-Id=corr-3}] responseBody=[{\"pin\":\"****\"}]");
    }

    @Test
    void nonJsonBodyIsLoggedAsIs(CapturedOutput output) {
        logging.logRequest(new MockHttpServletRequest("POST", "/api/x"), "not json");

        assertThat(output).contains("body=[not json]");
    }

    @Test
    void unsafeCorrelationIdIsReplacedAndStableForTheRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        request.addHeader("X-Correlation-Id", "bad id with spaces");

        String id = CorrelationId.of(request);

        assertThat(id).hasSize(36).isEqualTo(CorrelationId.of(request));
    }

    @Test
    void actuatorIsNotLogged(CapturedOutput output) {
        logging.logRequest(new MockHttpServletRequest("GET", "/actuator/health"), null);
        logging.logResponse(new MockHttpServletRequest("GET", "/actuator/health"), new HttpHeaders(), "{}");

        assertThat(output).doesNotContain("REQUEST", "RESPONSE");
    }
}
