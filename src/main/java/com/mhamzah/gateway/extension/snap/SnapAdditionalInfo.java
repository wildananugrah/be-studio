package com.mhamzah.gateway.extension.snap;

import com.mhamzah.gateway.config.FlowDefinition;
import com.mhamzah.gateway.config.FlowRegistry;
import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.mapping.MappedMessage;
import com.mhamzah.gateway.mapping.MappingEngine;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code additionalInfo} of SNAP responses, success and error alike. It is
 * optional: only a flow with the named
 * response {@value FlowDefinition#ADDITIONAL_INFO_RESPONSE} (or, on success,
 * rules on {@code $.additionalInfo.*})
 * sends it, and only when its rules produce at least one field. The response's
 * BODY rules build the object; on an
 * error they can also read {@code $.error.} and the failed call at
 * {@code $.steps.<step>.}, so rules that read only
 * those leave it out of a success.
 */
final class SnapAdditionalInfo {

    static final String FIELD = FlowDefinition.ADDITIONAL_INFO_RESPONSE;

    private static final Logger log = LoggerFactory.getLogger(SnapAdditionalInfo.class);

    private SnapAdditionalInfo() {
    }

    static FlowDefinition flow(Supplier<FlowRegistry> registry, String code) {
        if (code == null) {
            return null;
        }
        FlowRegistry current = registry.get();
        return current == null ? null
                : current.flows().stream().filter(f -> f.code().equals(code)).findFirst().orElse(null);
    }

    /**
     * The object the flow's {@value #FIELD} response builds; null (not sent) when
     * the flow has none or it is empty.
     */
    static ObjectNode build(FlowDefinition flow, MappingEngine mapping, ExecutionContext ctx) {
        FlowDefinition.Response rules = flow == null ? null : flow.responses().get(FIELD);
        if (rules == null) {
            return null;
        }
        try {
            MappedMessage out = mapping.apply(rules.rules(), ctx);
            if (out.body() instanceof ObjectNode o) {
                return o.isEmpty() ? null : o;
            }
            if (out.body() != null && !out.body().isNull() && !out.body().isMissingNode()) {
                log.warn("additionalInfo of flow {} is not a JSON object; leaving it out", flow.code());
            }
        } catch (RuntimeException ex) {
            log.warn("additionalInfo of flow {} could not be built; leaving it out: {}", flow.code(), ex.getMessage());
        }
        return null;
    }
}