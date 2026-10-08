package com.mhamzah.gateway.extension.custom;

import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.extension.GatewayResponse;
import com.mhamzah.gateway.extension.MessageHandler;
import com.mhamzah.gateway.extension.MessageView;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Example {@link MessageHandler} at the <b>step request</b> hook: signs the outgoing call, as many partner APIs require.
 * <pre>
 *   X-Timestamp: 2026-10-08T09:00:00Z
 *   X-Signature: Base64( HMAC-SHA256( secret, timestamp + ":" + body ) )
 * </pre>
 * Use it on a step: {@code gw_flow_step.request_handler = 'requestSigner'}.
 * Secret: {@code custom.request-signer.secret} (set it from an environment variable; never commit real secrets).
 */
@Component("requestSigner")
public class RequestSigner implements MessageHandler {

    private final byte[] secret;
    private final Clock clock;

    @Autowired
    public RequestSigner(@Value("${custom.request-signer.secret:dev-signing-secret}") String secret) {
        this(secret, Clock.systemUTC());
    }

    /** For tests: a fixed clock makes the signature predictable. */
    RequestSigner(String secret, Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    @Override
    public GatewayResponse handle(MessageView message, ExecutionContext ctx) {
        String timestamp = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString();
        String body = message.body() == null ? "" : message.body().toString(); // compact JSON, exactly as sent
        message.headers().put("X-Timestamp", timestamp);
        message.headers().put("X-Signature", hmacSha256Base64(timestamp + ":" + body));
        return null; // only modifies the request; a step request handler cannot short-circuit
    }

    private String hmacSha256Base64(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); // Mac is not thread-safe: one per call
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
