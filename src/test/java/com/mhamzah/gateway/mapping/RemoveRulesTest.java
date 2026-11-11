package com.mhamzah.gateway.mapping;

import static com.mhamzah.gateway.mapping.RuleBuilder.body;
import static com.mhamzah.gateway.mapping.RuleBuilder.to;
import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.extension.ExecutionContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** REMOVE rules: a body field (also in every array element) or a header, removed after every other rule. */
class RemoveRulesTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final MappingEngine engine = new MappingEngine();
    private ExecutionContext ctx;

    private JsonNode json(String s) {
        return mapper.readTree(s.replace('\'', '"'));
    }

    @BeforeEach
    void setUp() {
        ctx = new ExecutionContext("F1", "corr-1", Map.of(), (ObjectNode) json("{'headers':{},'body':{}}"));
        ctx.putStepResult("lcList", (ObjectNode) json("""
                {'outcome':'SUCCESS','status':200,'body':{
                  'responseCode':'200E000',
                  'data':[{'applicationNo':'A1','status':'NEW','bniReferenceNo':null,'actionable':false},
                          {'applicationNo':'A2','status':'OPEN','bniReferenceNo':'B2','actionable':true}],
                  'meta':{'page':1,'totalPages':2}}}"""));
    }

    private static CompiledRule copy(String source) {
        JsonPath from = JsonPath.compile(source);
        return new CompiledRule(1L, 0, TargetType.COPY, CopyRules.target(from), null, from, null, null, null, null,
                null, null, false);
    }

    private static CompiledRule remove(String target) {
        boolean bodyPath = target.startsWith("$");
        return new CompiledRule(2L, 0, TargetType.REMOVE, bodyPath ? JsonPath.compile(target) : null,
                bodyPath ? null : target, null, null, null, null, null, null, null, false);
    }

    private MappedMessage map(CompiledRule... rules) {
        return engine.apply(CopyRules.prepare(List.of(rules)), ctx);
    }

    @Test
    void removesAFieldFromEveryElementOfACopiedList() {
        JsonNode out = map(copy("$.steps.lcList.body.data"), remove("$.data[*].bniReferenceNo"),
                remove("$.data[*].actionable")).body();
        assertThat(out).isEqualTo(json("{'data':[{'applicationNo':'A1','status':'NEW'},{'applicationNo':'A2','status':'OPEN'}]}"));
    }

    @Test
    void runsAfterEveryOtherRuleWhateverItsOrder() {
        // the REMOVE comes first in the list, the BODY rule that writes the field after it: the field is still gone
        JsonNode out = map(remove("$.meta.totalPages"), copy("$.steps.lcList.body.meta"),
                body("$.meta.totalPages").constant("9").build(), body("$.extra").constant("1").build()).body();
        assertThat(out).isEqualTo(json("{'meta':{'page':1},'extra':1}"));
    }

    @Test
    void worksOnAWholeBodyCopyAndOnNestedObjects() {
        JsonNode out = map(body("$").from("$.steps.lcList.body").build(), remove("$.meta"), remove("$.responseCode")).body();
        assertThat(out.has("data")).isTrue();
        assertThat(out.has("meta")).isFalse();
        assertThat(out.has("responseCode")).isFalse();
    }

    @Test
    void neverChangesTheStepResultItRemovesFrom() {
        map(body("$").from("$.steps.lcList.body").build(), remove("$.meta.totalPages"), remove("$.data[*].actionable"));
        assertThat(ctx.read(JsonPath.compile("$.steps.lcList.body.meta"))).isEqualTo(json("{'page':1,'totalPages':2}"));
        assertThat(ctx.read(JsonPath.compile("$.steps.lcList.body.data[0].actionable"))).isNotNull();
    }

    @Test
    void removingSomethingThatIsNotThereDoesNothing() {
        JsonNode out = map(copy("$.steps.lcList.body.responseCode"), remove("$.nope.deeper"), remove("$.data[*].x"),
                remove("X-Missing")).body();
        assertThat(out).isEqualTo(json("{'responseCode':'200E000'}"));
    }

    @Test
    void removesAHeaderWithoutCaseAndRemembersIt() {
        MappedMessage out = map(to(TargetType.HEADER, "X-Internal").constant("secret").build(),
                to(TargetType.HEADER, "X-Keep").constant("yes").build(), remove("x-internal"), remove("X-Channel-Id"));
        assertThat(out.headers()).containsExactly(Map.entry("X-Keep", "yes"));
        assertThat(out.removedHeaders()).containsExactlyInAnyOrder("x-internal", "x-channel-id");

        // a header added after mapping (e.g. the target system's default) goes too
        Map<String, String> sent = new java.util.LinkedHashMap<>(Map.of("X-Channel-ID", "GATEWAY", "Accept", "application/json"));
        out.removeHeadersFrom(sent);
        assertThat(sent).containsOnlyKeys("Accept");
    }
}
