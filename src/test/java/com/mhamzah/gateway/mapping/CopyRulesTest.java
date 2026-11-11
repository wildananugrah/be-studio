package com.mhamzah.gateway.mapping;

import static com.mhamzah.gateway.mapping.RuleBuilder.body;
import static com.mhamzah.gateway.mapping.RuleBuilder.to;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.extension.GatewayError;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Pass-through (COPY) rules: the copied fields, their place, and the rules that override them. */
class CopyRulesTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final MappingEngine engine = new MappingEngine();
    private ExecutionContext ctx;

    private JsonNode json(String s) {
        return mapper.readTree(s.replace('\'', '"'));
    }

    @BeforeEach
    void setUp() {
        ObjectNode request = (ObjectNode) json("{'headers':{},'body':{'customer':{'name':'Budi','dob':'1990'},'channel':'MB'}}");
        ctx = new ExecutionContext("F1", "corr-1", Map.of(), request);
        ctx.putStepResult("account", (ObjectNode) json("""
                {'outcome':'SUCCESS','status':200,'body':{
                  'acctNo':'123','acctName':'Budi',
                  'detail':{'branch':'JKT01','openDate':'20200101'},
                  'items':[{'id':'A','amt':1,'note':'x'},{'id':'B','amt':2,'note':'y'}]}}"""));
    }

    private static CompiledRule copy(String source) {
        JsonPath from = JsonPath.compile(source);
        return new CompiledRule(1L, 0, TargetType.COPY, CopyRules.target(from), null, from, null, null, null, null,
                null, null, false);
    }

    private JsonNode map(CompiledRule... rules) {
        return engine.apply(CopyRules.prepare(List.of(rules)), ctx).body();
    }

    @Test
    void targetIsThePathInsideTheBody() {
        assertThat(CopyRules.target(JsonPath.compile("$.steps.account.body.detail.openDate")))
                .hasToString("$.detail.openDate");
        assertThat(CopyRules.target(JsonPath.compile("$.request.body.customer"))).hasToString("$.customer");
        assertThat(CopyRules.target(JsonPath.compile("$.steps.account.body.items[*].id"))).hasToString("$.items[*].id");
        // the whole body, or not a body at all
        assertThat(CopyRules.target(JsonPath.compile("$.steps.account.body"))).isNull();
        assertThat(CopyRules.target(JsonPath.compile("$.steps.account.status"))).isNull();
        assertThat(CopyRules.target(JsonPath.compile("$.request.headers.x"))).isNull();
        assertThat(CopyRules.target(JsonPath.compile("$.correlationId"))).isNull();
    }

    @Test
    void copiesSelectedFieldsKeepingTheirPlace() {
        JsonNode out = map(copy("$.steps.account.body.acctName"), copy("$.steps.account.body.detail.openDate"));
        assertThat(out).isEqualTo(json("{'acctName':'Budi','detail':{'openDate':'20200101'}}"));
    }

    @Test
    void renamedFieldJoinsTheCopiedObject() {
        // the example from the design: acctName and detail.openDate pass through, detail.branch becomes detail.cabang
        JsonNode out = map(
                copy("$.steps.account.body.acctName"),
                copy("$.steps.account.body.detail.openDate"),
                copy("$.steps.account.body.detail.branch"),
                body("$.detail.cabang").from("$.steps.account.body.detail.branch").build());
        assertThat(out).isEqualTo(json("{'acctName':'Budi','detail':{'openDate':'20200101','cabang':'JKT01'}}"));
    }

    @Test
    void remappedFieldIsNotCopiedEvenWhenTheRuleComesFirst() {
        List<CompiledRule> rules = CopyRules.prepare(List.of(
                body("$.account.number").from("$.steps.account.body.acctNo").build(),
                copy("$.steps.account.body.acctNo"),
                copy("$.steps.account.body.acctName")));
        assertThat(rules.get(1).remappedBy()).contains("$.account.number");
        assertThat(rules.get(2).remappedBy()).isNull();
        assertThat(engine.apply(rules, ctx).body())
                .isEqualTo(json("{'acctName':'Budi','account':{'number':'123'}}"));
    }

    @Test
    void copiedObjectLeavesOutTheFieldsOtherRulesMap() {
        JsonNode out = map(copy("$.steps.account.body.detail"),
                body("$.detail.cabang").from("$.steps.account.body.detail.branch").build());
        assertThat(out).isEqualTo(json("{'detail':{'openDate':'20200101','cabang':'JKT01'}}"));
    }

    @Test
    void ruleReadingAParentOverridesTheCopy() {
        JsonNode out = map(copy("$.steps.account.body.detail.openDate"),
                body("$.info").from("$.steps.account.body.detail").build());
        assertThat(out).isEqualTo(json("{'info':{'branch':'JKT01','openDate':'20200101'}}"));
    }

    @Test
    void anyTargetTypeCountsAsARemap() {
        JsonNode out = map(copy("$.steps.account.body.acctNo"), copy("$.steps.account.body.acctName"),
                to(TargetType.HEADER, "X-Account").from("$.steps.account.body.acctNo").build());
        assertThat(out).isEqualTo(json("{'acctName':'Budi'}"));
    }

    @Test
    void copiesArraysByPositionAndLeavesOutRemappedElementFields() {
        JsonNode out = map(copy("$.steps.account.body.items"),
                body("$.items[*].amount").from("$.steps.account.body.items[*].amt").converter("TO_STRING").build());
        assertThat(out).isEqualTo(json("{'items':[{'id':'A','note':'x','amount':'1'},{'id':'B','note':'y','amount':'2'}]}"));

        JsonNode ids = map(copy("$.steps.account.body.items[*].id"));
        assertThat(ids).isEqualTo(json("{'items':[{'id':'A'},{'id':'B'}]}"));
    }

    @Test
    void copiesFromTheRequestBody() {
        JsonNode out = map(copy("$.request.body.customer"), copy("$.request.body.channel"),
                body("$.customer.birthDate").from("$.request.body.customer.dob").build());
        assertThat(out).isEqualTo(json("{'customer':{'name':'Budi','birthDate':'1990'},'channel':'MB'}"));
    }

    @Test
    void missingFieldIsSkipped() {
        assertThat(map(copy("$.steps.account.body.nope"), copy("$.steps.account.body.acctName")))
                .isEqualTo(json("{'acctName':'Budi'}"));
    }

    @Test
    void requiredMissingFieldFails() {
        JsonPath from = JsonPath.compile("$.steps.account.body.nope");
        CompiledRule required = new CompiledRule(1L, 0, TargetType.COPY, CopyRules.target(from), null, from, null,
                null, null, null, null, null, true);
        assertThatThrownBy(() -> map(required)).isInstanceOf(GatewayError.class).hasMessageContaining("nope");
    }

    @Test
    void writingIntoACopyDoesNotChangeTheStepResult() {
        map(copy("$.steps.account.body.detail"), body("$.detail.extra").constant("1").build());
        assertThat(ctx.read(JsonPath.compile("$.steps.account.body.detail")))
                .isEqualTo(json("{'branch':'JKT01','openDate':'20200101'}"));
    }

    @Test
    void listsWithoutCopiesAreReturnedAsTheyAre() {
        List<CompiledRule> rules = List.of(body("$.a").from("$.request.body.channel").build());
        assertThat(CopyRules.prepare(rules)).isSameAs(rules);
    }
}
