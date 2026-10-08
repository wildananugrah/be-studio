package com.mhamzah.gateway.config;

import com.mhamzah.gateway.config.ConfigRows.FlowRow;
import com.mhamzah.gateway.config.ConfigRows.LookupRow;
import com.mhamzah.gateway.config.ConfigRows.RuleRow;
import com.mhamzah.gateway.config.ConfigRows.SchemaRow;
import com.mhamzah.gateway.config.ConfigRows.StepRow;
import com.mhamzah.gateway.config.ConfigRows.TargetHeaderRow;
import com.mhamzah.gateway.config.ConfigRows.TargetRow;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/** Test fixture for building config rows with sensible defaults. */
public final class Rows {

    private final AtomicLong ids = new AtomicLong(1);
    private final List<FlowRow> flows = new ArrayList<>();
    private final List<StepRow> steps = new ArrayList<>();
    private final List<RuleRow> rules = new ArrayList<>();
    private final List<LookupRow> lookups = new ArrayList<>();
    private final List<SchemaRow> schemas = new ArrayList<>();
    private final List<TargetRow> targets = new ArrayList<>();
    private final List<TargetHeaderRow> targetHeaders = new ArrayList<>();

    public FlowRow flow(String code, String method, String path) {
        return flow(code, method, path, f -> f);
    }

    public FlowRow flow(String code, String method, String path, UnaryOperator<FlowRow> customize) {
        FlowRow f = customize.apply(new FlowRow(ids.getAndIncrement(), code, code, method, path,
                null, null, null, null, null, null, null, "INHERIT", true));
        flows.add(f);
        return f;
    }

    public StepRow step(FlowRow flow, String name, int order) {
        return step(flow, name, order, s -> s);
    }

    public StepRow step(FlowRow flow, String name, int order, UnaryOperator<StepRow> customize) {
        StepRow s = customize.apply(new StepRow(ids.getAndIncrement(), flow.id(), name, order, "CORE", "POST",
                "/" + name, null, null, "STOP", null, null, null, null, null, true));
        steps.add(s);
        return s;
    }

    /** STEP_REQUEST BODY rule copying {@code source} to {@code target}. */
    public RuleRow stepRule(StepRow step, String target, String source) {
        return rule(step.flowId(), step.id(), "STEP_REQUEST", "BODY", target, source, null);
    }

    /** FLOW_RESPONSE BODY rule copying {@code source} to {@code target}. */
    public RuleRow responseRule(FlowRow flow, String target, String source) {
        return rule(flow.id(), null, "FLOW_RESPONSE", "BODY", target, source, null);
    }

    public RuleRow rule(long flowId, Long stepId, String phase, String targetType, String target, String source,
            String constant) {
        return rule(new RuleRow(ids.getAndIncrement(), flowId, stepId, phase, rules.size() + 1, targetType, target,
                source, constant, null, null, null, null, false));
    }

    public RuleRow rule(RuleRow r) {
        rules.add(r);
        return r;
    }

    public void lookup(String code, String source, String target) {
        lookups.add(new LookupRow(ids.getAndIncrement(), code, source, target));
    }

    public void schema(String code, String text) {
        schemas.add(new SchemaRow(ids.getAndIncrement(), code, text));
    }

    /** gw_target_system row with default timeouts. */
    public TargetRow target(String code, String baseUrl) {
        return target(new TargetRow(ids.getAndIncrement(), code, baseUrl, null, null, null, true));
    }

    public TargetRow target(TargetRow t) {
        targets.add(t);
        return t;
    }

    public void targetHeader(String targetCode, String name, String value) {
        targetHeaders.add(new TargetHeaderRow(ids.getAndIncrement(), targetCode, name, value));
    }

    public ConfigRows build() {
        return new ConfigRows(flows, steps, rules, lookups, schemas, targets, targetHeaders);
    }
}
