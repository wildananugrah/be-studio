package com.mhamzah.gateway.extension.custom.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.extension.GatewayResponse;
import com.mhamzah.gateway.extension.MessageView;
import com.mhamzah.gateway.mapping.JsonValues;
import java.util.Map;
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

    @Test
    void createRejectsAnInvalidEmailBeforeTouchingTheDatabase() {
        var create = new MsUserHandlers(null, "/api").msUserCreate();
        var body = JsonValues.MAPPER.readTree("{\"username\":\"budi\",\"fullName\":\"Budi\",\"email\":\"not-an-email\"}");

        GatewayResponse r = create.handle(new MessageView(Map.of(), body), new ExecutionContext("F", "corr-1", Map.of(), null));

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("details").toString()).contains("email is not a valid address");
    }
}
