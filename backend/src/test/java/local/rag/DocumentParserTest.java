package local.rag;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class DocumentParserTest {
    final DocumentParser parser=new DocumentParser();
    @Test void unicodeCodePointsNotUtf16UnitsAndExactOverlap() {
        String text="😀".repeat(1550);
        var chunks=parser.parse("doc","txt",text.getBytes(StandardCharsets.UTF_8),800,100);
        assertThat(chunks).hasSize(3);
        assertThat(chunks.stream().map(c -> c.text().codePointCount(0,c.text().length()))).containsExactly(800,800,150);
        assertThat(chunks).allSatisfy(c -> { assertThat(c.pageStart()).isNull(); assertThat(c.index()).isPositive(); });
        assertThat(chunks.get(0).text().substring(1400)).isEqualTo(chunks.get(1).text().substring(0,200));
    }
    @Test void doesNotCreateRedundantTrailingOverlapChunk() {
        assertThat(parser.parse("doc","md","x".repeat(1500).getBytes(StandardCharsets.UTF_8),800,100)).hasSize(2);
    }
    @Test void invalidUtf8FailsRatherThanReplacementCharacters() {
        assertThatThrownBy(() -> parser.parse("d","txt",new byte[]{(byte)0xc3,0x28},800,100))
            .isInstanceOf(ApiException.class).hasMessageContaining("UTF-8");
    }
    @Test void bomRemovedAndHtmlPreservedAsText() {
        var chunks=parser.parse("d","txt","\uFEFF<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8),800,100);
        assertThat(chunks.get(0).text()).isEqualTo("<script>alert(1)</script>");
    }
    @Test void blankAndBinaryRejected() {
        assertThatThrownBy(() -> parser.parse("d","txt"," \n ".getBytes(StandardCharsets.UTF_8),800,100)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> parser.parse("d","txt",new byte[]{65,0,66},800,100)).isInstanceOf(ApiException.class);
    }
    @Test void pageBoundariesDoNotWasteOneChunkPerPage() {
        var data=new DocumentParser.Extracted("a".repeat(300)+"b".repeat(300)+"c".repeat(300),
            List.of(new DocumentParser.PageSpan(0,300,1),new DocumentParser.PageSpan(300,600,2),new DocumentParser.PageSpan(600,900,3)));
        var chunks=parser.split("d",data,800,100);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).pageStart()).isEqualTo(1);
        assertThat(chunks.get(0).pageEnd()).isEqualTo(3);
        assertThat(chunks.get(1).pageStart()).isEqualTo(3);
        assertThat(chunks.get(1).pageEnd()).isEqualTo(3);
    }
    @Test void extractsRealPdfTextAndPageRange() throws Exception {
        byte[] bytes;
        try (PDDocument pdf=new PDDocument(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            for (int i=1;i<=2;i++) {
                PDPage page=new PDPage(); pdf.addPage(page);
                try (PDPageContentStream stream=new PDPageContentStream(pdf,page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                    stream.newLineAtOffset(50,700); stream.showText("Page "+i+" verified source."); stream.endText();
                }
            }
            pdf.save(out); bytes=out.toByteArray();
        }
        var chunks=parser.parse("pdf","pdf",bytes,800,100);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("Page 1", "Page 2");
        assertThat(chunks.get(0).pageStart()).isEqualTo(1);
        assertThat(chunks.get(0).pageEnd()).isEqualTo(2);
    }
    @Test void emptyPdfFailsWithoutPretendingOcr() throws Exception {
        byte[] bytes;
        try (PDDocument pdf=new PDDocument(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage()); pdf.save(out); bytes=out.toByteArray();
        }
        assertThatThrownBy(() -> parser.parse("d","pdf",bytes,800,100)).isInstanceOf(ApiException.class).hasMessageContaining("OCR");
    }
    @Test void invalidParametersCannotInfiniteLoop() {
        assertThatThrownBy(() -> parser.split("d",new DocumentParser.Extracted("abc",List.of()),100,100)).isInstanceOf(IllegalArgumentException.class);
    }
}
