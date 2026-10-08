package com.mhamzah.gateway.studio.testing;

import com.mhamzah.gateway.studio.testing.TestModel.AuditTrail;
import com.mhamzah.gateway.studio.testing.TestModel.DownstreamExchange;
import com.mhamzah.gateway.studio.testing.TestModel.Message;
import com.mhamzah.gateway.studio.testing.TestModel.TestCase;
import com.mhamzah.gateway.studio.testing.TestModel.TestResult;
import com.mhamzah.gateway.studio.testing.TestModel.TestRun;
import com.mhamzah.gateway.mapping.JsonValues;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The unit test document of a run as format-neutral blocks; {@link ReportRenderers} turns them into Markdown, Word
 * or PDF. Per test case: incoming request (client to gateway), outgoing requests (gateway to downstream), incoming
 * responses (downstream to gateway), outgoing response (gateway to client), audit trail and logs.
 */
public final class TestReport {

    /** One piece of the document. */
    public sealed interface Block {}

    public record Title(String text, String subtitle) implements Block {}

    public record Heading(int level, String text) implements Block {}

    public record Paragraph(String text) implements Block {}

    /** Two-column table of labels and values. */
    public record Fields(List<String[]> rows) implements Block {}

    public record Table(List<String> header, List<List<String>> rows) implements Block {}

    /** Preformatted text (HTTP messages, JSON, logs). */
    public record Code(String text) implements Block {}

    public record PageBreak() implements Block {}

    /** What to include besides the HTTP messages. */
    public record Options(boolean audit, boolean logs) {}

    private static final Set<String> PAYLOAD_COLUMNS = Set.of("request_payload", "response_payload");

    private TestReport() {}

    public static List<Block> build(TestRun run, Options options) {
        List<Block> out = new ArrayList<>();
        out.add(new Title("Unit Test Report - " + run.flowCode(), run.flowName() + " · " + run.method() + " " + run.endpoint()));
        out.add(new Fields(List.of(
                row("Flow", run.flowCode() + " (" + run.flowName() + ")"),
                row("Endpoint", run.method() + " " + run.endpoint()),
                row("Gateway", run.gatewayUrl()),
                row("Executed at", run.executedAt()),
                row("Configuration loaded at", run.configLoadedAt()),
                row("Run ID", run.runId()),
                row("Result", run.passed() + " passed, " + run.failed() + " failed"
                        + (run.unverified() > 0 ? ", " + run.unverified() + " without expected status" : "")
                        + " - " + run.results().size() + " test case(s)"),
                row("Masked fields", String.join(", ", run.maskedFields())))));

        out.add(new Heading(1, "Summary"));
        List<List<String>> summary = new ArrayList<>();
        int n = 0;
        for (TestResult r : run.results()) {
            summary.add(List.of(String.valueOf(++n), r.testCase().name(), str(r.testCase().expectedStatus()),
                    r.outgoingResponse() == null ? "-" : str(r.outgoingResponse().status()), verdict(r),
                    r.correlationId()));
        }
        out.add(new Table(List.of("#", "Test case", "Expected", "Actual", "Result", "Correlation ID"), summary));
        out.add(new Paragraph("Incoming request: client to gateway. Outgoing request: gateway to downstream system. "
                + "Incoming response: downstream system to gateway. Outgoing response: gateway to client. "
                + "Values of the masked fields are replaced by the mask in every message, the audit rows and the logs."));

        n = 0;
        for (TestResult r : run.results()) {
            n++;
            out.add(new PageBreak());
            TestCase c = r.testCase();
            out.add(new Heading(1, n + ". " + c.name() + " - " + verdict(r)));
            if (c.description() != null && !c.description().isBlank()) {
                out.add(new Paragraph(c.description()));
            }
            out.add(new Fields(List.of(
                    row("Correlation ID", r.correlationId()),
                    row("Started at", r.startedAt()),
                    row("Expected status", str(c.expectedStatus())),
                    row("Actual status", r.outgoingResponse() == null ? "-" : str(r.outgoingResponse().status())),
                    row("Duration", r.durationMs() + " ms"),
                    row("Downstream calls", String.valueOf(r.downstream().size())))));
            if (r.error() != null) {
                out.add(new Paragraph("Error: " + r.error()));
            }

            out.add(new Heading(2, n + ".1 Incoming request (client to gateway)"));
            out.add(new Code(http(r.incomingRequest())));

            out.add(new Heading(2, n + ".2 Outgoing request (gateway to downstream)"));
            if (r.downstream().isEmpty()) {
                out.add(new Paragraph("No downstream call was made."));
            }
            int k = 0;
            for (DownstreamExchange d : r.downstream()) {
                out.add(new Heading(3, "Call " + (++k) + " - " + d.targetSystem()));
                out.add(new Code(http(d.request())));
            }

            out.add(new Heading(2, n + ".3 Incoming response (downstream to gateway)"));
            if (r.downstream().isEmpty()) {
                out.add(new Paragraph("No downstream call was made."));
            }
            k = 0;
            for (DownstreamExchange d : r.downstream()) {
                out.add(new Heading(3, "Call " + (++k) + " - " + d.targetSystem() + " - " + d.request().method() + " "
                        + d.request().url() + " (" + d.durationMs() + " ms)"));
                out.add(d.response() == null ? new Paragraph("No response: " + d.error()) : new Code(http(d.response())));
            }

            out.add(new Heading(2, n + ".4 Outgoing response (gateway to client)"));
            out.add(r.outgoingResponse() == null ? new Paragraph("No response.") : new Code(http(r.outgoingResponse())));

            if (options.audit()) {
                out.add(new Heading(2, n + ".5 Audit trail"));
                audit(r.audit(), out);
            }
            if (options.logs()) {
                out.add(new Heading(2, n + (options.audit() ? ".6" : ".5") + " Logs"));
                out.add(r.logs().isEmpty() ? new Paragraph("No log line carried this correlation ID.")
                        : new Code(String.join("\n", r.logs())));
            }
        }
        return out;
    }

    private static void audit(AuditTrail a, List<Block> out) {
        if (a == null) {
            out.add(new Paragraph("Not available."));
            return;
        }
        if (a.note() != null) {
            out.add(new Paragraph(a.note()));
        }
        if (!a.enabled() || a.transaction() == null) {
            return;
        }
        out.add(new Heading(3, "Transaction (gw_audit_transaction)"));
        List<String[]> rows = new ArrayList<>();
        a.transaction().forEach((k, v) -> {
            if (!PAYLOAD_COLUMNS.contains(k)) {
                rows.add(row(k, str(v)));
            }
        });
        out.add(new Fields(rows));
        payloads(a.transaction(), out);
        if (!a.steps().isEmpty()) {
            out.add(new Heading(3, "Steps (gw_audit_step)"));
            List<List<String>> table = new ArrayList<>();
            for (Map<String, Object> s : a.steps()) {
                table.add(List.of(str(s.get("step_name")), str(s.get("target_system")), str(s.get("http_method")),
                        str(s.get("url")), str(s.get("http_status")), str(s.get("outcome")), str(s.get("duration_ms"))));
            }
            out.add(new Table(List.of("Step", "Target", "Method", "URL", "Status", "Outcome", "ms"), table));
            for (Map<String, Object> s : a.steps()) {
                if (s.get("request_payload") != null || s.get("response_payload") != null) {
                    out.add(new Paragraph("Step " + str(s.get("step_name")) + " payloads as audited:"));
                    payloads(s, out);
                }
            }
        }
    }

    private static void payloads(Map<String, Object> row, List<Block> out) {
        for (String col : List.of("request_payload", "response_payload")) {
            Object v = row.get(col);
            if (v != null) {
                out.add(new Paragraph(col + ":"));
                out.add(new Code(pretty(v.toString())));
            }
        }
    }

    /** The message as an HTTP exchange: request or status line, headers, blank line, body. */
    static String http(Message m) {
        if (m == null) {
            return "(none)";
        }
        StringBuilder b = new StringBuilder();
        b.append(m.method() != null ? m.method() + " " + m.url() : "HTTP " + m.status()).append('\n');
        m.headers().forEach((k, v) -> b.append(k).append(": ").append(v).append('\n'));
        if (m.body() != null && !m.body().isEmpty()) {
            b.append('\n').append(m.body());
        }
        return b.toString().stripTrailing();
    }

    private static String pretty(String text) {
        try {
            return JsonValues.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(JsonValues.MAPPER.readTree(text));
        } catch (RuntimeException e) {
            return text;
        }
    }

    static String verdict(TestResult r) {
        return r.passed() == null ? "RECORDED" : r.passed() ? "PASS" : "FAIL";
    }

    private static String[] row(String label, String value) {
        return new String[] {label, value};
    }

    private static String str(Object o) {
        return o == null ? "-" : o.toString();
    }
}
