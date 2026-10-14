package com.mhamzah.gateway.mapping;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Output of applying a rule set: JSON body (an object, or an array when rules write one to {@code $}) plus string-valued headers, query parameters and path variables. */
public record MappedMessage(
        JsonNode body, Map<String, String> headers, Map<String, String> query, Map<String, String> pathVariables) {}
