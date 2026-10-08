package com.mhamzah.gateway.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mhamzah.gateway.mapping.JsonValues;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class XmlJsonTest {

    private static JsonNode json(String text) {
        return JsonValues.MAPPER.readTree(text);
    }

    @Test
    void objectBecomesNestedElements() {
        String xml = XmlJson.toXml(json("{\"Inquiry\":{\"accountNo\":\"123\",\"amount\":12500.50,\"vip\":true}}"));

        assertThat(xml).isEqualTo("<Inquiry><accountNo>123</accountNo><amount>12500.50</amount><vip>true</vip></Inquiry>");
    }

    @Test
    void arrayBecomesRepeatedElements() {
        String xml = XmlJson.toXml(json("{\"List\":{\"item\":[{\"id\":1},{\"id\":2}]}}"));

        assertThat(xml).isEqualTo("<List><item><id>1</id></item><item><id>2</id></item></List>");
    }

    @Test
    void atKeysBecomeAttributesAndHashTextBecomesText() {
        String xml = XmlJson.toXml(json(
                "{\"ns:Req\":{\"@xmlns:ns\":\"urn:bank\",\"ns:amount\":{\"@currency\":\"IDR\",\"#text\":\"10\"}}}"));

        assertThat(xml).isEqualTo("<ns:Req xmlns:ns=\"urn:bank\"><ns:amount currency=\"IDR\">10</ns:amount></ns:Req>");
    }

    @Test
    void textAndAttributesAreEscaped() {
        String xml = XmlJson.toXml(json("{\"a\":{\"@q\":\"x\\\"<&\",\"b\":\"1 < 2 & 3 > 0\"}}"));

        assertThat(xml).isEqualTo("<a q=\"x&quot;&lt;&amp;\"><b>1 &lt; 2 &amp; 3 &gt; 0</b></a>");
    }

    @Test
    void nullAndEmptyObjectBecomeEmptyElements() {
        assertThat(XmlJson.toXml(json("{\"a\":{\"b\":null,\"c\":{}}}"))).isEqualTo("<a><b/><c/></a>");
    }

    @Test
    void bigDecimalIsWrittenWithoutExponent() {
        assertThat(XmlJson.toXml(json("{\"a\":1E+3}"))).isEqualTo("<a>1000</a>");
    }

    @Test
    void rootMustBeObjectWithExactlyOneField() {
        assertThatThrownBy(() -> XmlJson.toXml(json("{\"a\":1,\"b\":2}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one");
        assertThatThrownBy(() -> XmlJson.toXml(json("[1]")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one");
    }

    @Test
    void invalidElementNameIsRejected() {
        assertThatThrownBy(() -> XmlJson.toXml(json("{\"a\":{\"1bad name\":1}}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1bad name");
    }

    @Test
    void nestedArraysAreRejected() {
        assertThatThrownBy(() -> XmlJson.toXml(json("{\"a\":{\"b\":[[1]]}}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nested array");
    }

    @Test
    void parsesElementsToJsonUsingLocalNames() {
        JsonNode j = XmlJson.toJson("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ns2:InquiryResponse xmlns:ns2="urn:bank">
                  <ns2:name>BUDI</ns2:name>
                  <ns2:balance>100.10</ns2:balance>
                </ns2:InquiryResponse>
                """);

        assertThat(j.toString()).isEqualTo("{\"InquiryResponse\":{\"name\":\"BUDI\",\"balance\":\"100.10\"}}");
    }

    @Test
    void repeatedElementsBecomeArrays() {
        JsonNode j = XmlJson.toJson("<r><tx><id>1</id></tx><tx><id>2</id></tx><one>x</one></r>");

        assertThat(j.toString()).isEqualTo("{\"r\":{\"tx\":[{\"id\":\"1\"},{\"id\":\"2\"}],\"one\":\"x\"}}");
    }

    @Test
    void attributesBecomeAtKeysAndNamespaceDeclarationsAreDropped() {
        JsonNode j = XmlJson.toJson("<r xmlns=\"urn:x\" xmlns:a=\"urn:a\" id=\"7\"><amt cur=\"IDR\">10</amt><e/></r>");

        assertThat(j.toString()).isEqualTo("{\"r\":{\"@id\":\"7\",\"amt\":{\"@cur\":\"IDR\",\"#text\":\"10\"},\"e\":\"\"}}");
    }

    @Test
    void xsiNilBecomesNull() {
        JsonNode j = XmlJson.toJson(
                "<r xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><a xsi:nil=\"true\"/></r>");

        assertThat(j.toString()).isEqualTo("{\"r\":{\"a\":null}}");
    }

    @Test
    void cdataIsText() {
        assertThat(XmlJson.toJson("<r><![CDATA[a < b]]></r>").toString()).isEqualTo("{\"r\":\"a < b\"}");
    }

    @Test
    void doctypeIsRejectedToPreventXxe() {
        assertThatThrownBy(() -> XmlJson.toJson("""
                <?xml version="1.0"?>
                <!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <r>&x;</r>
                """)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedXmlIsRejected() {
        assertThatThrownBy(() -> XmlJson.toJson("{\"not\":\"xml\"}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundTrip() {
        String xml = "<a x=\"1\"><b>t</b><c><d>1</d></c><c><d>2</d></c></a>";

        assertThat(XmlJson.toXml(XmlJson.toJson(xml))).isEqualTo(xml);
    }
}
