package com.mhamzah.gateway.mapping;

import java.util.Map;
import tools.jackson.databind.node.ObjectNode;

/** Output of applying a rule set: JSON body plus string-valued headers, query parameters and path variables. */
public record MappedMessage(
        ObjectNode body, Map<String, String> headers, Map<String, String> query, Map<String, String> pathVariables) {}
