package com.mhamzah.gateway.studio;

import static org.assertj.core.api.Assertions.assertThat;

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
}
