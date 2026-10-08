package com.mhamzah.gateway.mapping;

import com.mhamzah.gateway.extension.FieldHandler;
import tools.jackson.databind.JsonNode;

/**
 * A validated, ready-to-run row of {@code gw_mapping_rule}.
 *
 * @param targetPath set for BODY targets
 * @param targetName set for HEADER / QUERY / PATH targets
 * @param source null when the value comes from {@code constant} or only from the field handler
 */
public record CompiledRule(
        long id,
        int seq,
        TargetType targetType,
        JsonPath targetPath,
        String targetName,
        JsonPath source,
        JsonNode constant,
        JsonNode defaultValue,
        Converters.Converter converter,
        LookupTable lookup,
        FieldHandler fieldHandler,
        String fieldHandlerName,
        boolean required) {

    /** True when a failure of this rule is the client's fault (its source is under {@code $.request}). */
    public boolean sourcedFromRequest() {
        return source != null && "request".equals(source.firstField());
    }

    public String describe() {
        String target = targetType == TargetType.BODY ? targetPath.toString() : targetType + ":" + targetName;
        return "mapping rule " + id + " (" + (source != null ? source + " -> " : "") + target + ")";
    }
}
