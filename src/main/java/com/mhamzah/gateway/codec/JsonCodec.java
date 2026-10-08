package com.mhamzah.gateway.codec;

import com.mhamzah.gateway.extension.BodyCodec;
import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.mapping.JsonValues;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * JSON on the wire (bean {@code jsonCodec}): the default for every step. Set it on a step explicitly only to call
 * one JSON endpoint of a target system whose {@code body_codec} is XML or SOAP.
 */
public final class JsonCodec implements BodyCodec {

    public static final String BEAN_NAME = "jsonCodec";
    public static final JsonCodec INSTANCE = new JsonCodec();

    private JsonCodec() {}

    @Override
    public String contentType() {
        return "application/json";
    }

    @Override
    public String encode(JsonNode body, Map<String, String> headers, ExecutionContext ctx) {
        return body.toString();
    }

    @Override
    public JsonNode decode(String body, Map<String, String> headers) {
        try {
            return JsonValues.MAPPER.readTree(body);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("not JSON", e);
        }
    }
}
