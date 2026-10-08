package com.mhamzah.gateway.extension;

import tools.jackson.databind.JsonNode;

/**
 * Custom logic for a single mapped field. Implement as a Spring bean and reference its bean name
 * from {@code gw_mapping_rule.field_handler}. Runs after default, lookup and converter.
 */
@FunctionalInterface
public interface FieldHandler {

    /**
     * @param value the field value so far; null when missing
     * @return the new value, or null to treat the field as missing
     */
    JsonNode handle(JsonNode value, ExecutionContext ctx);
}
