package com.mhamzah.gateway.masking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MaskerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Masker masker = new Masker(List.of("pin", "cardNo", "Authorization"), "****");

    @Test
    void masksMatchingFieldsAtAnyDepthCaseInsensitively() {
        JsonNode in = mapper.readTree("""
                {"PIN":"1234","user":{"cardno":"4111","name":"Budi"},"list":[{"pin":5}]}""");
        JsonNode out = masker.mask(in);
        assertThat(out.toString()).isEqualTo("""
                {"PIN":"****","user":{"cardno":"****","name":"Budi"},"list":[{"pin":"****"}]}""");
    }

    @Test
    void doesNotModifyTheOriginal() {
        JsonNode in = mapper.readTree("{\"pin\":\"1234\"}");
        masker.mask(in);
        assertThat(in.get("pin").stringValue()).isEqualTo("1234");
    }

    @Test
    void masksHeaders() {
        assertThat(masker.maskHeaders(Map.of("authorization", "Bearer x", "x-id", "1")))
                .containsEntry("authorization", "****")
                .containsEntry("x-id", "1");
    }

    @Test
    void masksXmlTextByElementAndAttributeName() {
        String xml = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"urn:s\"><s:Body><q0:auth pin=\"9999\">"
                + "<q0:PIN>1234</q0:PIN><cardNo type=\"visa\">4111</cardNo><name>Budi</name></q0:auth></s:Body></s:Envelope>";
        assertThat(masker.mask(tools.jackson.databind.node.StringNode.valueOf(xml)).asString()).isEqualTo(
                "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"urn:s\"><s:Body><q0:auth pin=\"****\">"
                + "<q0:PIN>****</q0:PIN><cardNo type=\"visa\">****</cardNo><name>Budi</name></q0:auth></s:Body></s:Envelope>");
    }

    @Test
    void masksJsonTextAndLeavesOtherTextAlone() {
        assertThat(masker.maskText("{\"pin\":\"1234\",\"a\":1}")).isEqualTo("{\"pin\":\"****\",\"a\":1}");
        assertThat(masker.maskText("pin=1234")).isEqualTo("pin=1234");
    }

    @Test
    void nullIsSafe() {
        assertThat(masker.mask(null)).isNull();
    }
}
