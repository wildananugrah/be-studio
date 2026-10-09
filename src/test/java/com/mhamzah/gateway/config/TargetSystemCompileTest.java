package com.mhamzah.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mhamzah.gateway.codec.JsonCodec;
import com.mhamzah.gateway.codec.SoapCodec;
import com.mhamzah.gateway.codec.XmlCodec;
import com.mhamzah.gateway.config.ConfigRows.TargetRow;
import com.mhamzah.gateway.extension.DefaultErrorHandler;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Target systems from gw_target_system / gw_target_system_header, merged with application.yml (DB wins). */
class TargetSystemCompileTest {

    private final Map<String, GatewayProperties.TargetSystem> configTargets = new HashMap<>();
    private final Map<String, String> env = new HashMap<>(Map.of("CARD_API_KEY", "s3cret", "CORE_HOST", "10.1.1.1"));
    private final Rows rows = new Rows();
    private final SoapCodec soapCodec = SoapCodec.soap11();
    private final XmlCodec xmlCodec = new XmlCodec();
    private final Map<String, Object> beans = Map.of("defaultErrorHandler", new DefaultErrorHandler(),
            "soapCodec", soapCodec, "xmlCodec", xmlCodec, "jsonCodec", JsonCodec.INSTANCE);

    /** Resolves ${NAME} and ${NAME:default} from {@link #env}; fails on unknown names like Spring does. */
    private final UnaryOperator<String> placeholders = text -> {
        Matcher m = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}").matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = env.getOrDefault(m.group(1), m.group(2));
            if (value == null) {
                throw new IllegalArgumentException("Could not resolve placeholder '" + m.group(1) + "'");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    };

    private FlowRegistry compile() {
        ConfigCompiler compiler = new ConfigCompiler(
                new ConfigCompiler.HandlerLookup() {
                    @Override
                    public <T> T find(String name, Class<T> type) {
                        Object bean = beans.get(name);
                        return type.isInstance(bean) ? type.cast(bean) : null;
                    }
                },
                configTargets, placeholders, Duration.ofSeconds(30), Duration.ofSeconds(10));
        return compiler.compile(rows.build());
    }

    private void assertInvalid(String... fragments) {
        assertThatThrownBy(this::compile).isInstanceOfSatisfying(ConfigValidationException.class, e -> {
            for (String f : fragments) {
                assertThat(e.errors()).anySatisfy(err -> assertThat(err).contains(f));
            }
        });
    }

    private void flowCalling(String target) {
        var flow = rows.flow("F", "GET", "/f");
        rows.step(flow, "s", 1, s -> s.withTarget(target));
    }

    @Test
    void stepUsesTargetFromDatabase() {
        rows.target(new TargetRow(1, "CORE", "http://10.20.30.40:9080/api", 1500, 7000, null, true));
        rows.targetHeader("CORE", "X-Channel-Id", "GATEWAY");
        flowCalling("CORE");

        FlowRegistry registry = compile();

        StepDefinition step = registry.flows().getFirst().allSteps().getFirst();
        assertThat(step.targetSystem().baseUrl()).isEqualTo("http://10.20.30.40:9080/api");
        assertThat(step.targetSystem().connectTimeoutMs()).isEqualTo(1500);
        assertThat(step.timeout()).isEqualTo(Duration.ofMillis(7000));
        assertThat(step.targetSystem().staticHeaders()).containsEntry("X-Channel-Id", "GATEWAY");
        assertThat(registry.targetSystems().get("CORE").source()).isEqualTo(ResolvedTarget.Source.DATABASE);
    }

    @Test
    void tlsSettingsResolvePlaceholdersAndReachTheStep() {
        env.put("CORE_TLS_MODE_UNUSED", "x");
        env.put("CORE_CA", "classpath:does-not-matter.pem");
        rows.target(new TargetRow(1, "CORE", "http://core", null, null, null, true, "insecure", null, null, null, null));
        flowCalling("CORE");
        StepDefinition step = compile().flows().getFirst().allSteps().getFirst();
        assertThat(step.targetSystem().tls().mode()).isEqualTo("INSECURE"); // kept, but unused for http://
    }

    @Test
    void badTlsOnAnHttpsTargetIsAConfigError() {
        rows.target(new TargetRow(1, "CORE", "https://core", null, null, null, true, "CUSTOM",
                "/no/such/ca.pem", null, null, null));
        flowCalling("CORE");
        assertInvalid("target system 'CORE': tls: cannot load the trust store (/no/such/ca.pem)");
    }

    @Test
    void customTlsNeedsAStoreAndPlaceholdersMustResolve() {
        rows.target(new TargetRow(1, "CORE", "https://core", null, null, null, true, "CUSTOM", null, null, null, null));
        rows.target(new TargetRow(2, "CARD", "https://card", null, null, null, true, "CUSTOM", "${CARD_CA}", null,
                null, null));
        assertInvalid("target system 'CORE': tls: tls_mode CUSTOM needs a trust store",
                "target system 'CARD': tls: Could not resolve placeholder 'CARD_CA'");
    }

    @Test
    void unknownTlsModeIsReportedEvenForHttp() {
        rows.target(new TargetRow(1, "CORE", "http://core", null, null, null, true, "STRICT", null, null, null, null));
        assertInvalid("target system 'CORE': tls_mode 'STRICT' must be VERIFY, INSECURE or CUSTOM");
    }

    @Test
    void databaseDefaultsForTimeouts() {
        rows.target("CORE", "http://core");
        flowCalling("CORE");
        StepDefinition step = compile().flows().getFirst().allSteps().getFirst();
        assertThat(step.targetSystem().connectTimeoutMs()).isEqualTo(3000);
        assertThat(step.timeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void databaseWinsOverApplicationConfig() {
        configTargets.put("CORE", new GatewayProperties.TargetSystem("http://from-yml", 1000, 2000, Map.of("X-Yml", "1"), null));
        rows.target("CORE", "http://from-db");
        flowCalling("CORE");

        FlowRegistry registry = compile();

        ResolvedTarget core = registry.targetSystems().get("CORE");
        assertThat(core.system().baseUrl()).isEqualTo("http://from-db");
        assertThat(core.system().staticHeaders()).doesNotContainKey("X-Yml");
        assertThat(core.overridesConfig()).isTrue();
    }

    @Test
    void applicationConfigUsedWhenNotInDatabase() {
        configTargets.put("CORE", new GatewayProperties.TargetSystem("http://from-yml", 1000, 2000, Map.of(), null));
        flowCalling("CORE");
        ResolvedTarget core = compile().targetSystems().get("CORE");
        assertThat(core.system().baseUrl()).isEqualTo("http://from-yml");
        assertThat(core.source()).isEqualTo(ResolvedTarget.Source.CONFIG);
        assertThat(core.overridesConfig()).isFalse();
    }

    @Test
    void disabledDatabaseRowIsIgnored() {
        configTargets.put("CORE", new GatewayProperties.TargetSystem("http://from-yml", 1000, 2000, Map.of(), null));
        rows.target(new TargetRow(1, "CORE", "http://from-db", null, null, null, false));
        rows.targetHeader("CORE", "X-Ignored", "1");
        flowCalling("CORE");
        assertThat(compile().targetSystems().get("CORE").system().baseUrl()).isEqualTo("http://from-yml");
    }

    @Test
    void placeholdersAreResolvedInBaseUrlAndHeaders() {
        rows.target("CARD", "https://${CORE_HOST}:${CORE_PORT:8443}/cards");
        rows.targetHeader("CARD", "X-Api-Key", "${CARD_API_KEY}");
        flowCalling("CARD");
        GatewayProperties.TargetSystem card = compile().targetSystems().get("CARD").system();
        assertThat(card.baseUrl()).isEqualTo("https://10.1.1.1:8443/cards");
        assertThat(card.staticHeaders()).containsEntry("X-Api-Key", "s3cret");
    }

    @Test
    void unresolvablePlaceholderIsAnError() {
        rows.target("CARD", "http://card");
        rows.targetHeader("CARD", "X-Api-Key", "${MISSING_KEY}");
        assertInvalid("target system 'CARD'", "MISSING_KEY");
    }

    @Test
    void baseUrlMustBeAnHttpUrlWithHost() {
        rows.target("A", "10.1.1.1:9080");
        rows.target("B", "ftp://files.internal");
        rows.target("C", "http://");
        rows.target("D", "http://host:9080/api?x=1");
        assertInvalid("target system 'A'", "target system 'B'", "target system 'C'", "target system 'D'");
    }

    @Test
    void timeoutsMustBePositive() {
        rows.target(new TargetRow(1, "CORE", "http://core", 0, -5, null, true));
        assertInvalid("connect_timeout_ms", "read_timeout_ms");
    }

    @Test
    void headersMustBelongToAKnownTargetAndBeValid() {
        rows.target("CORE", "http://core");
        rows.targetHeader("NOPE", "X-A", "1");
        rows.targetHeader("CORE", "Bad Header", "1");
        rows.targetHeader("CORE", "X-Injected", "a\r\nX-Evil: 1");
        assertInvalid("unknown target system 'NOPE'", "'Bad Header'", "X-Injected");
    }

    @Test
    void configTargetWithoutBaseUrlIsAnError() {
        configTargets.put("CORE", new GatewayProperties.TargetSystem(null, 1000, null, Map.of(), null));
        assertInvalid("target system 'CORE'", "base");
    }

    @Test
    void unknownTargetStillReportedForSteps() {
        flowCalling("NOWHERE");
        assertInvalid("target_system 'NOWHERE' is not configured");
    }

    @Test
    void stepsDefaultToJson() {
        rows.target("CORE", "http://core");
        flowCalling("CORE");

        StepDefinition step = compile().flows().getFirst().allSteps().getFirst();

        assertThat(step.bodyCodec()).isSameAs(JsonCodec.INSTANCE);
        assertThat(step.bodyCodecName()).isEqualTo("jsonCodec");
    }

    @Test
    void stepUsesBodyCodecOfItsTargetSystem() {
        rows.target(new TargetRow(1, "CORE", "http://core", null, null, "soapCodec", true));
        flowCalling("CORE");

        StepDefinition step = compile().flows().getFirst().allSteps().getFirst();

        assertThat(step.bodyCodec()).isSameAs(soapCodec);
        assertThat(step.bodyCodecName()).isEqualTo("soapCodec");
        assertThat(step.targetSystem().bodyCodec()).isEqualTo("soapCodec");
    }

    @Test
    void stepBodyCodecWinsOverTargetSystem() {
        rows.target(new TargetRow(1, "CORE", "http://core", null, null, "xmlCodec", true));
        var flow = rows.flow("F", "GET", "/f");
        rows.step(flow, "xml", 1);
        rows.step(flow, "json", 1, s -> s.withBodyCodec("jsonCodec"));

        var steps = compile().flows().getFirst().allSteps();

        assertThat(steps).filteredOn(s -> s.name().equals("xml")).singleElement()
                .satisfies(s -> assertThat(s.bodyCodec()).isSameAs(xmlCodec));
        assertThat(steps).filteredOn(s -> s.name().equals("json")).singleElement()
                .satisfies(s -> assertThat(s.bodyCodec()).isSameAs(JsonCodec.INSTANCE));
    }

    @Test
    void applicationConfigTargetCanSetBodyCodec() {
        configTargets.put("CORE", new GatewayProperties.TargetSystem("http://core", 1000, null, Map.of(), "xmlCodec"));
        flowCalling("CORE");

        assertThat(compile().flows().getFirst().allSteps().getFirst().bodyCodec()).isSameAs(xmlCodec);
    }

    @Test
    void unknownBodyCodecIsAConfigError() {
        rows.target(new TargetRow(1, "CORE", "http://core", null, null, "nope", true));
        var flow = rows.flow("F", "GET", "/f");
        rows.step(flow, "s", 1, s -> s.withBodyCodec("missing"));

        assertInvalid("target system 'CORE': body_codec 'nope' is not a BodyCodec bean",
                "step 's': body_codec 'missing' is not a BodyCodec bean");
    }
}
