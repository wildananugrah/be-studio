package com.mhamzah.gateway.mapping;

import com.mhamzah.gateway.extension.FieldHandler;
import tools.jackson.databind.JsonNode;

/**
 * A validated, ready-to-run row of {@code gw_mapping_rule}.
 *
 * @param targetPath set for BODY targets
 * @param targetName set for HEADER / QUERY / PATH targets
 * @param source null when the value comes from {@code constant}, an error map or only from the field handler
 * @param errorMap set when the value comes from an error map (then {@code source} and {@code constant} are null)
 * @param copyExclude COPY rules: the fields (relative to the copied value) other rules remap, left out of the copy
 * @param remappedBy COPY rules: the rule that remaps the whole field, which is then not copied; null when copied
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
        boolean required,
        ErrorMap.Lookup errorMap,
        java.util.List<JsonPath> copyExclude,
        String remappedBy) {

    public CompiledRule {
        copyExclude = copyExclude == null ? java.util.List.of() : java.util.List.copyOf(copyExclude);
    }

    public CompiledRule(long id, int seq, TargetType targetType, JsonPath targetPath, String targetName,
            JsonPath source, JsonNode constant, JsonNode defaultValue, Converters.Converter converter,
            LookupTable lookup, FieldHandler fieldHandler, String fieldHandlerName, boolean required,
            ErrorMap.Lookup errorMap) {
        this(id, seq, targetType, targetPath, targetName, source, constant, defaultValue, converter, lookup,
                fieldHandler, fieldHandlerName, required, errorMap, null, null);
    }

    public CompiledRule(long id, int seq, TargetType targetType, JsonPath targetPath, String targetName,
            JsonPath source, JsonNode constant, JsonNode defaultValue, Converters.Converter converter,
            LookupTable lookup, FieldHandler fieldHandler, String fieldHandlerName, boolean required) {
        this(id, seq, targetType, targetPath, targetName, source, constant, defaultValue, converter, lookup,
                fieldHandler, fieldHandlerName, required, null);
    }

    /** This COPY rule with what other rules remap (see {@link CopyRules}). */
    public CompiledRule withCopyOverrides(java.util.List<JsonPath> exclude, String remappedBy) {
        return new CompiledRule(id, seq, targetType, targetPath, targetName, source, constant, defaultValue, converter,
                lookup, fieldHandler, fieldHandlerName, required, errorMap, exclude, remappedBy);
    }

    /** The context paths this rule reads: its source and its error map conditions. */
    public java.util.List<JsonPath> references() {
        java.util.List<JsonPath> out = new java.util.ArrayList<>();
        if (source != null) {
            out.add(source);
        }
        if (errorMap != null) {
            out.addAll(errorMap.references());
        }
        return out;
    }

    /** True when a failure of this rule is the client's fault (its source is under {@code $.request}). */
    public boolean sourcedFromRequest() {
        return source != null && "request".equals(source.firstField());
    }

    public String describe() {
        String target = targetType == TargetType.BODY ? targetPath.toString()
                : targetType == TargetType.COPY ? "copy " + targetPath
                : targetType == TargetType.REMOVE ? "remove " + (targetPath != null ? targetPath : "header " + targetName)
                : targetType + ":" + targetName;
        String from = source != null ? source + " -> " : errorMap != null ? "error map " + errorMap.map().code() + " -> " : "";
        return "mapping rule " + id + " (" + from + target + ")";
    }
}
