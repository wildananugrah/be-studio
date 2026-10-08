package com.mhamzah.gateway.audit;

import java.time.Instant;
import java.util.List;
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
        List<StepRecord> steps) {

    public AuditRecord {
        steps = List.copyOf(steps);
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
            Long durationMs) {}
}
