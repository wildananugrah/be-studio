package com.mhamzah.gateway.studio.testing;

import com.mhamzah.gateway.studio.testing.TestReport.Block;
import com.mhamzah.gateway.studio.testing.TestReport.Code;
import com.mhamzah.gateway.studio.testing.TestReport.Fields;
import com.mhamzah.gateway.studio.testing.TestReport.Heading;
import com.mhamzah.gateway.studio.testing.TestReport.PageBreak;
import com.mhamzah.gateway.studio.testing.TestReport.Paragraph;
import com.mhamzah.gateway.studio.testing.TestReport.Table;
import com.mhamzah.gateway.studio.testing.TestReport.Title;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.TableRowAlign;
import org.apache.poi.xwpf.usermodel.TableWidthType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGrid;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblLayoutType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd;

/** Renders {@link TestReport} blocks as Markdown, Word (.docx) or PDF. */
public final class ReportRenderers {

    public enum Format {
        MD("text/markdown; charset=utf-8", "md"),
        DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
        PDF("application/pdf", "pdf");

        public final String contentType;
        public final String extension;

        Format(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }
    }

    private ReportRenderers() {}

    public static byte[] render(List<Block> blocks, Format format) {
        try {
            return switch (format) {
                case MD -> markdown(blocks).getBytes(StandardCharsets.UTF_8);
                case DOCX -> docx(blocks);
                case PDF -> new Pdf().render(blocks);
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- Markdown

    static String markdown(List<Block> blocks) {
        StringBuilder b = new StringBuilder();
        for (Block block : blocks) {
            switch (block) {
                case Title t -> b.append("# ").append(t.text()).append("\n\n_").append(t.subtitle()).append("_\n\n");
                case Heading h -> b.append("#".repeat(h.level() + 1)).append(' ').append(h.text()).append("\n\n");
                case Paragraph p -> b.append(p.text()).append("\n\n");
                case Fields f -> {
                    b.append("| Field | Value |\n|---|---|\n");
                    f.rows().forEach(r -> b.append("| **").append(cell(r[0])).append("** | ").append(cell(r[1])).append(" |\n"));
                    b.append('\n');
                }
                case Table t -> {
                    b.append('|');
                    t.header().forEach(h -> b.append(' ').append(cell(h)).append(" |"));
                    b.append("\n|").append("---|".repeat(t.header().size())).append('\n');
                    for (List<String> row : t.rows()) {
                        b.append('|');
                        row.forEach(c -> b.append(' ').append(cell(c)).append(" |"));
                        b.append('\n');
                    }
                    b.append('\n');
                }
                case Code c -> {
                    String fence = c.text().contains("```") ? "~~~~" : "```";
                    b.append(fence).append('\n').append(c.text()).append('\n').append(fence).append("\n\n");
                }
                case PageBreak p -> b.append("---\n\n");
            }
        }
        return b.toString();
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", "<br>");
    }

    // ---------------------------------------------------------------- Word

    private static final String DOCX_FONT = "Arial";
    private static final String DOCX_MONO = "Courier New";
    /** A4 in twips, 1000-twip margins: 11906 - 2 * 1000. */
    private static final int DOCX_TEXT_WIDTH = 9906;

    static byte[] docx(List<Block> blocks) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CTSectPr sect = doc.getDocument().getBody().addNewSectPr();
            CTPageSz pageSize = sect.addNewPgSz();
            pageSize.setW(BigInteger.valueOf(11906));
            pageSize.setH(BigInteger.valueOf(16838));
            CTPageMar mar = sect.addNewPgMar();
            BigInteger margin = BigInteger.valueOf(1000); // twips, about 1.76 cm
            mar.setLeft(margin);
            mar.setRight(margin);
            mar.setTop(margin);
            mar.setBottom(margin);
            boolean breakBefore = false;
            for (Block block : blocks) {
                switch (block) {
                    case Title t -> {
                        run(doc.createParagraph(), t.text(), true, 20, null, false);
                        XWPFParagraph p = doc.createParagraph();
                        p.setSpacingAfter(240);
                        run(p, t.subtitle(), false, 11, "6A6D75", false);
                    }
                    case Heading h -> {
                        XWPFParagraph p = doc.createParagraph();
                        p.setPageBreak(breakBefore);
                        breakBefore = false;
                        p.setSpacingBefore(h.level() == 1 ? 240 : 180);
                        p.setSpacingAfter(80);
                        p.setKeepNext(true);
                        run(p, h.text(), true, h.level() == 1 ? 15 : h.level() == 2 ? 12.5 : 11, h.level() == 1 ? null : "3E4047", false);
                    }
                    case Paragraph p -> run(doc.createParagraph(), p.text(), false, 10, null, false);
                    case Fields f -> {
                        XWPFTable table = doc.createTable(f.rows().size(), 2);
                        int[] widths = {DOCX_TEXT_WIDTH * 28 / 100, DOCX_TEXT_WIDTH * 72 / 100};
                        fixedWidth(table, widths);
                        for (int i = 0; i < f.rows().size(); i++) {
                            XWPFTableRow row = table.getRow(i);
                            cell(row.getCell(0), f.rows().get(i)[0], true, "F4F3EF");
                            cell(row.getCell(1), f.rows().get(i)[1], false, null);
                            setWidths(row, widths);
                        }
                        doc.createParagraph();
                    }
                    case Table t -> {
                        XWPFTable table = doc.createTable(t.rows().size() + 1, t.header().size());
                        int[] widths = columnWidths(t);
                        fixedWidth(table, widths);
                        table.getRows().forEach(row -> setWidths(row, widths));
                        for (int c = 0; c < t.header().size(); c++) {
                            cell(table.getRow(0).getCell(c), t.header().get(c), true, "E4E1D8");
                        }
                        table.getRow(0).setRepeatHeader(true);
                        for (int r = 0; r < t.rows().size(); r++) {
                            for (int c = 0; c < t.header().size(); c++) {
                                cell(table.getRow(r + 1).getCell(c), t.rows().get(r).get(c), false, null);
                            }
                        }
                        doc.createParagraph();
                    }
                    case Code c -> {
                        XWPFParagraph p = doc.createParagraph();
                        shade(p, "F4F3EF");
                        p.setSpacingAfter(120);
                        XWPFRun r = p.createRun();
                        r.setFontFamily(DOCX_MONO);
                        r.setFontSize(8);
                        String[] lines = c.text().split("\n", -1);
                        for (int i = 0; i < lines.length; i++) {
                            if (i > 0) {
                                r.addBreak();
                            }
                            r.setText(lines[i], i);
                        }
                    }
                    case PageBreak p -> breakBefore = true;
                }
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static void fixedWidth(XWPFTable table, int[] widths) {
        int total = 0;
        for (int w : widths) {
            total += w;
        }
        table.setWidthType(TableWidthType.DXA);
        table.setWidth(String.valueOf(total));
        table.setTableAlignment(TableRowAlign.LEFT);
        CTTblPr pr = table.getCTTbl().getTblPr();
        (pr.isSetTblLayout() ? pr.getTblLayout() : pr.addNewTblLayout()).setType(STTblLayoutType.FIXED);
        CTTblGrid grid = table.getCTTbl().getTblGrid() != null ? table.getCTTbl().getTblGrid() : table.getCTTbl().addNewTblGrid();
        while (grid.sizeOfGridColArray() > 0) {
            grid.removeGridCol(0);
        }
        for (int w : widths) {
            grid.addNewGridCol().setW(BigInteger.valueOf(w));
        }
    }

    private static void setWidths(XWPFTableRow row, int[] widths) {
        for (int c = 0; c < widths.length && c < row.getTableCells().size(); c++) {
            row.getCell(c).setWidthType(TableWidthType.DXA);
            row.getCell(c).setWidth(String.valueOf(widths[c]));
        }
    }

    /** Column widths in proportion to their longest text (capped), filling the text width. */
    private static int[] columnWidths(Table t) {
        int cols = t.header().size();
        double[] natural = new double[cols];
        for (int c = 0; c < cols; c++) {
            natural[c] = Math.max(3, t.header().get(c).length());
            for (List<String> r : t.rows()) {
                natural[c] = Math.max(natural[c], r.get(c) == null ? 0 : Math.min(40, r.get(c).length()));
            }
        }
        double sum = 0;
        for (double n : natural) {
            sum += n;
        }
        int[] out = new int[cols];
        for (int c = 0; c < cols; c++) {
            out[c] = (int) (natural[c] / sum * DOCX_TEXT_WIDTH);
        }
        return out;
    }

    private static void run(XWPFParagraph p, String text, boolean bold, double size, String color, boolean mono) {
        XWPFRun r = p.createRun();
        r.setText(text == null ? "" : text);
        r.setBold(bold);
        r.setFontSize(size);
        r.setFontFamily(mono ? DOCX_MONO : DOCX_FONT);
        if (color != null) {
            r.setColor(color);
        }
    }

    private static void cell(XWPFTableCell cell, String text, boolean bold, String fill) {
        XWPFParagraph p = cell.getParagraphs().getFirst();
        p.setAlignment(ParagraphAlignment.LEFT);
        String[] lines = (text == null ? "" : text).split("\n", -1);
        XWPFRun r = p.createRun();
        r.setBold(bold);
        r.setFontSize(9);
        r.setFontFamily(DOCX_FONT);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                r.addBreak();
            }
            r.setText(lines[i], i);
        }
        if (fill != null) {
            cell.setColor(fill);
        }
    }

    private static void shade(XWPFParagraph p, String fill) {
        CTPPr ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        CTShd shd = ppr.isSetShd() ? ppr.getShd() : ppr.addNewShd();
        shd.setVal(STShd.CLEAR);
        shd.setColor("auto");
        shd.setFill(fill);
    }

    // ---------------------------------------------------------------- PDF

    /** A small flowing layout on A4 with the standard 14 fonts (no font files needed). */
    private static final class Pdf {
        private static final float MARGIN = 42;
        private static final PDRectangle SIZE = PDRectangle.A4;
        private static final float WIDTH = SIZE.getWidth() - 2 * MARGIN;
        private static final Color INK = new Color(0x17, 0x18, 0x1C);
        private static final Color MUTED = new Color(0x6A, 0x6D, 0x75);
        private static final Color LINE = new Color(0xD9, 0xD6, 0xCC);
        private static final Color SHADE = new Color(0xF4, 0xF3, 0xEF);
        private static final Color HEAD = new Color(0xE4, 0xE1, 0xD8);

        private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private final PDType1Font mono = new PDType1Font(Standard14Fonts.FontName.COURIER);
        private final Map<String, Boolean> encodable = new HashMap<>();
        private PDDocument doc;
        private PDPage page;
        private PDPageContentStream cs;
        private float y;
        private String footer = "";

        byte[] render(List<Block> blocks) throws IOException {
            try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                doc = d;
                newPage();
                for (Block block : blocks) {
                    switch (block) {
                        case Title t -> {
                            footer = t.text();
                            text(t.text(), bold, 18, INK, 0, 24);
                            text(t.subtitle(), regular, 10, MUTED, 0, 14);
                            y -= 8;
                        }
                        case Heading h -> {
                            float size = h.level() == 1 ? 14 : h.level() == 2 ? 11.5f : 10;
                            ensure(size * 4); // keep a heading with what follows
                            y -= h.level() == 1 ? 6 : 4;
                            text(h.text(), bold, size, h.level() == 1 ? INK : new Color(0x3E, 0x40, 0x47), 0, size * 1.35f);
                            y -= 2;
                        }
                        case Paragraph p -> {
                            text(p.text(), regular, 9.5f, INK, 0, 12.5f);
                            y -= 4;
                        }
                        case Fields f -> table(null, f.rows().stream().map(r -> List.of(r[0], r[1])).toList(), new float[] {0.28f, 0.72f}, true);
                        case Table t -> table(t.header(), t.rows(), null, false);
                        case Code c -> code(c.text());
                        case PageBreak p -> {
                            if (y < SIZE.getHeight() - MARGIN - 1) {
                                newPage();
                            }
                        }
                    }
                }
                cs.close();
                int total = doc.getNumberOfPages();
                for (int i = 0; i < total; i++) {
                    try (PDPageContentStream f = new PDPageContentStream(doc, doc.getPage(i), PDPageContentStream.AppendMode.APPEND, true, true)) {
                        String label = clean(footer, regular) + "   -   page " + (i + 1) + " of " + total;
                        f.beginText();
                        f.setFont(regular, 7.5f);
                        f.setNonStrokingColor(MUTED);
                        f.newLineAtOffset(MARGIN, MARGIN / 2);
                        f.showText(label);
                        f.endText();
                    }
                }
                doc.save(out);
                return out.toByteArray();
            }
        }

        private void newPage() throws IOException {
            if (cs != null) {
                cs.close();
            }
            page = new PDPage(SIZE);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = SIZE.getHeight() - MARGIN;
        }

        private void ensure(float height) throws IOException {
            if (y - height < MARGIN) {
                newPage();
            }
        }

        private void text(String text, PDType1Font font, float size, Color color, float indent, float leading) throws IOException {
            for (String line : wrap(text, font, size, WIDTH - indent)) {
                ensure(leading);
                write(line, font, size, color, MARGIN + indent, y - size);
                y -= leading;
            }
        }

        private void write(String line, PDType1Font font, float size, Color color, float x, float baseline) throws IOException {
            cs.beginText();
            cs.setFont(font, size);
            cs.setNonStrokingColor(color);
            cs.newLineAtOffset(x, baseline);
            cs.showText(line);
            cs.endText();
        }

        private void code(String text) throws IOException {
            float size = 7.5f;
            float leading = 9.5f;
            float pad = 6;
            List<String> lines = new ArrayList<>();
            for (String raw : text.split("\n", -1)) {
                lines.addAll(wrap(raw.replace("\t", "  "), mono, size, WIDTH - 2 * pad));
            }
            ensure(leading + pad);
            y -= 2;
            boolean first = true;
            for (String line : lines) {
                float top = first ? pad : 0;
                if (y - leading - top < MARGIN) {
                    newPage();
                    top = pad;
                }
                cs.setNonStrokingColor(SHADE);
                cs.addRect(MARGIN, y - leading - top, WIDTH, leading + top);
                cs.fill();
                write(line, mono, size, INK, MARGIN + pad, y - top - size);
                y -= leading + top;
                first = false;
            }
            cs.setNonStrokingColor(SHADE);
            cs.addRect(MARGIN, y - pad, WIDTH, pad);
            cs.fill();
            y -= pad + 8;
        }

        private void table(List<String> header, List<List<String>> rows, float[] fractions, boolean labelColumn) throws IOException {
            int cols = header != null ? header.size() : rows.isEmpty() ? 0 : rows.getFirst().size();
            if (cols == 0) {
                return;
            }
            float size = 8;
            float leading = 10;
            float pad = 4;
            float[] widths = new float[cols];
            if (fractions != null) {
                for (int c = 0; c < cols; c++) {
                    widths[c] = WIDTH * fractions[c];
                }
            } else {
                float[] natural = new float[cols];
                for (int c = 0; c < cols; c++) {
                    natural[c] = Math.min(220, Math.max(28, width(header.get(c), bold, size) + 2 * pad));
                    for (List<String> r : rows) {
                        natural[c] = Math.min(220, Math.max(natural[c], width(r.get(c), regular, size) + 2 * pad));
                    }
                }
                float sum = 0;
                for (float n : natural) {
                    sum += n;
                }
                for (int c = 0; c < cols; c++) {
                    widths[c] = natural[c] / sum * WIDTH;
                }
            }
            if (header != null) {
                row(header, widths, size, leading, pad, true, HEAD);
            }
            for (List<String> r : rows) {
                if (y - leading - 2 * pad < MARGIN) {
                    newPage();
                    if (header != null) {
                        row(header, widths, size, leading, pad, true, HEAD);
                    }
                }
                row(r, widths, size, leading, pad, false, labelColumn ? SHADE : null);
            }
            y -= 10;
        }

        /** {@code firstFill}: background of the first cell only (label column), or of every cell when {@code bolder}. */
        private void row(List<String> cells, float[] widths, float size, float leading, float pad, boolean bolder, Color firstFill) throws IOException {
            List<List<String>> wrapped = new ArrayList<>();
            int lines = 1;
            for (int c = 0; c < cells.size(); c++) {
                PDType1Font font = bolder || (firstFill != null && c == 0) ? bold : regular;
                List<String> w = wrap(cells.get(c), font, size, widths[c] - 2 * pad);
                wrapped.add(w);
                lines = Math.max(lines, w.size());
            }
            float height = lines * leading + 2 * pad;
            if (y - height < MARGIN) {
                newPage();
            }
            float x = MARGIN;
            for (int c = 0; c < cells.size(); c++) {
                Color fill = bolder ? firstFill : c == 0 ? firstFill : null;
                if (fill != null) {
                    cs.setNonStrokingColor(fill);
                    cs.addRect(x, y - height, widths[c], height);
                    cs.fill();
                }
                cs.setStrokingColor(LINE);
                cs.setLineWidth(0.5f);
                cs.addRect(x, y - height, widths[c], height);
                cs.stroke();
                PDType1Font font = bolder || (firstFill != null && c == 0) ? bold : regular;
                float ly = y - pad - size;
                for (String line : wrapped.get(c)) {
                    write(line, font, size, INK, x + pad, ly);
                    ly -= leading;
                }
                x += widths[c];
            }
            y -= height;
        }

        private float width(String s, PDType1Font font, float size) throws IOException {
            return font.getStringWidth(clean(s == null ? "" : s, font)) / 1000 * size;
        }

        /** Word-wraps {@code text} (and breaks words longer than a line); keeps explicit newlines. */
        private List<String> wrap(String text, PDType1Font font, float size, float max) throws IOException {
            List<String> out = new ArrayList<>();
            for (String para : clean(text == null ? "" : text, font).split("\n", -1)) {
                if (para.isEmpty()) {
                    out.add("");
                    continue;
                }
                StringBuilder line = new StringBuilder();
                int i = 0;
                while (i < para.length()) {
                    int j = i;
                    while (j < para.length() && para.charAt(j) != ' ') {
                        j++;
                    }
                    String word = para.substring(i, Math.min(j + 1, para.length()));
                    if (width(line + word, font, size) <= max) {
                        line.append(word);
                    } else {
                        if (!line.isEmpty()) {
                            out.add(line.toString().stripTrailing());
                            line.setLength(0);
                        }
                        while (width(word, font, size) > max) {
                            int k = word.length();
                            while (k > 1 && width(word.substring(0, k), font, size) > max) {
                                k--;
                            }
                            out.add(word.substring(0, k));
                            word = word.substring(k);
                        }
                        line.append(word);
                    }
                    i = j + 1;
                }
                if (!line.isEmpty() || out.isEmpty()) {
                    out.add(line.toString().stripTrailing());
                }
            }
            return out;
        }

        /** The standard fonts only know WinAnsi; anything else becomes a close ASCII stand-in or '?'. */
        private String clean(String s, PDType1Font font) {
            StringBuilder b = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                String c = switch (ch) {
                    case '\t' -> "  ";
                    case '\r' -> "";
                    case '\u2192' -> "->";
                    case '\u2190' -> "<-";
                    case '\u2264' -> "<=";
                    case '\u2265' -> ">=";
                    default -> String.valueOf(ch);
                };
                if (c.length() == 1 && ch != '\n') {
                    String key = font.getName() + c;
                    Boolean ok = encodable.get(key);
                    if (ok == null) {
                        try {
                            font.encode(c);
                            ok = true;
                        } catch (IOException | IllegalArgumentException e) {
                            ok = false;
                        }
                        encodable.put(key, ok);
                    }
                    if (!ok) {
                        c = "?";
                    }
                }
                b.append(c);
            }
            return b.toString();
        }
    }
}
