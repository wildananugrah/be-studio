package com.mhamzah.gateway.studio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link ApplicationExport#isSecretHeader} gives the same answers as the regex it replaced. */
class SecretHeaderTest {

    @Test
    void matchesTheOldRegex() {
        String[] names = {"Authorization", "X-Auth-Token", "x-api-key", "X-API_KEY", "ApiKey", "api.key", "X-Secret",
                "Password", "X-Signature", "Content-Type", "X-Channel-Id", "", "apikeys", "API-Key", "Oauth"};
        for (String name : names) {
            assertThat(ApplicationExport.isSecretHeader(name)).as(name)
                    .isEqualTo(name.matches("(?i).*(auth|token|secret|password|api[-_]?key|signature).*"));
        }
        assertThat(ApplicationExport.isSecretHeader(null)).isFalse();
    }
}
