package com.mhamzah.gateway.studio;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.config.ConfigRows;
import com.mhamzah.gateway.studio.StudioConfig.Entry;
import com.mhamzah.gateway.studio.StudioConfig.Flow;
import com.mhamzah.gateway.studio.StudioConfig.Header;
import com.mhamzah.gateway.studio.StudioConfig.Lookup;
import com.mhamzah.gateway.studio.StudioConfig.Rule;
import com.mhamzah.gateway.studio.StudioConfig.Schema;
import com.mhamzah.gateway.studio.StudioConfig.Step;
import com.mhamzah.gateway.studio.StudioConfig.Target;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StudioRowsTest {

    private static Rule rule(String target, String source) {
        return new Rule("BODY", target, source, null, null, null, null, null, false);
    }

    private static StudioConfig config(List<Lookup> lookups, List<Header> headers) {
        Step inquiry = new Step("inquiry", 1, "CORE", "GET", "/accounts/{acc}", null, "${steps.inquiry.status} == 200",
                "STOP", 3000, null, null, null, null, true,
                List.of(new Rule("PATH", "acc", "$.request.path.accountNo", null, null, null, null, null, true)));
        Step notify = new Step("notify", 2, "CORE", "POST", "/notify", null, null, "CONTINUE", null, null, null, null,
                "xmlCodec", false, List.of(new Rule("BODY", "$.template", null, "OK", null, null, null, null, false)));
        Flow flow = new Flow("ACCOUNT", "Account", "GET", "/v1/accounts/{accountNo}", null, null, null, null,
                "coreBankingErrorHandler", 200, null, "INHERIT", true, List.of(inquiry, notify),
                List.of(rule("$.name", "$.steps.inquiry.body.acctName"),
                        new Rule("BODY", "$.status", "$.steps.inquiry.body.statusCd", null, "A", "ACCOUNT_STATUS",
                                "UPPER", null, false)));
        Target core = new Target("CORE", "${CORE_URL:http://localhost:8089}", 2000, null, null, true, headers);
        return new StudioConfig(null, List.of(flow), List.of(core), lookups,
                List.of(new Schema("ACCOUNT", "Account response", "{\"type\":\"object\"}")));
    }

    @Test
    void roundTripsThroughRows() {
        StudioConfig config = config(
                List.of(new Lookup("ACCOUNT_STATUS", List.of(new Entry("A", "ACTIVE"), new Entry("*", "UNKNOWN")))),
                List.of(new Header("X-Channel-Id", "GATEWAY")));

        ConfigRows rows = StudioRows.toRows(config);

        assertThat(StudioRows.problems(rows)).isEmpty();
        assertThat(StudioRows.fromRows(rows, Map.of("ACCOUNT", "Account response"))).isEqualTo(config);
        assertThat(rows.rules()).extracting(ConfigRows.RuleRow::phase)
                .containsExactly("STEP_REQUEST", "STEP_REQUEST", "FLOW_RESPONSE", "FLOW_RESPONSE");
    }

    @Test
    void ruleIdsAreTheirPositionSoCompilerMessagesMatchTheStudio() {
        ConfigRows rows = StudioRows.toRows(config(List.of(), List.of()));
        assertThat(rows.rules()).extracting(ConfigRows.RuleRow::id).containsExactly(1L, 1L, 1L, 2L);
    }

    @Test
    void blankTextBecomesNullAndDefaultsAreFilledIn() {
        Flow flow = new Flow("F", " ", "GET", "/f", "", null, null, null, null, null, null, null, true,
                List.of(new Step("s", 1, "CORE", "GET", "/", "", null, null, null, null, null, null, null, true,
                        List.of(new Rule(null, "$.a", "$.request.body.a", "", null, null, null, null, false)))),
                List.of());
        ConfigRows rows = StudioRows.toRows(new StudioConfig(null, List.of(flow), List.of(), List.of(), List.of()));

        assertThat(rows.flows().getFirst().name()).isNull();
        assertThat(rows.flows().getFirst().requestSchemaCode()).isNull();
        assertThat(rows.flows().getFirst().successStatus()).isEqualTo(200);
        assertThat(rows.flows().getFirst().auditMode()).isEqualTo("INHERIT");
        assertThat(rows.steps().getFirst().onFailure()).isEqualTo("STOP");
        assertThat(rows.steps().getFirst().conditionExpr()).isNull();
        assertThat(rows.rules().getFirst().targetType()).isEqualTo("BODY");
        assertThat(rows.rules().getFirst().constantValue()).isNull();
    }

    @Test
    void reportsWhatTheDatabaseWouldReject() {
        StudioConfig config = config(
                List.of(new Lookup("ACCOUNT_STATUS", List.of(new Entry("A", "ACTIVE"), new Entry("A", "OTHER"),
                        new Entry("", "X"), new Entry("B", "")))),
                List.of(new Header("X-Id", "1"), new Header("X-Id", "2")));

        assertThat(StudioRows.problems(StudioRows.toRows(config))).containsExactly(
                "target system 'CORE' header 'X-Id': duplicate header name",
                "lookup 'ACCOUNT_STATUS': duplicate source_value 'A' (unique lookup_code, source_value)",
                "lookup 'ACCOUNT_STATUS': source_value must not be empty",
                "lookup 'ACCOUNT_STATUS' source_value 'B': target_value must not be empty");
    }

    @Test
    void reportsDuplicateAndEmptySchemas() {
        StudioConfig base = config(List.of(), List.of());
        StudioConfig config = new StudioConfig(null, base.flows(), base.targets(), base.lookups(), List.of(
                new Schema("A", null, "{}"), new Schema("A", null, "{}"), new Schema("B", null, " ")));

        assertThat(StudioRows.problems(StudioRows.toRows(config))).containsExactly(
                "json schema 'A': duplicate code", "json schema 'B': schema_text must not be empty");
    }
}
