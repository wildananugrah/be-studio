package com.mhamzah.gateway.studio.testing;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** The records of a Gateway Studio test run, from the generated cases to the captured evidence. */
public final class TestModel {

    private TestModel() {}

    /**
     * One call to make against a flow. {@code path} is concrete and relative to {@code gateway.api-base-path}
     * ({@code /v1/accounts/1001}); {@code expectedStatus} null means "record only, no verdict".
     */
    public record TestCase(
            String name,
            String description,
            String method,
            String path,
            Map<String, String> query,
            Map<String, String> headers,
            JsonNode body,
            Integer expectedStatus) {

        public TestCase {
            query = query == null ? Map.of() : Map.copyOf(query);
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    /** One HTTP message as it was sent or received; bodies are text, masked when they are JSON. */
    public record Message(String method, String url, Integer status, Map<String, String> headers, String body) {}

    /** A call from the gateway to a downstream system: outgoing request plus incoming response (or the failure). */
    public record DownstreamExchange(String targetSystem, Message request, Message response, long durationMs,
            String error) {}

    /**
     * {@code gw_audit_transaction} and {@code gw_audit_step} rows of the call. {@code enabled} false: the flow is not
     * audited, {@code note} says why and how to switch it on.
     */
    public record AuditTrail(boolean enabled, String note, Map<String, Object> transaction,
            List<Map<String, Object>> steps) {}

    public record TestResult(
            TestCase testCase,
            String correlationId,
            Boolean passed,
            String error,
            String startedAt,
            long durationMs,
            Message incomingRequest,
            List<DownstreamExchange> downstream,
            Message outgoingResponse,
            AuditTrail audit,
            List<String> logs) {}

    public record TestRun(
            String runId,
            String flowCode,
            String flowName,
            String method,
            String endpoint,
            String gatewayUrl,
            String executedAt,
            String configLoadedAt,
            List<String> maskedFields,
            int passed,
            int failed,
            int unverified,
            List<TestResult> results) {}
}
