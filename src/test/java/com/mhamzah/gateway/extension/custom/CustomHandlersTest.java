package com.mhamzah.gateway.extension.custom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mhamzah.gateway.extension.ErrorType;
import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.extension.GatewayError;
import com.mhamzah.gateway.extension.GatewayResponse;
import com.mhamzah.gateway.extension.MessageView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Unit tests for the example custom classes: no Spring, no database, no HTTP. */
class CustomHandlersTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** A context like the one the engine builds, with the given inbound headers. */
    private static ExecutionContext ctx(Map<String, String> headers) {
        ObjectNode request = F.objectNode();
        ObjectNode h = request.putObject("headers");
        headers.forEach(h::put);
        request.putObject("body");
        return new ExecutionContext("DEMO_FLOW", "corr-42", Map.of(), request);
    }

    @Nested
    class IdrAmountFormatterTest {

        private final IdrAmountFormatter formatter = new IdrAmountFormatter();

        @Test
        void formatsNumbersAndNumericTextAsRupiah() {
            assertThat(formatter.handle(F.numberNode(new java.math.BigDecimal("1500000.00")), ctx(Map.of())).stringValue())
                    .isEqualTo("Rp1.500.000,00");
            assertThat(formatter.handle(F.stringNode("25000.5"), ctx(Map.of())).stringValue())
                    .isEqualTo("Rp25.000,50");
            assertThat(formatter.handle(F.numberNode(-750), ctx(Map.of())).stringValue())
                    .isEqualTo("-Rp750,00");
        }

        @Test
        void missingStaysMissing() {
            assertThat(formatter.handle(null, ctx(Map.of()))).isNull();
            assertThat(formatter.handle(F.nullNode(), ctx(Map.of()))).isNull();
        }

        @Test
        void rejectsNonNumericValues() {
            assertThatThrownBy(() -> formatter.handle(F.stringNode("abc"), ctx(Map.of())))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("abc");
        }
    }

    @Nested
    class ChannelGuardTest {

        private final ChannelGuard guard = new ChannelGuard(List.of("MOBILE", "ATM"));

        @Test
        void allowedChannelContinues() {
            MessageView message = new MessageView(Map.of("x-channel", "mobile"), F.objectNode());
            assertThat(guard.handle(message, ctx(Map.of("x-channel", "mobile")))).isNull();
        }

        @Test
        void unknownChannelIsRejectedWith403() {
            MessageView message = new MessageView(Map.of("x-channel", "TELLER"), F.objectNode());
            GatewayResponse r = guard.handle(message, ctx(Map.of()));
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.body().get("errorCode").stringValue()).isEqualTo("CHANNEL_NOT_ALLOWED");
            assertThat(r.body().get("correlationId").stringValue()).isEqualTo("corr-42");
        }

        @Test
        void missingChannelIsRejectedToo() {
            GatewayResponse r = guard.handle(new MessageView(Map.of(), F.objectNode()), ctx(Map.of()));
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.body().get("errorMessage").stringValue()).contains("X-Channel");
        }
    }

    @Nested
    class RequestSignerTest {

        private final Clock clock = Clock.fixed(Instant.parse("2026-10-08T09:00:00Z"), ZoneOffset.UTC);
        private final RequestSigner signer = new RequestSigner("test-secret", clock);

        @Test
        void addsTimestampAndHmacSignatureHeaders() {
            MessageView message = new MessageView(Map.of(), MAPPER.readTree("{\"acc\":\"1001\"}"));

            assertThat(signer.handle(message, ctx(Map.of()))).isNull();

            assertThat(message.headers()).containsEntry("X-Timestamp", "2026-10-08T09:00:00Z");
            // Expected value computed independently:
            // printf '%s' '2026-10-08T09:00:00Z:{"acc":"1001"}' | openssl dgst -sha256 -hmac test-secret -binary | base64
            assertThat(message.headers()).containsEntry("X-Signature", "5u6/b/xf5uVAcMrQILT/3gaIh11Y2ZZhczn/8i0dUOU=");
        }

        @Test
        void requestWithoutBodySignsTimestampOnly() {
            MessageView message = new MessageView(Map.of(), null);
            signer.handle(message, ctx(Map.of()));
            // printf '%s' '2026-10-08T09:00:00Z:' | openssl dgst -sha256 -hmac test-secret -binary | base64
            assertThat(message.headers()).containsEntry("X-Signature", "8pixXog4FwfzhvVGp/LwQjE8WrxqDfTyZhNTw7pYhQM=");
        }

        @Test
        void differentSecretsGiveDifferentSignatures() {
            MessageView a = new MessageView(Map.of(), F.objectNode());
            MessageView b = new MessageView(Map.of(), F.objectNode());
            signer.handle(a, ctx(Map.of()));
            new RequestSigner("other-secret", clock).handle(b, ctx(Map.of()));
            assertThat(a.headers().get("X-Signature")).isNotEqualTo(b.headers().get("X-Signature"));
        }
    }

    @Nested
    class PartnerErrorHandlerTest {

        private final PartnerErrorHandler handler = new PartnerErrorHandler();

        private JsonNode body(GatewayError error) {
            GatewayResponse r = handler.handle(error, ctx(Map.of()));
            assertThat(r.status()).as("partner always gets HTTP 200").isEqualTo(200);
            return r.body();
        }

        @Test
        void invalidRequestIsFormatError30() {
            JsonNode b = body(GatewayError.of(ErrorType.REQUEST_SCHEMA_INVALID).details(List.of("$.amount: required")).build());
            assertThat(b.get("responseCode").stringValue()).isEqualTo("30");
            assertThat(b.get("responseMessage").stringValue()).isEqualTo("Format error");
            assertThat(b.get("details").get(0).stringValue()).isEqualTo("$.amount: required");
            assertThat(b.get("correlationId").stringValue()).isEqualTo("corr-42");
        }

        @Test
        void timeoutsAre68() {
            assertThat(body(GatewayError.of(ErrorType.DOWNSTREAM_TIMEOUT).build()).get("responseCode").stringValue())
                    .isEqualTo("68");
            assertThat(body(GatewayError.of(ErrorType.FLOW_TIMEOUT).build()).get("responseCode").stringValue())
                    .isEqualTo("68");
        }

        @Test
        void businessErrorPassesTheDownstreamCodeThrough() {
            GatewayError error = GatewayError.of(ErrorType.DOWNSTREAM_BUSINESS_ERROR).step("debit")
                    .downstream(200, Map.of(), MAPPER.readTree("{\"responseCode\":\"51\",\"responseMessage\":\"Insufficient funds\"}"))
                    .build();
            JsonNode b = body(error);
            assertThat(b.get("responseCode").stringValue()).isEqualTo("51");
            assertThat(b.get("responseMessage").stringValue()).isEqualTo("Insufficient funds");
        }

        @Test
        void downstreamHttpErrorWithCodeAlsoPassesThrough() {
            GatewayError error = GatewayError.of(ErrorType.DOWNSTREAM_HTTP_ERROR)
                    .downstream(404, Map.of(), MAPPER.readTree("{\"responseCode\":\"14\",\"responseMessage\":\"Account not found\"}"))
                    .build();
            assertThat(body(error).get("responseCode").stringValue()).isEqualTo("14");
        }

        @Test
        void anythingElseIsSystemMalfunction96() {
            assertThat(body(GatewayError.of(ErrorType.DOWNSTREAM_CONNECTION).build()).get("responseCode").stringValue())
                    .isEqualTo("96");
            assertThat(body(GatewayError.of(ErrorType.DOWNSTREAM_HTTP_ERROR).downstream(500, Map.of(), F.stringNode("boom"))
                    .build()).get("responseCode").stringValue()).isEqualTo("96");
        }
    }

    @Nested
    class PartnerSoapCodecTest {

        private final PartnerSoapCodec codec = new PartnerSoapCodec("gw-user", "p<ss");

        @Test
        void addsWsSecurityUsernameTokenHeader() {
            String xml = codec.encode(MAPPER.readTree("{\"Ping\":null}"), new java.util.TreeMap<>(), ctx(Map.of()));

            assertThat(xml).contains("<soapenv:Header><wsse:Security xmlns:wsse=\"" + PartnerSoapCodec.WSSE + "\">"
                    + "<wsse:UsernameToken><wsse:Username>gw-user</wsse:Username>"
                    + "<wsse:Password Type=\"" + PartnerSoapCodec.PASSWORD_TEXT + "\">p&lt;ss</wsse:Password>"
                    + "</wsse:UsernameToken></wsse:Security></soapenv:Header>")
                    .contains("<soapenv:Body><Ping/></soapenv:Body>");
        }

        @Test
        void decodesLikeTheBuiltInSoapCodec() {
            JsonNode j = codec.decode("<e:Envelope xmlns:e=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                    + "<e:Body><Pong>ok</Pong></e:Body></e:Envelope>", Map.of());

            assertThat(j.toString()).isEqualTo("{\"Pong\":\"ok\"}");
        }
    }
}
