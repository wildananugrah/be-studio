package com.mhamzah.gateway.studio.testing;

import com.mhamzah.gateway.config.ConfigCompiler;
import com.mhamzah.gateway.config.FlowDefinition;
import com.mhamzah.gateway.config.FlowRegistry;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.config.Rows;
import com.mhamzah.gateway.docs.OpenApiGenerator;
import com.mhamzah.gateway.extension.DefaultErrorHandler;
import java.time.Duration;
import java.util.Map;
import java.util.function.UnaryOperator;
import tools.jackson.databind.JsonNode;

/** A router flow PAYMENT whose request is one of the TRANSFER_REQUEST / TOPUP_REQUEST bodies, and its OpenAPI document. */
final class RouterFixture {

    static final String TRANSFER = """
            {"type":"object","required":["fromAccount","amount"],
             "properties":{"fromAccount":{"type":"string"},"amount":{"type":"number"}}}""";
    static final String TOPUP = """
            {"type":"object","required":["phoneNo","amount"],
             "properties":{"phoneNo":{"type":"string"},"amount":{"type":"number"}}}""";
    static final String PAYMENT = """
            {"oneOf":[{"$ref":"gw:TRANSFER_REQUEST"},{"$ref":"gw:TOPUP_REQUEST"}]}""";

    final Rows rows = new Rows();
    FlowRegistry registry;
    JsonNode doc;

    RouterFixture() {
        rows.schema("TRANSFER_REQUEST", TRANSFER);
        rows.schema("TOPUP_REQUEST", TOPUP);
        rows.schema("PAYMENT_REQUEST", PAYMENT);
        rows.flow("TRANSFER", "POST", "/transfers", f -> f.withSchemas("TRANSFER_REQUEST", null));
        rows.flow("PAYMENT", "POST", "/payments", f -> f.withSchemas("PAYMENT_REQUEST", null).withSchemaValidation("NONE"));
    }

    /** Compiles the rows (call after adding any more) and generates the OpenAPI document. */
    RouterFixture build() {
        ConfigCompiler compiler = new ConfigCompiler(
                new ConfigCompiler.HandlerLookup() {
                    @Override
                    public <T> T find(String name, Class<T> type) {
                        return name.equals("defaultErrorHandler") ? type.cast(new DefaultErrorHandler()) : null;
                    }
                },
                Map.of("CORE", new GatewayProperties.TargetSystem("http://core", 1000, 2000, Map.of(), null)),
                UnaryOperator.identity(), Duration.ofSeconds(5), Duration.ofSeconds(2));
        registry = compiler.compile(rows.build());
        doc = OpenApiGenerator.generate(registry, "/api", "Test API", null);
        return this;
    }

    FlowDefinition flow(String code) {
        return registry.flows().stream().filter(f -> f.code().equals(code)).findFirst().orElseThrow();
    }
}
