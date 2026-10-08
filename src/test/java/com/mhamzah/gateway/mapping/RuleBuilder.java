package com.mhamzah.gateway.mapping;

import com.mhamzah.gateway.extension.FieldHandler;
import java.util.Map;

/** Test helper for building {@link CompiledRule}s readably. */
public final class RuleBuilder {

    private TargetType targetType = TargetType.BODY;
    private String target;
    private String source;
    private String constant;
    private String defaultValue;
    private String converter;
    private LookupTable lookup;
    private FieldHandler handler;
    private boolean required;

    public static RuleBuilder body(String targetPath) {
        RuleBuilder b = new RuleBuilder();
        b.target = targetPath;
        return b;
    }

    public static RuleBuilder to(TargetType type, String name) {
        RuleBuilder b = new RuleBuilder();
        b.targetType = type;
        b.target = name;
        return b;
    }

    public RuleBuilder from(String sourcePath) {
        this.source = sourcePath;
        return this;
    }

    public RuleBuilder constant(String literal) {
        this.constant = literal;
        return this;
    }

    public RuleBuilder defaultValue(String literal) {
        this.defaultValue = literal;
        return this;
    }

    public RuleBuilder converter(String spec) {
        this.converter = spec;
        return this;
    }

    public RuleBuilder lookup(Map<String, String> entries) {
        java.util.Map<String, tools.jackson.databind.JsonNode> m = new java.util.HashMap<>();
        tools.jackson.databind.JsonNode fallback = null;
        for (var e : entries.entrySet()) {
            if (e.getKey().equals(LookupTable.FALLBACK_KEY)) {
                fallback = JsonValues.parseLiteral(e.getValue());
            } else {
                m.put(e.getKey(), JsonValues.parseLiteral(e.getValue()));
            }
        }
        this.lookup = new LookupTable("TEST", m, fallback);
        return this;
    }

    public RuleBuilder handler(FieldHandler handler) {
        this.handler = handler;
        return this;
    }

    public RuleBuilder required() {
        this.required = true;
        return this;
    }

    public CompiledRule build() {
        return new CompiledRule(
                1L,
                0,
                targetType,
                targetType == TargetType.BODY ? JsonPath.compile(target) : null,
                targetType == TargetType.BODY ? null : target,
                source == null ? null : JsonPath.compile(source),
                constant == null ? null : JsonValues.parseLiteral(constant),
                defaultValue == null ? null : JsonValues.parseLiteral(defaultValue),
                converter == null ? null : Converters.parse(converter),
                lookup,
                handler,
                handler == null ? null : "testHandler",
                required);
    }
}
