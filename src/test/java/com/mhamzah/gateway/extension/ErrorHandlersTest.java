package com.mhamzah.gateway.extension;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.extension.custom.CoreBankingErrorHandler;
import com.mhamzah.gateway.mapping.JsonValues;
import com.mhamzah.gateway.mapping.LookupTable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ErrorHandlersTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final DefaultErrorHandler defaults = new DefaultErrorHandler();

    private ExecutionContext ctx(Map<String, LookupTable> lookups) {
        return new ExecutionContext("F", "corr-9", lookups, null);
    }

    @Test
    void defaultHandlerUsesTypeStatusAndCode() {
        GatewayError err = GatewayError.of(ErrorType.DOWNSTREAM_TIMEOUT).step("inquiry").build();
        GatewayResponse r = defaults.handle(err, ctx(Map.of()));
        assertThat(r.status()).isEqualTo(504);
        assertThat(r.body().get("errorCode").stringValue()).isEqualTo("GW-504-DOWNSTREAM");
        assertThat(r.body().get("correlationId").stringValue()).isEqualTo("corr-9");
        assertThat(r.body().get("step").stringValue()).isEqualTo("inquiry");
        assertThat(r.body().has("details")).isFalse();
    }

    @Test
    void defaultHandlerIncludesDetailsAndOmitsStepWhenAbsent() {
        GatewayError err = GatewayError.of(ErrorType.REQUEST_SCHEMA_INVALID).details(List.of("$.amount: required")).build();
        GatewayResponse r = defaults.handle(err, ctx(Map.of()));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().has("step")).isFalse();
        assertThat(r.body().get("details").get(0).stringValue()).isEqualTo("$.amount: required");
    }

    @Test
    void clientMappingErrorIs400() {
        GatewayError err = GatewayError.of(ErrorType.MAPPING_ERROR).clientError(true).build();
        GatewayResponse r = defaults.handle(err, ctx(Map.of()));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("errorCode").stringValue()).isEqualTo("GW-400-MAPPING");
    }

    @Test
    void defaultHandlerDoesNotLeakInternalExceptionMessages() {
        GatewayError err = GatewayError.of(ErrorType.INTERNAL).message("NullPointerException at Foo.java:12").build();
        GatewayResponse r = defaults.handle(err, ctx(Map.of()));
        assertThat(r.body().toString()).doesNotContain("Foo.java");
    }

    private Map<String, LookupTable> coreLookups() {
        return Map.of("CORE_BANKING_ERRORS", new LookupTable("CORE_BANKING_ERRORS",
                Map.of("51", JsonValues.parseLiteral(
                        "{\"status\":422,\"errorCode\":\"INSUFFICIENT_FUNDS\",\"errorMessage\":\"Insufficient balance\"}")),
                null));
    }

    @Test
    void lookupHandlerMapsDownstreamCodeFromBody() {
        GatewayError err = GatewayError.of(ErrorType.DOWNSTREAM_BUSINESS_ERROR).step("debit")
                .downstream(200, Map.of(), mapper.readTree("{\"responseCode\":\"51\"}")).build();
        GatewayResponse r = new CoreBankingErrorHandler().handle(err, ctx(coreLookups()));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.body().get("errorCode").stringValue()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(r.body().get("errorMessage").stringValue()).isEqualTo("Insufficient balance");
        assertThat(r.body().get("correlationId").stringValue()).isEqualTo("corr-9");
    }

    @Test
    void lookupHandlerFallsBackToDefaultWhenCodeUnknown() {
        GatewayError err = GatewayError.of(ErrorType.DOWNSTREAM_HTTP_ERROR).step("debit")
                .downstream(500, Map.of(), mapper.readTree("{\"responseCode\":\"99\"}")).build();
        GatewayResponse r = new CoreBankingErrorHandler().handle(err, ctx(coreLookups()));
        assertThat(r.status()).isEqualTo(502);
        assertThat(r.body().get("errorCode").stringValue()).isEqualTo("GW-502-DOWNSTREAM");
    }

    @Test
    void lookupHandlerDelegatesNonDownstreamErrors() {
        GatewayError err = GatewayError.of(ErrorType.REQUEST_SCHEMA_INVALID).build();
        GatewayResponse r = new CoreBankingErrorHandler().handle(err, ctx(coreLookups()));
        assertThat(r.status()).isEqualTo(400);
    }
}
