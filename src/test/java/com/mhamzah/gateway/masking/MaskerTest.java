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
    void nullIsSafe() {
        assertThat(masker.mask(null)).isNull();
    }
}
