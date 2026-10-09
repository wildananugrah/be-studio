package com.mhamzah.gateway.studio.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.studio.testing.CodeHighlighter.Kind;
import com.mhamzah.gateway.studio.testing.CodeHighlighter.Piece;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CodeHighlighterTest {

    private static String joined(List<Piece> pieces) {
        return pieces.stream().map(Piece::text).collect(Collectors.joining());
    }

    private static List<String> of(List<Piece> pieces, Kind kind) {
        return pieces.stream().filter(p -> p.kind() == kind).map(Piece::text).toList();
    }

    @Test
    void httpMessageWithJsonBody() {
        String text = "POST /api/v1/transfers HTTP/1.1\nContent-Type: application/json\nX-Channel: MOBILE\n\n"
                + "{\n  \"amount\" : 150000.5,\n  \"note\" : \"a \\\"b\\\": c\",\n  \"ok\" : true,\n  \"x\" : null,\n"
                + "  \"list\" : [ -1, \"y\" ]\n}";
        List<Piece> pieces = CodeHighlighter.highlight(text);

        assertThat(joined(pieces)).isEqualTo(text);
        assertThat(of(pieces, Kind.HTTP_LINE)).containsExactly("POST /api/v1/transfers HTTP/1.1");
        assertThat(of(pieces, Kind.HEADER)).containsExactly("Content-Type", "X-Channel");
        assertThat(of(pieces, Kind.KEY)).containsExactly("\"amount\"", "\"note\"", "\"ok\"", "\"x\"", "\"list\"");
        assertThat(of(pieces, Kind.STRING)).containsExactly("\"a \\\"b\\\": c\"", "\"y\"");
        assertThat(of(pieces, Kind.NUMBER)).containsExactly("150000.5", "-1");
        assertThat(of(pieces, Kind.LITERAL)).containsExactly("true", "null");
        assertThat(CodeHighlighter.language(text)).isEqualTo("http");
    }

    @Test
    void soapXmlWithNamespacesAttributesAndComments() {
        String text = "<?xml version=\"1.0\"?>\n<soapenv:Envelope xmlns:soapenv=\"http://x\">"
                + "<!-- note --><q0:amount currency='IDR'>150000.00</q0:amount><empty/></soapenv:Envelope>";
        List<Piece> pieces = CodeHighlighter.highlight(text);

        assertThat(joined(pieces)).isEqualTo(text);
        assertThat(of(pieces, Kind.TAG)).containsExactly("xml", "soapenv:Envelope", "q0:amount", "q0:amount", "empty",
                "soapenv:Envelope");
        assertThat(of(pieces, Kind.ATTR)).containsExactly("version", "xmlns:soapenv", "currency");
        assertThat(of(pieces, Kind.STRING)).containsExactly("\"1.0\"", "\"http://x\"", "'IDR'");
        assertThat(of(pieces, Kind.COMMENT)).containsExactly("<!-- note -->");
        assertThat(of(pieces, Kind.PLAIN)).contains("150000.00");
        assertThat(CodeHighlighter.language(text)).isEqualTo("xml");
    }

    @Test
    void statusLineAndPlainText() {
        String response = "HTTP 422\ncontent-type: text/xml\n\n<a>1</a>";
        assertThat(joined(CodeHighlighter.highlight(response))).isEqualTo(response);
        assertThat(of(CodeHighlighter.highlight(response), Kind.TAG)).containsExactly("a", "a");

        String log = "2026-10-10 INFO step=inquiry status=200";
        assertThat(CodeHighlighter.highlight(log)).containsExactly(new Piece(log, Kind.PLAIN));
        assertThat(CodeHighlighter.language(log)).isEmpty();
        assertThat(CodeHighlighter.language("{\"a\":1}")).isEqualTo("json");
    }
}
