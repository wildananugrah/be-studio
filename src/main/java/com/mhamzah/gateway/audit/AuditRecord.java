package com.mhamzah.gateway.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** One audited transaction ({@code gw_audit_transaction}) with its steps ({@code gw_audit_step}). Payloads are unmasked here. */
public record AuditRecord(
        String correlationId,
        String flowCode,
        String httpMethod,
        String path,
        int clientStatus,
        String errorType,
        String errorCode,
        JsonNode requestPayload,
        JsonNode responsePayload,
        Instant startedAt,
        long durationMs,
        List<StepRecord> steps,
        Map<String, String> requestHeaders,
        Map<String, String> responseHeaders) {

    public AuditRecord {
        steps = List.copyOf(steps);
    }

    public AuditRecord(String correlationId, String flowCode, String httpMethod, String path, int clientStatus,
            String errorType, String errorCode, JsonNode requestPayload, JsonNode responsePayload, Instant startedAt,
            long durationMs, List<StepRecord> steps) {
        this(correlationId, flowCode, httpMethod, path, clientStatus, errorType, errorCode, requestPayload,
                responsePayload, startedAt, durationMs, steps, null, null);
    }

    /**
     * @param outcome SUCCESS, FAILED, SKIPPED, TIMEOUT or CANCELLED (stopped because a sibling failed or the flow timed out)
     */
    public record StepRecord(
            String stepName,
            String targetSystem,
            String httpMethod,
            String url,
            Integer httpStatus,
            String outcome,
            JsonNode requestPayload,
            JsonNode responsePayload,
            Instant startedAt,
            Long durationMs,
            Map<String, String> requestHeaders,
            Map<String, String> responseHeaders) {

        public StepRecord(String stepName, String targetSystem, String httpMethod, String url, Integer httpStatus,
                String outcome, JsonNode requestPayload, JsonNode responsePayload, Instant startedAt, Long durationMs) {
            this(stepName, targetSystem, httpMethod, url, httpStatus, outcome, requestPayload, responsePayload,
                    startedAt, durationMs, null, null);
        }
    }
}
