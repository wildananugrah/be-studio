package com.mhamzah.gateway.mapping;

import java.util.ArrayList;
import java.util.List;

/**
 * Pass-through ({@link TargetType#COPY}) rules: where a copied field lands, and which copies other rules override.
 *
 * <p>A copy keeps the field's place in the body it comes from: {@code $.steps.account.body.detail.openDate} is written
 * to {@code $.detail.openDate}, {@code $.request.body.customer} to {@code $.customer}.
 *
 * <p>A field that another rule of the same list reads is remapped, so it is not copied:
 * <ul>
 *   <li>the other rule reads the copied field or a parent of it ({@code detail.branch} or {@code detail}): the copy is
 *       skipped;</li>
 *   <li>the other rule reads a field inside the copied value ({@code detail.branch} while {@code detail} is copied):
 *       the copy leaves that field out.</li>
 * </ul>
 * The list keeps its order (Studio's preview shows one result per rule); {@link MappingEngine} writes the copies
 * first, so the other rules then add to or replace what was copied.
 */
public final class CopyRules {

    private CopyRules() {}

    /**
     * Where a copy of {@code source} is written: the path after {@code $.request.body} or {@code $.steps.<name>.body}.
     * Null when {@code source} is not a field under one of those bodies.
     */
    public static JsonPath target(JsonPath source) {
        if (source == null) {
            return null;
        }
        List<String> names = source.fieldNames();
        int bodyAt = "request".equals(source.firstField()) ? 1
                : "steps".equals(source.firstField()) && source.secondField() != null ? 2 : -1;
        if (bodyAt < 0 || names.size() <= bodyAt + 1 || !"body".equals(names.get(bodyAt))) {
            return null;
        }
        return source.tail(bodyAt + 1);
    }

    /** The rules with each COPY rule told what the other rules remap (see the class comment); same order. */
    public static List<CompiledRule> prepare(List<CompiledRule> rules) {
        if (rules.stream().noneMatch(r -> r.targetType() == TargetType.COPY)) {
            return rules;
        }
        List<CompiledRule> out = new ArrayList<>(rules.size());
        for (CompiledRule rule : rules) {
            out.add(rule.targetType() == TargetType.COPY ? override(rule, rules) : rule);
        }
        return out;
    }

    private static CompiledRule override(CompiledRule copy, List<CompiledRule> rules) {
        JsonPath from = copy.source();
        List<JsonPath> exclude = new ArrayList<>();
        for (CompiledRule other : rules) {
            // a FILE rule's source is the step itself, not a field of its body
            if (other.targetType() == TargetType.COPY || other.targetType() == TargetType.FILE || other.source() == null) {
                continue;
            }
            JsonPath read = other.source();
            if (from.startsWith(read)) {
                return copy.withCopyOverrides(List.of(), other.describe());
            }
            if (read.startsWith(from)) {
                JsonPath inside = read.tail(from.size());
                if (!exclude.contains(inside)) {
                    exclude.add(inside);
                }
            }
        }
        return copy.withCopyOverrides(exclude, null);
    }
}
