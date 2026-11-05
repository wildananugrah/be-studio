package com.mhamzah.gateway.studio.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiSpecTest {

    private final RouterFixture fixture = new RouterFixture();

    /** The API specification (markdown) of one flow. */
    private String spec(String code) {
        return ReportRenderers.markdown(ApiSpec.build(fixture.doc,
                ApiSpec.endpoints(fixture.doc, List.of(fixture.flow(code)), Map.of()),
                "Spec", "http://localhost:8080", ZonedDateTime.parse("2026-11-05T10:00:00Z")));
    }

    @Test
    void routerRequestShowsEveryOption() {
        fixture.build();

        assertThat(spec("PAYMENT")).contains("Body option 1 of 2: TRANSFER_REQUEST", "Body option 2 of 2: TOPUP_REQUEST",
                "| fromAccount |", "| phoneNo |", "Example request (TRANSFER_REQUEST):", "Example request (TOPUP_REQUEST):",
                "\"phoneNo\"");
        assertThat(spec("TRANSFER")).doesNotContain("Body option").contains("Example request:", "| fromAccount |");
    }

    @Test
    void responseOneOfShowsOptionsToo() {
        fixture.rows.flow("STATUS", "GET", "/status", f -> f.withSchemas(null, "PAYMENT_REQUEST"));
        fixture.build();

        String md = spec("STATUS");
        String response = md.substring(md.indexOf("Response"));
        assertThat(response).contains("Body option 1 of 2: TRANSFER_REQUEST", "Body option 2 of 2: TOPUP_REQUEST",
                "Example response (TRANSFER_REQUEST):", "Example response (TOPUP_REQUEST):");
    }

    @Test
    void namedResponsesStillListTheirShapesUnderOtherResponses() {
        var flow = fixture.rows.flow("F", "POST", "/f");
        fixture.rows.step(flow, "s", 1, s -> s.withNext("RESPOND:accepted", "RESPOND:refused"));
        fixture.rows.responseRule(flow, "$.id", "$.steps.s.body.id");
        fixture.rows.response(flow, "refused", 422);
        fixture.rows.responseRule(flow, "refused", "$.reason", "$.steps.s.body.message", null);
        fixture.rows.response(flow, "accepted", 200);
        fixture.rows.responseRule(flow, "accepted", "$.queued", null, "true");
        fixture.build();

        String md = spec("F");
        assertThat(md).doesNotContain("Body option").contains("Other responses", "response 'accepted'", "| queued |",
                "| reason |", "| id |");
    }
}
