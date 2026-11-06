package com.mhamzah.gateway.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class FileCodecTest {

    /** The regex isJson replaced; it must accept exactly the same types. */
    private static boolean oldRegex(String contentType) {
        return contentType.toLowerCase(Locale.ROOT).matches("application/([a-z0-9.+-]*\\+)?json.*");
    }

    @Test
    void isJsonAcceptsTheSameTypesAsTheOldRegex() {
        String[] types = {"application/json", "application/json; charset=UTF-8", "APPLICATION/JSON",
                "application/problem+json", "application/vnd.api+json;v=2", "application/a+b+json", "application/jsonl",
                "application/x-json", "application/+json", "application/vnd.json", "application/xml", "text/json",
                "application/", "application", "", "application/pdf", "application/vnd+jso", "application/ json",
                "application/vnd_x+json", "application/v.n-d+json"};
        for (String type : types) {
            assertThat(FileCodec.isJson(type)).as(type).isEqualTo(oldRegex(type));
            assertThat(FileCodec.isFile(type)).as(type).isEqualTo(type.isBlank() || !oldRegex(type));
        }
        assertThat(FileCodec.isJson(null)).isFalse();
        assertThat(FileCodec.isFile(null)).isTrue();
    }

    @Test
    void isJsonIsLinearOnALongSubtype() {
        String hostile = "application/" + "+".repeat(200_000) + "x";
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThat(FileCodec.isJson(hostile)).isFalse());
    }
}
