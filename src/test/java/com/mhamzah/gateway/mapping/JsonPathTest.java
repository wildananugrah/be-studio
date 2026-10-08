package com.mhamzah.gateway.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class JsonPathTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private JsonNode json(String s) {
        return mapper.readTree(s.replace('\'', '"'));
    }

    @Test
    void readsNestedField() {
        JsonNode root = json("{'a':{'b':{'c':42}}}");
        assertThat(JsonPath.compile("$.a.b.c").read(root).intValue()).isEqualTo(42);
    }

    @Test
    void readsBracketedFieldAndIndex() {
        JsonNode root = json("{'x-y':[{'v':1},{'v':2}]}");
        assertThat(JsonPath.compile("$['x-y'][1].v").read(root).intValue()).isEqualTo(2);
    }

    @Test
    void missingPathReadsAsNull() {
        assertThat(JsonPath.compile("$.a.nope.deeper").read(json("{'a':{}}"))).isNull();
        assertThat(JsonPath.compile("$.a[5]").read(json("{'a':[1]}"))).isNull();
    }

    @Test
    void rootPathReadsWholeDocument() {
        JsonNode root = json("{'a':1}");
        assertThat(JsonPath.compile("$").read(root)).isEqualTo(root);
    }

    @Test
    void writeCreatesIntermediateObjects() {
        ObjectNode target = mapper.createObjectNode();
        JsonPath.compile("$.a.b.c").write(target, json("'v'"));
        assertThat(target).isEqualTo(json("{'a':{'b':{'c':'v'}}}"));
    }

    @Test
    void writeCreatesArraysAndPadsElements() {
        ObjectNode target = mapper.createObjectNode();
        JsonPath.compile("$.items[1].name").write(target, json("'second'"));
        assertThat(target).isEqualTo(json("{'items':[{},{'name':'second'}]}"));
    }

    @Test
    void writeToRootMergesObjectFields() {
        ObjectNode target = (ObjectNode) json("{'keep':1}");
        JsonPath.compile("$").write(target, json("{'added':2}"));
        assertThat(target).isEqualTo(json("{'keep':1,'added':2}"));
    }

    @Test
    void wildcardCountAndSubstitution() {
        JsonPath p = JsonPath.compile("$.a[*].b[*].c");
        assertThat(p.wildcardCount()).isEqualTo(2);
        assertThat(p.withIndices(new int[] {3, 1}).toString()).isEqualTo("$.a[3].b[1].c");
    }

    @Test
    void readAllCollectsWildcardMatchesWithTheirIndices() {
        JsonNode root = json("{'items':[{'p':1},{'q':0},{'p':3}]}");
        var matches = JsonPath.compile("$.items[*].p").readAll(root);
        assertThat(matches).hasSize(3);
        assertThat(matches.get(0).indices()).containsExactly(0);
        assertThat(matches.get(0).value().intValue()).isEqualTo(1);
        assertThat(matches.get(1).value()).isNull();
        assertThat(matches.get(2).indices()).containsExactly(2);
    }

    @Test
    void rejectsMalformedPaths() {
        assertThatThrownBy(() -> JsonPath.compile("a.b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonPath.compile("$.a[")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonPath.compile("$.a[x]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonPath.compile("$.")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writeWithWildcardIsRejected() {
        assertThatThrownBy(() -> JsonPath.compile("$.a[*]").write(mapper.createObjectNode(), json("1")))
                .isInstanceOf(IllegalStateException.class);
    }
}
