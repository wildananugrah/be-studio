package com.mhamzah.gateway.studio.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.studio.testing.TestReport.Block;
import com.mhamzah.gateway.studio.testing.TestReport.Contents;
import com.mhamzah.gateway.studio.testing.TestReport.Heading;
import com.mhamzah.gateway.studio.testing.TestReport.PageBreak;
import com.mhamzah.gateway.studio.testing.TestReport.Paragraph;
import com.mhamzah.gateway.studio.testing.TestReport.Title;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ReportRenderersTest {

    /** Contents, then two sections on their own pages: the contents link to them. */
    private static final List<Block> BLOCKS = List.of(
            new Title("Spec", "2 endpoints"),
            new Heading(1, "Contents"),
            new Contents(List.of(new Contents.Entry(1, "1. First", "app_1"), new Contents.Entry(2, "1.1 Second", "ep_1_1"))),
            new PageBreak(),
            new Heading(1, "1. First", "app_1"),
            new Paragraph("text"),
            new PageBreak(),
            new Heading(2, "1.1 Second", "ep_1_1"),
            new Paragraph("text"));

    @Test
    void markdownContentsLinkToAnchors() {
        String md = ReportRenderers.markdown(BLOCKS);
        assertThat(md).contains("- [1. First](#app_1)\n  - [1.1 Second](#ep_1_1)\n", "<a id=\"ep_1_1\"></a>\n\n### 1.1 Second");
    }

    @Test
    void pdfContentsShowPagesLinkAndBecomeBookmarks() throws Exception {
        byte[] pdf = ReportRenderers.render(BLOCKS, ReportRenderers.Format.PDF);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(3);
            PDFTextStripper text = new PDFTextStripper();
            text.setStartPage(1);
            text.setEndPage(1);
            // the contents lines end with the pages their headings landed on
            assertThat(text.getText(doc)).containsPattern("1\\. First \\.+ ?2").containsPattern("1\\.1 Second \\.+ ?3");
            assertThat(doc.getPage(0).getAnnotations()).hasSize(2);
            assertThat(doc.getDocumentCatalog().getDocumentOutline().getFirstChild().getTitle()).isEqualTo("1. First");
            assertThat(doc.getDocumentCatalog().getDocumentOutline().getFirstChild().getFirstChild().getTitle())
                    .isEqualTo("1.1 Second");
        }
    }

    @Test
    void wordContentsLinkToBookmarksWithPageFields() throws Exception {
        String xml = entry(ReportRenderers.render(BLOCKS, ReportRenderers.Format.DOCX), "word/document.xml");
        assertThat(xml).contains("w:anchor=\"ep_1_1\"", "w:name=\"ep_1_1\"", "PAGEREF ep_1_1 \\h", "w:outlineLvl");
    }

    private static String entry(byte[] zip, String name) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                if (e.getName().equals(name)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError(name + " not in the file");
    }
}
