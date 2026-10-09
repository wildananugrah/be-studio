package com.mhamzah.gateway.studio;

import com.mhamzah.gateway.config.ConfigCompiler;
import com.mhamzah.gateway.config.ConfigLoader;
import com.mhamzah.gateway.config.ConfigRows;
import com.mhamzah.gateway.config.ConfigValidationException;
import com.mhamzah.gateway.config.FlowDefinition;
import com.mhamzah.gateway.config.FlowRegistry;
import com.mhamzah.gateway.config.FlowRegistryHolder;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.config.StepDefinition;
import com.mhamzah.gateway.extension.BodyCodec;
import com.mhamzah.gateway.extension.ErrorHandler;
import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.extension.FieldHandler;
import com.mhamzah.gateway.extension.GatewayError;
import com.mhamzah.gateway.extension.LookupErrorHandler;
import com.mhamzah.gateway.extension.MessageHandler;
import com.mhamzah.gateway.mapping.CompiledRule;
import com.mhamzah.gateway.mapping.JsonValues;
import com.mhamzah.gateway.mapping.MappedMessage;
import com.mhamzah.gateway.mapping.MappingEngine;
import com.mhamzah.gateway.mapping.TargetType;
import com.mhamzah.gateway.studio.StudioConfig.Flow;
import com.mhamzah.gateway.studio.StudioConfig.Step;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** What Gateway Studio does with the configuration: read it, check a draft, preview a mapping, save and reload. */
public class StudioService {

    /** Converters with the arguments a developer usually starts from (see {@code Converters}). */
    static final List<String> CONVERTERS = List.of("TO_STRING", "TO_NUMBER", "TO_BOOLEAN", "TRIM", "UPPER", "LOWER",
            "PAD_LEFT:12:0", "PAD_RIGHT:12: ", "SUBSTRING:0:6", "DATE_FORMAT:yyyyMMdd:dd/MM/yyyy", "DECIMAL_SCALE:2");

    private final ConfigLoader loader;
    private final ConfigCompiler compiler;
    private final FlowRegistryHolder holder;
    private final MappingEngine mapping;
    private final ListableBeanFactory beans;
    private final GatewayProperties properties;
    private final StudioWriter writer;
    private final TransactionTemplate tx;

    public StudioService(ConfigLoader loader, ConfigCompiler compiler, FlowRegistryHolder holder, MappingEngine mapping,
            ListableBeanFactory beans, GatewayProperties properties, DataSource dataSource,
            PlatformTransactionManager txManager) {
        this.loader = loader;
        this.compiler = compiler;
        this.holder = holder;
        this.mapping = mapping;
        this.beans = beans;
        this.properties = properties;
        this.writer = new StudioWriter(dataSource, properties.db());
        this.tx = new TransactionTemplate(txManager);
    }

    /** The configuration as stored in the database, with its version. */
    public StudioConfig load() {
        StudioConfig config = StudioRows.fromRows(loader.load(), writer.schemaDescriptions());
        return config.withVersion(version(config));
    }

    /** Every problem a save of {@code draft} would be rejected with; empty when it is valid. */
    public List<String> validate(StudioConfig draft) {
        return problems(StudioRows.toRows(draft));
    }

    private List<String> problems(ConfigRows rows) {
        List<String> errors = new ArrayList<>(StudioRows.problems(rows));
        try {
            compiler.compile(withoutEmptySchemas(rows));
        } catch (ConfigValidationException e) {
            e.errors().stream().filter(m -> !errors.contains(m)).forEach(errors::add);
        }
        return errors;
    }

    /** Schemas without text are already reported by {@link StudioRows#problems}; the compiler cannot parse them. */
    private static ConfigRows withoutEmptySchemas(ConfigRows rows) {
        return new ConfigRows(rows.flows(), rows.steps(), rows.rules(), rows.lookups(),
                rows.schemas().stream().filter(x -> x.schemaText() != null && !x.schemaText().isBlank()).toList(),
                rows.targets(), rows.targetHeaders());
    }

    /** Outcome of {@link #save}. */
    public sealed interface SaveResult {
        record Saved(FlowRegistry registry, String version) implements SaveResult {}

        record Invalid(List<String> errors) implements SaveResult {}

        /** The database changed since {@code baseVersion} was read. */
        record Conflict(String currentVersion) implements SaveResult {}
    }

    /**
     * Replaces the config rows with {@code draft} and reloads, if the database is still at {@code baseVersion} and
     * the draft is valid. Nothing is written otherwise.
     */
    public synchronized SaveResult save(StudioConfig draft, String baseVersion) {
        String currentVersion = load().version();
        if (!Objects.equals(baseVersion, currentVersion)) {
            return new SaveResult.Conflict(currentVersion);
        }
        ConfigRows rows = StudioRows.toRows(draft);
        List<String> errors = problems(rows);
        if (!errors.isEmpty()) {
            return new SaveResult.Invalid(errors);
        }
        Map<String, String> descriptions = new HashMap<>();
        draft.schemas().forEach(x -> descriptions.put(x.code(),
                x.description() == null || x.description().isBlank() ? null : x.description()));
        tx.executeWithoutResult(status -> writer.replace(rows, descriptions));
        FlowRegistry registry = holder.reload();
        return new SaveResult.Saved(registry, load().version());
    }

    /**
     * Runs the mapping rules of one step ({@code STEP_REQUEST}) or of the flow response ({@code step} null) of
     * {@code flowCode} in {@code draft} against {@code sample}, a context of the form
     * {@code {"request": {...}, "steps": {"name": {...}}}}, with the real converters, lookups and field handlers.
     * Each rule is also run on its own so its result can be shown next to it.
     */
    public Preview preview(StudioConfig draft, String flowCode, String step, JsonNode sample) {
        Flow flow = draft.flows().stream().filter(f -> Objects.equals(f.code(), flowCode)).findFirst().orElse(null);
        if (flow == null) {
            return Preview.failed(List.of("unknown flow '" + flowCode + "'"));
        }
        // only this flow, enabled with all its steps, so it compiles even while switched off
        Flow only = new Flow(flow.code(), flow.name(), flow.method(), flow.path(), flow.requestSchema(),
                flow.responseSchema(), flow.requestHandler(), flow.responseHandler(), flow.errorHandler(),
                flow.successStatus(), flow.timeoutMs(), flow.auditMode(), true,
                flow.steps().stream().map(StudioService::enabled).toList(), flow.response());
        StudioConfig single = new StudioConfig(null, List.of(only), draft.targets(), draft.lookups(), draft.schemas());
        FlowRegistry registry;
        try {
            registry = compiler.compile(withoutEmptySchemas(StudioRows.toRows(single)));
        } catch (ConfigValidationException e) {
            return Preview.failed(e.errors());
        }
        FlowDefinition def = registry.flows().getFirst();
        List<CompiledRule> rules;
        if (step == null || step.isBlank()) {
            rules = def.responseRules();
        } else {
            StepDefinition s = def.allSteps().stream().filter(x -> x.name().equals(step)).findFirst().orElse(null);
            if (s == null) {
                return Preview.failed(List.of("unknown step '" + step + "'"));
            }
            rules = s.requestRules();
        }

        ExecutionContext ctx = context(flow.code(), registry, sample);
        List<RuleResult> results = new ArrayList<>();
        for (CompiledRule rule : rules) {
            try {
                results.add(RuleResult.of(rule, mapping.apply(List.of(rule), ctx)));
            } catch (GatewayError e) {
                results.add(new RuleResult(null, false, message(e)));
            } catch (RuntimeException e) {
                results.add(new RuleResult(null, false, e.getMessage()));
            }
        }
        List<String> errors = new ArrayList<>();
        Output output = null;
        try {
            MappedMessage m = mapping.apply(rules, ctx);
            output = new Output(m.body(), m.headers(), m.query(), m.pathVariables());
        } catch (GatewayError e) {
            errors.add(message(e));
        } catch (RuntimeException e) {
            errors.add(String.valueOf(e.getMessage()));
        }
        return new Preview(errors, results, output);
    }

    private static Step enabled(Step s) {
        return new Step(s.name(), s.order(), s.targetSystem(), s.method(), s.path(), s.condition(), s.success(),
                s.onFailure(), s.timeoutMs(), s.responseSchema(), s.requestHandler(), s.responseHandler(),
                s.bodyCodec(), true, s.rules());
    }

    private static ExecutionContext context(String flowCode, FlowRegistry registry, JsonNode sample) {
        JsonNode request = sample == null ? null : sample.get("request");
        ExecutionContext ctx = new ExecutionContext(flowCode, "preview-correlation-id", registry.lookups(),
                request instanceof ObjectNode o ? o.deepCopy() : JsonNodeFactory.instance.objectNode());
        JsonNode steps = sample == null ? null : sample.get("steps");
        if (steps != null && steps.isObject()) {
            steps.properties().forEach(e -> {
                if (e.getValue() instanceof ObjectNode o) {
                    ctx.putStepResult(e.getKey(), o.deepCopy());
                }
            });
        }
        return ctx;
    }

    private static String message(GatewayError e) {
        return e.details().isEmpty() ? e.getMessage() : e.getMessage() + ": " + String.join("; ", e.details());
    }

    /** Result of {@link #preview}: compile or mapping errors, one result per rule and the combined output. */
    public record Preview(List<String> errors, List<RuleResult> rules, Output output) {
        static Preview failed(List<String> errors) {
            return new Preview(errors, List.of(), null);
        }
    }

    /** What one rule wrote ({@code written} false: nothing, e.g. the source was missing), or why it failed. */
    public record RuleResult(JsonNode value, boolean written, String error) {
        static RuleResult of(CompiledRule rule, MappedMessage m) {
            JsonNode value = switch (rule.targetType()) {
                case BODY -> rule.targetPath().wildcardCount() > 0
                        ? rule.targetPath().prefixBeforeWildcard().read(m.body())
                        : rule.targetPath().read(m.body());
                case HEADER -> text(m.headers().get(rule.targetName()));
                case QUERY -> text(m.query().get(rule.targetName()));
                case PATH -> text(m.pathVariables().get(rule.targetName()));
            };
            return new RuleResult(value, value != null, null);
        }

        private static JsonNode text(String s) {
            return s == null ? null : JsonNodeFactory.instance.stringNode(s);
        }
    }

    public record Output(JsonNode body, Map<String, String> headers, Map<String, String> query, Map<String, String> path) {}

    /** Names a developer can pick from: handler and codec beans, converters, application.yml targets. */
    public Map<String, Object> catalog() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apiBasePath", properties.apiBasePath());
        out.put("defaultFlowTimeoutMs", properties.defaultFlowTimeoutMs());
        out.put("defaultStepTimeoutMs", properties.defaultStepTimeoutMs());
        out.put("auditEnabled", properties.audit().enabled());
        out.put("assistantEnabled", properties.assistant().enabled());
        out.put("studioMode", properties.studio().viewOnly() ? "view-only" : "edit");
        out.put("messageHandlers", names(MessageHandler.class));
        out.put("fieldHandlers", names(FieldHandler.class));
        out.put("bodyCodecs", names(BodyCodec.class));
        List<Map<String, Object>> errorHandlers = new ArrayList<>();
        for (String name : names(ErrorHandler.class)) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("name", name);
            if (beans.getBean(name) instanceof LookupErrorHandler l) {
                h.put("lookupCode", l.translatingLookupCode());
                h.put("codePath", l.downstreamCodePath());
            }
            errorHandlers.add(h);
        }
        out.put("errorHandlers", errorHandlers);
        out.put("converters", CONVERTERS);
        out.put("targetTypes", Arrays.stream(TargetType.values()).map(Enum::name).toList());
        List<Map<String, Object>> configTargets = new ArrayList<>();
        properties.targetSystems().forEach((code, t) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("baseUrl", t.baseUrl());
            m.put("connectTimeoutMs", t.connectTimeoutMs());
            m.put("readTimeoutMs", t.readTimeoutMs());
            m.put("bodyCodec", t.bodyCodec());
            m.put("headers", t.staticHeaders().keySet());
            configTargets.add(m);
        });
        out.put("configTargets", configTargets);
        return out;
    }

    private List<String> names(Class<?> type) {
        return Arrays.stream(beans.getBeanNamesForType(type)).sorted().toList();
    }

    /** SHA-256 of the canonical JSON of {@code config} without its version. */
    static String version(StudioConfig config) {
        try {
            byte[] json = JsonValues.MAPPER.writeValueAsString(config.withVersion(null)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json)).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
