package com.mhamzah.gateway.codec;

import com.mhamzah.gateway.extension.BodyCodec;
import com.mhamzah.gateway.extension.ExecutionContext;
import java.util.Map;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * SOAP over HTTP (beans {@code soapCodec} for SOAP 1.1 and {@code soap12Codec} for SOAP 1.2).
 * <ul>
 *   <li>Request: each field of the body JSON becomes an element inside {@code <soapenv:Body>}, using the
 *       {@link XmlJson} rules, e.g. {@code {"ns:InquiryRequest":{"@xmlns:ns":"urn:bank","ns:accountNo":"1"}}}.
 *       Set the {@code SOAPAction} header with a {@code HEADER} mapping rule; for SOAP 1.2 it is moved into the
 *       Content-Type {@code action} parameter.</li>
 *   <li>Response: the children of {@code <Body>} as JSON, e.g. {@code {"InquiryResponse":{...}}} or
 *       {@code {"Fault":{"faultcode":"...","faultstring":"..."}}}. The SOAP Header is ignored.</li>
 * </ul>
 * To send a SOAP Header (credentials, WS-Security), subclass and override {@link #header(ExecutionContext)}.
 */
public class SoapCodec implements BodyCodec {

    public static final String SOAP_11_BEAN_NAME = "soapCodec";
    public static final String SOAP_12_BEAN_NAME = "soap12Codec";

    public enum Version {
        SOAP_1_1("http://schemas.xmlsoap.org/soap/envelope/", "text/xml; charset=UTF-8"),
        SOAP_1_2("http://www.w3.org/2003/05/soap-envelope", "application/soap+xml; charset=UTF-8");

        final String namespace;
        final String contentType;

        Version(String namespace, String contentType) {
            this.namespace = namespace;
            this.contentType = contentType;
        }
    }

    private final Version version;

    public SoapCodec(Version version) {
        this.version = version;
    }

    public static SoapCodec soap11() {
        return new SoapCodec(Version.SOAP_1_1);
    }

    public static SoapCodec soap12() {
        return new SoapCodec(Version.SOAP_1_2);
    }

    @Override
    public String contentType() {
        return version.contentType;
    }

    /**
     * Elements to put in the SOAP Header, as a JSON object in the {@link XmlJson} form; null (the default) for none.
     */
    protected JsonNode header(ExecutionContext ctx) {
        return null;
    }

    @Override
    public String encode(JsonNode body, Map<String, String> headers, ExecutionContext ctx) {
        if (!body.isObject()) {
            throw new IllegalArgumentException("SOAP body must be a JSON object (one field per Body element)");
        }
        if (version == Version.SOAP_1_2) {
            String action = headers.remove("SOAPAction");
            if (action != null) {
                headers.put("Content-Type", contentType() + "; action=\"" + action.replace("\"", "") + "\"");
            }
        }
        StringBuilder sb = new StringBuilder(XmlJson.DECLARATION)
                .append("<soapenv:Envelope xmlns:soapenv=\"").append(version.namespace).append("\">");
        JsonNode header = header(ctx);
        if (header != null && !header.isEmpty()) {
            sb.append("<soapenv:Header>");
            XmlJson.appendElements(sb, header);
            sb.append("</soapenv:Header>");
        }
        sb.append("<soapenv:Body>");
        XmlJson.appendElements(sb, body);
        return sb.append("</soapenv:Body></soapenv:Envelope>").toString();
    }

    @Override
    public JsonNode decode(String body, Map<String, String> headers) {
        Element envelope = XmlJson.parse(body);
        if (!isSoap(envelope, "Envelope")) {
            throw new IllegalArgumentException("Response is not a " + label() + " Envelope");
        }
        for (Node n = envelope.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && isSoap(e, "Body")) {
                JsonNode content = XmlJson.toJson(e);
                return content.isObject() ? content : JsonNodeFactory.instance.objectNode();
            }
        }
        throw new IllegalArgumentException(label() + " Envelope has no Body");
    }

    private boolean isSoap(Element e, String localName) {
        return localName.equals(e.getLocalName()) && version.namespace.equals(e.getNamespaceURI());
    }

    private String label() {
        return version == Version.SOAP_1_1 ? "SOAP 1.1" : "SOAP 1.2";
    }

    /** For subclasses building a header: a fresh object node. */
    protected static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }
}
