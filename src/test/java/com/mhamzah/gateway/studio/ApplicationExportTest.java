package com.mhamzah.gateway.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.studio.StudioConfig.Flow;
import com.mhamzah.gateway.studio.StudioConfig.Schema;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApplicationExportTest {

    private static Flow flow(String code, String requestSchema, String responseSchema) {
        return new Flow(code, code, "POST", "/" + code.toLowerCase(), requestSchema, responseSchema, null, null, null, 200,
                null, "INHERIT", true, List.of(), List.of(), "PAY");
    }

    @Test
    void schemasReferencedThroughGwCodesAreExported() {
        StudioConfig config = new StudioConfig(null,
                List.of(flow("PAYMENT", "PAYMENT_REQUEST", null), flow("TREE", null, "LOOP")), List.of(), List.of(),
                List.of(new Schema("PAYMENT_REQUEST", null, "{\"oneOf\":[{\"$ref\":\"gw:TRANSFER_REQUEST\"},{\"$ref\":\"gw:TOPUP_REQUEST\"}]}"),
                        new Schema("TRANSFER_REQUEST", null, "{\"$ref\":\"gw:SHARED#/$defs/acct\"}"),
                        new Schema("TOPUP_REQUEST", null, "{\"type\":\"object\"}"),
                        new Schema("SHARED", null, "{\"$defs\":{\"acct\":{\"type\":\"object\"}}}"),
                        new Schema("UNRELATED", null, "{\"type\":\"string\"}"),
                        new Schema("LOOP", null, "{\"$ref\":\"gw:LOOP\"}")),
                List.of(), List.of(new StudioConfig.Application("PAY", "Payments", null)));

        ApplicationExport.Bundle bundle = ApplicationExport.bundle(config, "PAY");

        assertThat(bundle.schemas()).extracting(Schema::code)
                .containsExactlyInAnyOrder("PAYMENT_REQUEST", "TRANSFER_REQUEST", "TOPUP_REQUEST", "SHARED", "LOOP");
        assertThat(bundle.requires()).doesNotContainKey("schemasNotFound");
    }

    @Test
    void secretHeaderValuesAreReplacedByPlaceholders() {
        Flow flow = new Flow("PAYMENT", "PAYMENT", "POST", "/payment", null, null, null, null, null, 200, null, "INHERIT",
                true, List.of(new StudioConfig.Step("call", 1, "CORE", "POST", "/pay", null, null, null, null, null, null,
                        null, null, true, List.of(), null, null, null, null, null, List.of())), List.of(), "PAY");
        StudioConfig.Target core = new StudioConfig.Target("CORE", "http://core", 1000, 2000, null, true,
                List.of(new StudioConfig.Header("X-Api-Key", "k-123"), new StudioConfig.Header("X-Channel-Id", "WEB")),
                null);
        StudioConfig config = new StudioConfig(null, List.of(flow), List.of(core), List.of(), List.of(), List.of(),
                List.of(new StudioConfig.Application("PAY", "Payments", null)));

        ApplicationExport.Bundle bundle = ApplicationExport.bundle(config, "PAY");

        List<StudioConfig.Header> headers = bundle.targets().getFirst().headers();
        assertThat(headers).extracting(StudioConfig.Header::name).containsExactly("X-Api-Key", "X-Channel-Id");
        assertThat(headers.get(0).value()).isNotEqualTo("k-123");
        assertThat(headers.get(1).value()).isEqualTo("WEB");
        assertThat(bundle.replacedSecrets()).isNotEmpty();
    }

    @Test
    void errorMapsUsedByRulesAreExportedAsSharedItems() {
        StudioConfig.Rule rule = new StudioConfig.Rule("BODY", "$.message", null, null, null, null, null, null, false,
                "BG_ERRORS", List.of(new StudioConfig.Where("errorCode", "$.error.downstream.body.errorCode", null),
                        new StudioConfig.Where("message", "$.error.downstream.body.message", null, "template")),
                "messageEn", true, 2);
        Flow flow = new Flow("PAYMENT", "PAYMENT", "POST", "/payment", null, null, null, null, null, 200, null, "INHERIT",
                true, List.of(), List.of(rule), "PAY");
        StudioConfig config = new StudioConfig(null, List.of(flow), List.of(), List.of(), List.of(), List.of(),
                List.of(new StudioConfig.Application("PAY", "Payments", null)),
                List.of(new StudioConfig.ErrorMap("BG_ERRORS", "errors", "[{\"errorCode\":\"X\",\"generalMessage\":\"Y\"}]"),
                        new StudioConfig.ErrorMap("UNUSED", null, "[]")));

        ApplicationExport.Bundle bundle = ApplicationExport.bundle(config, "PAY");

        assertThat(bundle.errorMaps()).extracting(StudioConfig.ErrorMap::code).containsExactly("BG_ERRORS");
        assertThat(ApplicationExport.json(bundle, "test")).contains("\"errorMaps\"", "BG_ERRORS").doesNotContain("UNUSED");
        String sql = ApplicationExport.sql(bundle, ApplicationExport.Dialect.POSTGRES, TABLES, "test");
        assertThat(sql).contains("INSERT INTO gw_error_map", "error_map_code", "'messageEn'", "error_map_fill", "error_map_index",
                "[{\"field\":\"errorCode\",\"source\":\"$.error.downstream.body.errorCode\"},"
                        + "{\"field\":\"message\",\"source\":\"$.error.downstream.body.message\",\"match\":\"template\"}]");
    }

    private static final GatewayProperties.Tables TABLES = new GatewayProperties.Tables("gw_flow", "gw_flow_step",
            "gw_mapping_rule", "gw_lookup_entry", "gw_json_schema", "gw_audit_transaction", "gw_audit_step",
            "gw_target_system", "gw_target_system_header", "gw_storage", "gw_application", "gw_flow_response");

    private static StudioConfig twoFlowsOfPay() {
        return new StudioConfig(null,
                List.of(flow("PAYMENT", "PAYMENT_REQUEST", null), flow("REFUND", "REFUND_REQUEST", null)), List.of(),
                List.of(), List.of(new Schema("PAYMENT_REQUEST", null, "{\"type\":\"object\"}"),
                        new Schema("REFUND_REQUEST", null, "{\"type\":\"object\"}")),
                List.of(), List.of(new StudioConfig.Application("PAY", "Payments", null)));
    }

    @Test
    void flowsExportCarriesOnlyTheChosenFlowsAndWhatTheyUse() {
        ApplicationExport.Bundle bundle = ApplicationExport.flowsBundle(twoFlowsOfPay(), List.of("REFUND"));

        assertThat(bundle.flowsOnly()).isTrue();
        assertThat(bundle.flows()).extracting(Flow::code).containsExactly("REFUND");
        assertThat(bundle.schemas()).extracting(Schema::code).containsExactly("REFUND_REQUEST");
        assertThat(bundle.applications()).extracting(StudioConfig.Application::code).containsExactly("PAY");
        assertThat(ApplicationExport.json(bundle, "test")).contains("\"format\" : \"json-gateway-flows\"", "\"applications\"");
    }

    @Test
    void flowsSqlReplacesOnlyThoseFlowsAndAddsTheApplicationWhenMissing() {
        ApplicationExport.Bundle bundle = ApplicationExport.flowsBundle(twoFlowsOfPay(), List.of("REFUND"));

        for (ApplicationExport.Dialect dialect : ApplicationExport.Dialect.values()) {
            String sql = ApplicationExport.sql(bundle, dialect, TABLES, "test");
            assertThat(sql).contains("DELETE FROM gw_flow WHERE code IN ('REFUND');",
                    "SELECT COUNT(*) FROM gw_application WHERE code = 'PAY') = 0;", "'REFUND'")
                    .doesNotContain("application_code = 'PAY'", "DELETE FROM gw_application", "'PAYMENT'");
            assertThat(sql.contains("FROM DUAL WHERE")).isEqualTo(dialect == ApplicationExport.Dialect.ORACLE);
        }
    }

    @Test
    void flowsLiquibaseReplacesOnlyThoseFlows() {
        String xml = ApplicationExport.liquibase(ApplicationExport.flowsBundle(twoFlowsOfPay(), List.of("REFUND")), "test");

        assertThat(xml).contains("id=\"flows-refund-", "<where>code IN ('REFUND')</where>",
                "SELECT COUNT(*) FROM ${tbl.application} WHERE code = 'PAY'")
                .doesNotContain("application_code = 'PAY'", "<delete tableName=\"${tbl.application}\">");
    }

    @Test
    void flowsExportRejectsNoOrUnknownFlows() {
        assertThatThrownBy(() -> ApplicationExport.flowsBundle(twoFlowsOfPay(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApplicationExport.flowsBundle(twoFlowsOfPay(), List.of("REFUND", "NOPE")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'NOPE'");
    }
}
