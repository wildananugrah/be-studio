package com.mhamzah.gateway.codec;

import com.mhamzah.gateway.extension.BodyCodec;
import com.mhamzah.gateway.extension.ExecutionContext;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Plain XML over HTTP (bean {@code xmlCodec}). The request body JSON must have exactly one field, the root element;
 * see {@link XmlJson} for how JSON and XML correspond.
 */
public class XmlCodec implements BodyCodec {

    public static final String BEAN_NAME = "xmlCodec";

    @Override
    public String contentType() {
        return "application/xml; charset=UTF-8";
    }

    @Override
    public String accept() {
        return "application/xml, text/xml";
    }

    @Override
    public String encode(JsonNode body, Map<String, String> headers, ExecutionContext ctx) {
        return XmlJson.DECLARATION + XmlJson.toXml(body);
    }

    @Override
    public JsonNode decode(String body, Map<String, String> headers) {
        return XmlJson.toJson(body);
    }
}
