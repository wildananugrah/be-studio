package com.mhamzah.gateway.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.mapping.JsonValues;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class CodecsTest {

    private static final ExecutionContext CTX = new ExecutionContext("F", "corr-1", Map.of(), null);

    private static JsonNode json(String text) {
        return JsonValues.MAPPER.readTree(text);
    }

    private static Map<String, String> headers() {
        return new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    @Nested
    class XmlCodecTest {

        private final XmlCodec codec = new XmlCodec();

        @Test
        void encodesWithDeclaration() {
            String xml = codec.encode(json("{\"Inquiry\":{\"accountNo\":\"123\"}}"), headers(), CTX);

            assertThat(xml).isEqualTo("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Inquiry><accountNo>123</accountNo></Inquiry>");
            assertThat(codec.contentType()).isEqualTo("application/xml; charset=UTF-8");
            assertThat(codec.accept()).isEqualTo("application/xml, text/xml");
        }

        @Test
        void decodesToJson() {
            JsonNode j = codec.decode("<InquiryResponse><name>BUDI</name></InquiryResponse>", Map.of());

            assertThat(j.toString()).isEqualTo("{\"InquiryResponse\":{\"name\":\"BUDI\"}}");
        }
    }

    @Nested
    class Soap11Test {

        private final SoapCodec codec = SoapCodec.soap11();

        @Test
        void wrapsBodyInEnvelope() {
            String xml = codec.encode(json("{\"ns:InquiryRequest\":{\"@xmlns:ns\":\"urn:bank\",\"ns:accountNo\":\"1\"}}"),
                    headers(), CTX);

            assertThat(xml).isEqualTo("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body>"
                    + "<ns:InquiryRequest xmlns:ns=\"urn:bank\"><ns:accountNo>1</ns:accountNo></ns:InquiryRequest>"
                    + "</soapenv:Body></soapenv:Envelope>");
            assertThat(codec.contentType()).isEqualTo("text/xml; charset=UTF-8");
        }

        @Test
        void decodesBodyContent() {
            JsonNode j = codec.decode("""
                    <S:Envelope xmlns:S="http://schemas.xmlsoap.org/soap/envelope/">
                      <S:Header><x>ignored</x></S:Header>
                      <S:Body>
                        <ns2:InquiryResponse xmlns:ns2="urn:bank"><ns2:name>BUDI</ns2:name></ns2:InquiryResponse>
                      </S:Body>
                    </S:Envelope>
                    """, Map.of());

            assertThat(j.toString()).isEqualTo("{\"InquiryResponse\":{\"name\":\"BUDI\"}}");
        }

        @Test
        void decodesFault() {
            JsonNode j = codec.decode("""
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>
                      <soap:Fault><faultcode>soap:Server</faultcode><faultstring>Account not found</faultstring></soap:Fault>
                    </soap:Body></soap:Envelope>
                    """, Map.of());

            assertThat(j.get("Fault").get("faultstring").asString()).isEqualTo("Account not found");
        }

        @Test
        void emptyBodyDecodesToEmptyObject() {
            JsonNode j = codec.decode("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                    + "<soap:Body/></soap:Envelope>", Map.of());

            assertThat(j.toString()).isEqualTo("{}");
        }

        @Test
        void rejectsNonEnvelope() {
            assertThatThrownBy(() -> codec.decode("<InquiryResponse/>", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SOAP 1.1 Envelope");
        }

        @Test
        void rejectsSoap12EnvelopeInSoap11Codec() {
            assertThatThrownBy(() -> codec.decode("<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\">"
                    + "<e:Body/></e:Envelope>", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void subclassCanAddSoapHeader() {
            SoapCodec withHeader = new SoapCodec(SoapCodec.Version.SOAP_1_1) {
                @Override
                protected JsonNode header(ExecutionContext ctx) {
                    return json("{\"auth:Token\":{\"@xmlns:auth\":\"urn:auth\",\"#text\":\"" + ctx.correlationId() + "\"}}");
                }
            };

            String xml = withHeader.encode(json("{\"Ping\":null}"), headers(), CTX);

            assertThat(xml).contains("<soapenv:Header><auth:Token xmlns:auth=\"urn:auth\">corr-1</auth:Token></soapenv:Header>"
                    + "<soapenv:Body><Ping/></soapenv:Body>");
        }
    }

    @Nested
    class Soap12Test {

        private final SoapCodec codec = SoapCodec.soap12();

        @Test
        void usesSoap12NamespaceAndMovesSoapActionIntoContentType() {
            Map<String, String> headers = headers();
            headers.put("SOAPAction", "urn:bank/Inquiry");

            String xml = codec.encode(json("{\"Ping\":null}"), headers, CTX);

            assertThat(xml).contains("<soapenv:Envelope xmlns:soapenv=\"http://www.w3.org/2003/05/soap-envelope\">");
            assertThat(headers).doesNotContainKey("SOAPAction")
                    .containsEntry("Content-Type", "application/soap+xml; charset=UTF-8; action=\"urn:bank/Inquiry\"");
        }

        @Test
        void decodesSoap12Envelope() {
            JsonNode j = codec.decode("<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\">"
                    + "<e:Body><Pong>ok</Pong></e:Body></e:Envelope>", Map.of());

            assertThat(j.toString()).isEqualTo("{\"Pong\":\"ok\"}");
        }
    }
}
