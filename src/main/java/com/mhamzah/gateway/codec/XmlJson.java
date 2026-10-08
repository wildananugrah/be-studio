package com.mhamzah.gateway.codec;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Converts between JSON (what mapping rules build and read) and XML (what an XML / SOAP downstream speaks).
 * <pre>
 *   JSON                                         XML
 *   {"Req":{"no":"1","amt":10}}                  &lt;Req>&lt;no>1&lt;/no>&lt;amt>10&lt;/amt>&lt;/Req>
 *   {"L":{"item":[{"id":1},{"id":2}]}}           &lt;L>&lt;item>&lt;id>1&lt;/id>&lt;/item>&lt;item>&lt;id>2&lt;/id>&lt;/item>&lt;/L>
 *   {"amt":{"@cur":"IDR","#text":"10"}}          &lt;amt cur="IDR">10&lt;/amt>
 *   {"ns:Req":{"@xmlns:ns":"urn:bank"}}          &lt;ns:Req xmlns:ns="urn:bank"/>
 *   {"a":null}                                   &lt;a/>
 * </pre>
 * XML to JSON uses local names (namespace prefixes are dropped), keeps every value as text, turns repeated
 * elements into arrays (a single element stays an object), and maps {@code xsi:nil="true"} to null.
 * DOCTYPE declarations are rejected, so external entities (XXE) can never be resolved.
 */
public final class XmlJson {

    public static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final String XSI = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;
    private static final String NAME_PART = "[A-Za-z_][A-Za-z0-9._\\-]*";
    private static final Pattern NAME = Pattern.compile(NAME_PART + "(:" + NAME_PART + ")?");
    private static final DocumentBuilderFactory FACTORY = secureFactory();

    private XmlJson() {}

    /** XML for a JSON object with exactly one field, the root element. No XML declaration is written. */
    public static String toXml(JsonNode root) {
        if (root == null || !root.isObject() || root.size() != 1) {
            throw new IllegalArgumentException("XML body must be a JSON object with exactly one field (the root element)");
        }
        StringBuilder sb = new StringBuilder();
        appendElements(sb, root);
        return sb.toString();
    }

    /** Appends one element (or repeated elements, for an array) per field of {@code fields}. */
    public static void appendElements(StringBuilder sb, JsonNode fields) {
        for (Map.Entry<String, JsonNode> field : fields.properties()) {
            if (field.getKey().startsWith("@") || field.getKey().equals("#text")) {
                throw new IllegalArgumentException("'" + field.getKey() + "' is only allowed inside an element");
            }
            appendField(sb, field.getKey(), field.getValue());
        }
    }

    private static void appendField(StringBuilder sb, String name, JsonNode value) {
        if (value.isArray()) {
            for (JsonNode item : value) {
                if (item.isArray()) {
                    throw new IllegalArgumentException("'" + name + "' is a nested array, which XML cannot represent");
                }
                appendElement(sb, name, item);
            }
        } else {
            appendElement(sb, name, value);
        }
    }

    private static void appendElement(StringBuilder sb, String name, JsonNode value) {
        checkName(name);
        sb.append('<').append(name);
        if (value == null || value.isNull() || value.isObject() && value.isEmpty()) {
            sb.append("/>");
            return;
        }
        if (!value.isObject()) {
            sb.append('>');
            escape(sb, scalar(value), false);
            sb.append("</").append(name).append('>');
            return;
        }
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, JsonNode> field : value.properties()) {
            String key = field.getKey();
            if (key.startsWith("@")) {
                checkName(key.substring(1));
                sb.append(' ').append(key, 1, key.length()).append("=\"");
                escape(sb, scalar(field.getValue()), true);
                sb.append('"');
            } else if (key.equals("#text")) {
                escape(content, scalar(field.getValue()), false);
            } else {
                appendField(content, key, field.getValue());
            }
        }
        if (content.isEmpty()) {
            sb.append("/>");
        } else {
            sb.append('>').append(content).append("</").append(name).append('>');
        }
    }

    private static String scalar(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isContainer()) {
            throw new IllegalArgumentException("an attribute or #text value must be a scalar, not " + value);
        }
        return value.isBigDecimal() ? value.decimalValue().toPlainString() : value.asString();
    }

    private static void checkName(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("'" + name + "' is not a valid XML name");
        }
    }

    private static void escape(StringBuilder sb, String text, boolean attribute) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append(attribute ? "&quot;" : "\"");
                case '\n', '\r', '\t' -> sb.append(attribute ? "&#" + (int) c + ";" : String.valueOf(c));
                default -> sb.append(c);
            }
        }
    }

    /** JSON for an XML document: {@code {"<root local name>": <root value>}}. */
    public static JsonNode toJson(String xml) {
        Element root = parse(xml);
        ObjectNode out = F.objectNode();
        out.set(root.getLocalName(), toJson(root));
        return out;
    }

    /** The document element of {@code xml}; throws {@link IllegalArgumentException} when it is not well-formed XML. */
    public static Element parse(String xml) {
        try {
            DocumentBuilder builder = FACTORY.newDocumentBuilder();
            builder.setErrorHandler(THROWING);
            return builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
        } catch (SAXException | IOException | ParserConfigurationException e) {
            throw new IllegalArgumentException("Not valid XML: " + e.getMessage(), e);
        }
    }

    /** JSON value of one element (see the class comment for the rules). */
    public static JsonNode toJson(Element e) {
        if ("true".equals(e.getAttributeNS(XSI, "nil"))) {
            return F.nullNode();
        }
        ObjectNode obj = F.objectNode();
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String ns = a.getNamespaceURI();
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(ns) && !XSI.equals(ns)) {
                obj.put("@" + a.getLocalName(), a.getValue());
            }
        }
        Map<String, List<JsonNode>> children = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder();
        NodeList nodes = e.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            switch (n.getNodeType()) {
                case Node.ELEMENT_NODE -> children.computeIfAbsent(n.getLocalName(), k -> new ArrayList<>())
                        .add(toJson((Element) n));
                case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> text.append(n.getNodeValue());
                default -> { } // comments, processing instructions
            }
        }
        if (obj.isEmpty() && children.isEmpty()) {
            return F.stringNode(text.toString());
        }
        if (!text.toString().isBlank()) {
            obj.put("#text", children.isEmpty() ? text.toString() : text.toString().strip());
        }
        children.forEach((name, values) -> {
            if (values.size() == 1) {
                obj.set(name, values.getFirst());
            } else {
                ArrayNode arr = obj.putArray(name);
                values.forEach(arr::add);
            }
        });
        return obj;
    }

    private static final ErrorHandler THROWING = new ErrorHandler() {
        @Override
        public void warning(SAXParseException e) {}

        @Override
        public void error(SAXParseException e) throws SAXException {
            throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    };

    private static DocumentBuilderFactory secureFactory() {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        try {
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML parser does not support secure processing", e);
        }
        return f;
    }
}
