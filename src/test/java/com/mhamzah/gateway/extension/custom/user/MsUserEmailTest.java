package com.mhamzah.gateway.extension.custom.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link MsUserHandlers#isEmail} gives the same answers as the regex it replaced. */
class MsUserEmailTest {

    @Test
    void matchesTheOldRegex() {
        String[] emails = {"a@b.c", "budi@example.com", "a.b@c.d.e", "a@b..c", "a@.bc", "a@bc.", "a@b.", "a@.b", "@b.c",
                "a@bc", "a b@c.d", "a@b c.d", "a@@b.c", "a@b@c.d", "a@b.c\t", "", "a", "a@", "a@...", "a@x.y.", ".a@b.c",
                "a@b.c.", "a@.", "a@..b"};
        for (String email : emails) {
            assertThat(MsUserHandlers.isEmail(email)).as("[" + email + "]")
                    .isEqualTo(email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"));
        }
    }
}
