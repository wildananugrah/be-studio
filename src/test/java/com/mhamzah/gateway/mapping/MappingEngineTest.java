package com.mhamzah.gateway.mapping;

import static com.mhamzah.gateway.mapping.RuleBuilder.body;
import static com.mhamzah.gateway.mapping.RuleBuilder.to;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mhamzah.gateway.extension.ErrorType;
import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.extension.GatewayError;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class MappingEngineTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final MappingEngine engine = new MappingEngine();
    private ExecutionContext ctx;

    private JsonNode json(String s) {
        return mapper.readTree(s.replace('\'', '"'));
    }

    @BeforeEach
    void setUp() {
        ObjectNode request = (ObjectNode) json("""
                {'headers':{'x-channel':'MOBILE'},
                 'path':{'accountNo':'123'},
                 'query':{'lang':'id'},
                 'body':{'customer':{'name':'Budi','dob':'1990-01-31'},
                         'amount':12500,
                         'status':'A',
                         'items':[{'price':10},{'price':20}],
                         'empty':[]}}""");
        ctx = new ExecutionContext("F1", "corr-1", Map.of(), request);
        ctx.putStepResult("inquiry", (ObjectNode) json("{'outcome':'SUCCESS','status':200,'body':{'name':'BUDI S','bal':1}}"));
    }

    private MappedMessage map(CompiledRule... rules) {
        return engine.apply(List.of(rules), ctx);
    }

    @Test
    void movesNestedFieldToFlatTarget() {
        var out = map(body("$.nama_nasabah").from("$.request.body.customer.name").build());
        assertThat(out.body()).isEqualTo(json("{'nama_nasabah':'Budi'}"));
    }

    @Test
    void flatSourceToNestedTarget() {
        var out = map(body("$.data.account.no").from("$.request.path.accountNo").build());
        assertThat(out.body()).isEqualTo(json("{'data':{'account':{'no':'123'}}}"));
    }

    @Test
    void readsFromEarlierStepResults() {
        var out = map(body("$.name").from("$.steps.inquiry.body.name").build());
        assertThat(out.body().get("name").stringValue()).isEqualTo("BUDI S");
    }

    @Test
    void constantIsParsedAsJsonLiteral() {
        var out = map(body("$.channel").constant("MOBILE").build(), body("$.version").constant("2").build());
        assertThat(out.body()).isEqualTo(json("{'channel':'MOBILE','version':2}"));
    }

    @Test
    void defaultUsedWhenSourceMissing() {
        var out = map(body("$.note").from("$.request.body.nope").defaultValue("n/a").build());
        assertThat(out.body().get("note").stringValue()).isEqualTo("n/a");
    }

    @Test
    void missingOptionalSourceWritesNothing() {
        var out = map(body("$.note").from("$.request.body.nope").build());
        assertThat(out.body().has("note")).isFalse();
    }

    @Test
    void headerQueryAndPathTargetsAreStrings() {
        var out = map(
                to(TargetType.HEADER, "X-Channel").from("$.request.headers.x-channel").build(),
                to(TargetType.QUERY, "amount").from("$.request.body.amount").build(),
                to(TargetType.PATH, "acc").from("$.request.path.accountNo").build());
        assertThat(out.headers()).containsEntry("X-Channel", "MOBILE");
        assertThat(out.query()).containsEntry("amount", "12500");
        assertThat(out.pathVariables()).containsEntry("acc", "123");
    }

    @Test
    void appliesDefaultThenLookupThenConverterThenHandler() {
        var out = map(body("$.st")
                .from("$.request.body.status")
                .lookup(Map.of("A", "active"))
                .converter("UPPER")
                .handler((v, c) -> JsonNodeFactory.instance.stringNode(v.stringValue() + "!"))
                .build());
        assertThat(out.body().get("st").stringValue()).isEqualTo("ACTIVE!");
    }

    @Test
    void lookupFallbackRow() {
        var out = map(body("$.st").from("$.request.body.status").lookup(Map.of("X", "x", "*", "other")).build());
        assertThat(out.body().get("st").stringValue()).isEqualTo("other");
    }

    @Test
    void converterFormatsAmountAndDate() {
        var out = map(
                body("$.amt").from("$.request.body.amount").converter("PAD_LEFT:12:0").build(),
                body("$.dob").from("$.request.body.customer.dob").converter("DATE_FORMAT:yyyy-MM-dd:ddMMyyyy").build());
        assertThat(out.body()).isEqualTo(json("{'amt':'000000012500','dob':'31011990'}"));
    }

    @Test
    void arraysMapPositionally() {
        var out = map(body("$.detail[*].harga").from("$.request.body.items[*].price").converter("TO_STRING").build());
        assertThat(out.body()).isEqualTo(json("{'detail':[{'harga':'10'},{'harga':'20'}]}"));
    }

    @Test
    void wildcardSourceToPlainTargetCollectsArray() {
        var out = map(body("$.prices").from("$.request.body.items[*].price").build());
        assertThat(out.body()).isEqualTo(json("{'prices':[10,20]}"));
    }

    @Test
    void emptySourceArrayProducesEmptyTargetArray() {
        var out = map(
                body("$.a[*].x").from("$.request.body.empty[*].x").build(),
                body("$.b").from("$.request.body.empty[*].x").build());
        assertThat(out.body()).isEqualTo(json("{'a':[],'b':[]}"));
    }

    @Test
    void passthroughCopiesWholeObjectThenLaterRulesOverride() {
        var out = map(
                body("$").from("$.steps.inquiry.body").build(),
                body("$.bal").constant("99").build());
        assertThat(out.body()).isEqualTo(json("{'name':'BUDI S','bal':99}"));
    }

    @Test
    void requiredMissingFromRequestIsClientMappingError() {
        assertThatThrownBy(() -> map(body("$.x").from("$.request.body.nope").required().build()))
                .isInstanceOfSatisfying(GatewayError.class, e -> {
                    assertThat(e.type()).isEqualTo(ErrorType.MAPPING_ERROR);
                    assertThat(e.clientError()).isTrue();
                    assertThat(e.details()).anySatisfy(d -> assertThat(d).contains("$.request.body.nope"));
                });
    }

    @Test
    void requiredMissingFromStepIsServerMappingError() {
        assertThatThrownBy(() -> map(body("$.x").from("$.steps.inquiry.body.nope").required().build()))
                .isInstanceOfSatisfying(GatewayError.class, e -> assertThat(e.clientError()).isFalse());
    }

    @Test
    void converterFailureIsMappingError() {
        assertThatThrownBy(() -> map(body("$.x").from("$.request.body.customer.name").converter("TO_NUMBER").build()))
                .isInstanceOfSatisfying(GatewayError.class, e -> {
                    assertThat(e.type()).isEqualTo(ErrorType.MAPPING_ERROR);
                    assertThat(e.clientError()).isTrue();
                });
    }

    @Test
    void handlerExceptionIsHandlerError() {
        assertThatThrownBy(() -> map(body("$.x").constant("1").handler((v, c) -> {
                    throw new IllegalStateException("boom");
                }).build()))
                .isInstanceOfSatisfying(GatewayError.class, e -> assertThat(e.type()).isEqualTo(ErrorType.HANDLER_ERROR));
    }

    @Test
    void handlerOnlyRuleProducesValueFromNothing() {
        var out = map(body("$.ts").handler((v, c) -> JsonNodeFactory.instance.stringNode(c.correlationId())).build());
        assertThat(out.body().get("ts").stringValue()).isEqualTo("corr-1");
    }
}
