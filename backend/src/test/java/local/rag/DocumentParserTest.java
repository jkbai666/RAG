package local.rag;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class DocumentParserTest {
    final DocumentParser parser = new DocumentParser();

    @Test void differentLengthParagraphsRemainIndependentWithExactOriginalOverlap() {
        String first = "第一段内容。\n\n";
        String second = "第二段包含更多正文，保留独立的自然段，而不是填满固定长度。\n\n";
        String third = "最后一段。";
        String text = first+second+third;
        var chunks = parse(text,800,100);
        assertRanges(text,chunks,800,100,first.length(),first.length()+second.length(),text.length());
        assertThat(chunks.stream().map(c -> points(c.text()))).containsExactly(points(first),points(first+second),points(text));
    }
    @Test void headingsStayWithTheirOwnParagraphAndNeverPackAcrossSections() {
        String first = "# 第一节\n\n这里是第一节。\n\n";
        String second = "## 第二节\n这里是第二节，正文长度不同。";
        var chunks = parse(first+second,800,0);
        assertRanges(first+second,chunks,800,0,first.length(),first.length()+second.length());
        assertThat(chunks.get(0).text()).doesNotContain("第二节");
        assertThat(chunks.get(1).text()).doesNotContain("第一节");
    }
    @Test void setextHeadingsAreNotSeparatedFromTheirText() {
        String first = "First section\n=============\n\nFirst body.\n\n";
        String second = "Second section\n--------------\nSecond body.";
        assertRanges(first+second,parse(first+second,800,0),800,0,first.length(),first.length()+second.length());
    }
    @Test void markdownListsKeepItemsWrappedLinesAndBlankLinesTogether() {
        String intro = "# 采购\n采购说明。\n\n";
        String list = "- 第一项。\n  第一项的延续，不能拆成新段。\n\n- 第二项。\n  - 嵌套条目。\n\n1. 第三项。\n   第三项延续。\n\n";
        String tail = "列表后的正文。";
        String text = intro+list+tail;
        assertRanges(text,parse(text,800,8),800,8,intro.length(),intro.length()+list.length(),text.length());
    }
    @Test void oversizedListSplitsAtWholeItemsNotWrappedLinesOrCharacters() {
        String one = "- "+"a".repeat(30)+"\n";
        String two = "- "+"b".repeat(30)+"\n";
        String three = "- "+"c".repeat(30)+"\n";
        String four = "- "+"d".repeat(30)+"\n";
        String text = one+two+three+four;
        assertRanges(text,parse(text,80,10),80,10,one.length()+two.length(),text.length());
    }
    @Test void fencedCodeIsAtomicDespiteBlankLinesFakeHeadingsListsAndPunctuation() {
        String intro = "代码说明。\n\n";
        String code = "```java\nString s = \"Not a sentence.\";\n\n# not a heading\n- not a list\nSystem.out.println(s);\n```\n";
        String tail = "后续说明。";
        String text = intro+code+tail;
        assertRanges(text,parse(text,800,4),800,4,intro.length(),intro.length()+code.length(),text.length());
        assertThat(parse(text,800,0).get(1).text()).isEqualTo(code);
    }
    @Test void tildeFenceRequiresMatchingMarkerAndAtLeastOpeningLength() {
        String code = "~~~~text\nfirst.\n\n~~~\n```\n# literal\nlast.\n~~~~\n";
        String text = code+"正文。";
        assertRanges(text,parse(text,800,0),800,0,code.length(),text.length());
    }
    @Test void oversizedCodeFailsExplicitlyInsteadOfCuttingCodeCharacters() {
        assertUnsplit(() -> parse("```\n"+"x".repeat(100)+"\n```",50,5));
    }
    @Test void unterminatedFenceIsPreservedAsCodeToEndOfFile() {
        String text = "```java\nline.\n\n# literal heading\n- literal list";
        assertRanges(text,parse(text,800,100),800,100,text.length());
    }
    @Test void longChineseParagraphWithoutBlankLinesUsesWholeSentences() {
        String one = "甲".repeat(239)+"。";
        String two = "乙".repeat(239)+"！";
        String three = "丙".repeat(239)+"？";
        String four = "丁".repeat(239)+"。";
        String five = "戊".repeat(239)+"。";
        String text = one+two+three+four+five;
        var chunks = parse(text,800,100);
        assertRanges(text,chunks,800,100,720,text.length());
        assertThat(chunks.stream().map(c -> points(c.text()))).containsExactly(720,580);
    }
    @Test void englishSentencesRespectDecimalAbbreviationAndClosingQuotes() {
        String one = "Dr. Smith paid 3.14 dollars. ";
        String two = "\"Payment was approved!\" ";
        String three = "The final receipt is attached. ";
        String four = "Please keep it safe.";
        String text = one+two+three+four;
        var chunks = parse(text,70,10);
        assertRanges(text,chunks,70,10,one.length()+two.length(),text.length());
        assertThat(chunks.get(0).text()).isEqualTo(one+two);
    }
    @Test void chineseQuotedSentencesAndRepeatedPunctuationRemainWhole() {
        String one = "他说：“"+"甲".repeat(20)+"？！”";
        String two = "她答：“"+"乙".repeat(20)+"。”";
        String three = "最后，"+"丙".repeat(20)+"。";
        String text = one+two+three;
        assertRanges(text,parse(text,60,5),60,5,one.length()+two.length(),text.length());
    }
    @Test void sentenceTerminatedPlainTextLinesAreNaturalParagraphsWithoutBlankLines() {
        String first = "First paragraph.\n";
        String second = "第二个自然段。\n";
        String third = "Third paragraph.";
        String text = first+second+third;
        assertRanges(text,parse(text,800,3),800,3,first.length(),first.length()+second.length(),text.length());
    }
    @Test void unfinishedSoftWrappedLinesAreNotArtificialParagraphs() {
        String text = "This is a wrapped\nEnglish sentence that continues\non the next line.";
        assertRanges(text,parse(text,800,100),800,100,text.length());
    }
    @Test void pdfSoftLineBreaksIncludingSentenceEndDoNotForceSeparateChunks() {
        String text = "First complete sentence.\nA soft wrapped\nsecond sentence.\n";
        var extracted = new DocumentParser.Extracted(text,List.of(new DocumentParser.PageSpan(0,text.length(),1)));
        var chunks = parser.split("doc",extracted,800,100);
        assertRanges(text,chunks,800,100,text.length());
        assertThat(chunks.get(0).pageStart()).isEqualTo(1);
        assertThat(chunks.get(0).pageEnd()).isEqualTo(1);
    }
    @Test void emojiOverlapUsesCodePointsAndNeverSplitsSurrogates() {
        String first = "甲😀😀😀😀\n\n";
        String second = "乙🧪保持Unicode。";
        var chunks = parse(first+second,40,5);
        assertRanges(first+second,chunks,40,5,first.length(),first.length()+second.length());
        assertThat(chunks.get(1).text()).startsWith("😀😀😀\n\n");
        assertThat(points(chunks.get(1).text())).isEqualTo(5+points(second));
    }
    @Test void shortPreviousTextUsesOnlyAvailablePrefixAndEveryChunkAddsBody() {
        String first = "甲\n\n", second = "乙\n\n", third = "丙";
        String text = first+second+third;
        var chunks = parse(text,30,10);
        assertRanges(text,chunks,30,10,first.length(),first.length()+second.length(),text.length());
        assertThat(chunks.stream().map(c -> points(c.text()))).containsExactly(3,6,7);
    }
    @Test void firstChunkHasNoOverlapAndSubsequentChunkReservesTheTotalLength() {
        String first = "甲".repeat(797)+"。\n\n";
        String second = "乙".repeat(699)+"。";
        String text = first+second;
        var chunks = parse(text,800,100);
        assertRanges(text,chunks,800,100,first.length(),text.length());
        assertThat(chunks.stream().map(c -> points(c.text()))).containsExactly(800,800);
        assertThat(chunks).hasSize(2); // No redundant trailing overlap-only chunk.
    }
    @Test void zeroOverlapReconstructsAllSourceIncludingSeparatorsExactly() {
        String first = "\t起始正文。\r\n\r\n";
        String second = "第二段带🧪和 e\u0301。\r\n\r\n";
        String third = "最后一段。\r\n";
        String text = first+second+third;
        var chunks = parse(text,800,0);
        assertRanges(text,chunks,800,0,first.length(),first.length()+second.length(),text.length());
        assertThat(String.join("",chunks.stream().map(ApiTypes.Chunk::text).toList())).isEqualTo(text);
    }
    @Test void originalUnicodeCombiningMarksAndWhitespaceAreNotNormalized() {
        String text = "\uFEFF  <script>alert('🧪')</script>\r\n混合 e\u0301 é \u00a0\t。\r\n";
        var chunks = parse(text,800,100);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo(text.substring(1));
    }
    @Test void unicodeLineSeparatorsAreRetained() {
        String first = "第一段。\u2028\u2028", second = "第二段。\u2029";
        assertRanges(first+second,parse(first+second,800,0),800,0,first.length(),first.length()+second.length());
    }
    @Test void pageSpansCoverOverlapFromThePreviousPage() {
        String first = "第一页的正文。\n\n";
        String second = "第二页的正文。\n\n";
        String third = "第三页的正文。";
        String text = first+second+third;
        var extracted = new DocumentParser.Extracted(text,List.of(
            new DocumentParser.PageSpan(0,first.length(),1),
            new DocumentParser.PageSpan(first.length(),first.length()+second.length(),2),
            new DocumentParser.PageSpan(first.length()+second.length(),text.length(),3)));
        var chunks = parser.split("doc",extracted,800,5);
        assertRanges(text,chunks,800,5,first.length(),first.length()+second.length(),text.length());
        assertThat(chunks.stream().map(ApiTypes.Chunk::pageStart)).containsExactly(1,1,2);
        assertThat(chunks.stream().map(ApiTypes.Chunk::pageEnd)).containsExactly(1,2,3);
    }
    @Test void overlapCanReachAcrossMultipleShortPagesWithUtf16PageOffsets() {
        String firstPage = "😀甲", secondPage = "🧪乙。\n\n", thirdPage = "第三页正文。";
        String text = firstPage+secondPage+thirdPage;
        var extracted = new DocumentParser.Extracted(text,List.of(
            new DocumentParser.PageSpan(0,firstPage.length(),1),
            new DocumentParser.PageSpan(firstPage.length(),firstPage.length()+secondPage.length(),2),
            new DocumentParser.PageSpan(firstPage.length()+secondPage.length(),text.length(),3)));
        var chunks = parser.split("doc",extracted,800,100);
        assertRanges(text,chunks,800,100,firstPage.length()+secondPage.length(),text.length());
        assertThat(chunks.stream().map(ApiTypes.Chunk::pageStart)).containsExactly(1,1);
        assertThat(chunks.stream().map(ApiTypes.Chunk::pageEnd)).containsExactly(2,3);
    }
    @Test void sentenceBoundariesNotPageBoundariesChooseTheChunks() {
        // Old fixed-window expectation (800/200 and pages 1..3/3) no longer applies.
        String text = "a".repeat(299)+"。"+"b".repeat(299)+"。"+"c".repeat(299)+"。";
        var extracted = new DocumentParser.Extracted(text,List.of(
            new DocumentParser.PageSpan(0,300,1),new DocumentParser.PageSpan(300,600,2),new DocumentParser.PageSpan(600,900,3)));
        var chunks = parser.split("doc",extracted,800,100);
        assertRanges(text,chunks,800,100,600,900);
        assertThat(chunks.stream().map(ApiTypes.Chunk::pageStart)).containsExactly(1,2);
        assertThat(chunks.stream().map(ApiTypes.Chunk::pageEnd)).containsExactly(2,3);
    }
    @Test void extractsRealPdfWithoutInventingParagraphsAtEachPage() throws Exception {
        byte[] bytes;
        try (PDDocument pdf=new PDDocument(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            for (int i=1;i<=2;i++) {
                PDPage page=new PDPage(); pdf.addPage(page);
                try (PDPageContentStream stream=new PDPageContentStream(pdf,page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                    stream.newLineAtOffset(50,700); stream.showText("Page "+i+" verified source.");
                    stream.newLineAtOffset(0,-20); stream.showText("A wrapped line on the same page.");
                    stream.endText();
                }
            }
            pdf.save(out); bytes=out.toByteArray();
        }
        var chunks=parser.parse("pdf","pdf",bytes,800,100);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("Page 1", "Page 2", "A wrapped line on the same page.");
        assertThat(chunks.get(0).pageStart()).isEqualTo(1);
        assertThat(chunks.get(0).pageEnd()).isEqualTo(2);
    }
    @Test void unpunctuatedLongSentenceFails422RatherThanOldFixedCharacterWindows() {
        // The old 800/800/150 emoji sliding windows silently cut a single sentence.
        assertUnsplit(() -> parse("😀".repeat(1550),800,100));
        assertUnsplit(() -> parse("x".repeat(1500),800,100));
        assertUnsplit(() -> parse("无句界".repeat(300),800,100));
    }
    @Test void overlapCapacityIsReservedBeforeAcceptingAnIndivisibleSentence() {
        String first = "前文。\n\n";
        assertUnsplit(() -> parse(first+"x".repeat(798),800,100));
    }
    @Test void lastSentenceCannotExceedTheSmallerPostOverlapBudget() {
        String firstSentence = "a".repeat(749)+"。";
        String nextSentence = "b".repeat(749)+"。";
        assertUnsplit(() -> parse(firstSentence+nextSentence,800,100));
    }
    @Test void punctuationWithoutAValidEnglishSentenceBoundaryIsNotAnEscapeHatch() {
        assertUnsplit(() -> parse("word.without.spaces".repeat(80),800,100));
        assertUnsplit(() -> parse("3.14159".repeat(200),800,100));
    }
    @Test void invalidUtf8FailsRatherThanReplacementCharacters() {
        assertApi(() -> parser.parse("d","txt",new byte[]{(byte)0xc3,0x28},800,100),"INVALID_UTF8");
    }
    @Test void bomRemovedAndHtmlPreservedAsText() {
        var chunks = parser.parse("doc","md","\uFEFF<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8),800,100);
        assertThat(chunks.get(0).text()).isEqualTo("<script>alert(1)</script>");
    }
    @Test void blankAndBinaryRejectedWithExplicitErrors() {
        for (String text : List.of(""," \r\n\t ","\u2003\u2028")) assertApi(() -> parse(text,800,100),"NO_TEXT");
        assertApi(() -> parser.split("doc",new DocumentParser.Extracted(" \n ",List.of()),800,100),"NO_TEXT");
        assertApi(() -> parser.parse("d","txt",new byte[]{65,0,66},800,100),"INVALID_TEXT");
    }
    @Test void emptyPdfFailsWithoutPretendingOcr() throws Exception {
        byte[] bytes;
        try (PDDocument pdf=new PDDocument(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage()); pdf.save(out); bytes=out.toByteArray();
        }
        assertThatThrownBy(() -> parser.parse("d","pdf",bytes,800,100))
            .isInstanceOfSatisfying(ApiException.class,e -> {
                assertThat(e.status).isEqualTo(422); assertThat(e.code).isEqualTo("NO_TEXT");
                assertThat(e.getMessage()).contains("OCR");
            });
    }
    @Test void malformedPdfIsRejected() {
        assertApi(() -> parser.parse("doc","pdf","not a PDF".getBytes(StandardCharsets.UTF_8),800,100),"INVALID_PDF");
    }
    @Test void extractedTextLimitAlsoAppliesToDirectSplit() {
        assertApi(() -> parser.split("doc",new DocumentParser.Extracted("x".repeat(4_000_001),List.of()),800,100),"TEXT_TOO_LARGE");
    }
    @Test void invalidParametersCannotInfiniteLoopAndAreCheckedBeforeExtraction() {
        for (int[] pair : new int[][]{{0,0},{-1,0},{100,-1},{100,100},{100,101}}) {
            assertThatThrownBy(() -> parser.split("doc",new DocumentParser.Extracted("abc",List.of()),pair[0],pair[1]))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> parser.parse("doc","pdf",new byte[0],pair[0],pair[1]))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void smallestValidCapacityStillMakesProgressWithoutOverlapOnlyChunks() {
        assertRanges("甲\n\n乙",parse("甲\n\n乙",4,3),4,3,3,4);
        assertRanges("甲",parse("甲",1,0),1,0,1);
    }

    @Test void headingCanStandAloneWhenHeadingAndWholeFirstSentenceCannotFitTogether() {
        String heading = "# Long heading\n\n";
        String sentence = "x".repeat(89)+"。";
        String text = heading+sentence;
        assertRanges(text,parse(text,100,10),100,10,heading.length(),text.length());
    }
    @Test void englishAbbreviationIsNotUsedToEvadeIndivisibleSentenceLimit() {
        assertUnsplit(() -> parse("Dr. Smith.",8,0));
        assertUnsplit(() -> parse("Dr.\nSmith.",8,0));
    }
    @Test void identicalParagraphsAreReconstructedByOffsetsNotDeduplicatedByValue() {
        String paragraph = "重复😀。\n\n";
        String text = paragraph.repeat(4);
        assertRanges(text,parse(text,20,5),20,5,paragraph.length(),paragraph.length()*2,paragraph.length()*3,text.length());
    }
    @Test void finalWhitespaceAndLeadingBlankLinesRemainInTheirOriginalRanges() {
        String first = "\n\n前段。\n\n", second = "```\ncode\n```\n\n\t ";
        String text = first+second;
        assertRanges(text,parse(text,800,5),800,5,first.length(),text.length());
    }
    @Test void plainTextChunksHaveNoInventedPdfPageMetadata() {
        assertThat(parse("首段。\n\n次段。",800,100)).allSatisfy(chunk -> {
            assertThat(chunk.pageStart()).isNull(); assertThat(chunk.pageEnd()).isNull();
        });
    }
    @Test void realPdfOverlapIncludesPreviousPageInMetadata() throws Exception {
        String first = "First page verified source.", second = "Second page verified source.";
        byte[] bytes;
        try (PDDocument pdf=new PDDocument(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            for (String value : List.of(first,second)) {
                PDPage page=new PDPage(); pdf.addPage(page);
                try (PDPageContentStream stream=new PDPageContentStream(pdf,page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                    stream.newLineAtOffset(50,700); stream.showText(value); stream.endText();
                }
            }
            pdf.save(out); bytes=out.toByteArray();
        }
        var chunks = parser.parse("doc","pdf",bytes,40,10);
        String text = first+System.lineSeparator()+second+System.lineSeparator();
        assertRanges(text,chunks,40,10,first.length()+System.lineSeparator().length(),text.length());
        assertThat(chunks.stream().map(ApiTypes.Chunk::pageStart)).containsExactly(1,1);
        assertThat(chunks.stream().map(ApiTypes.Chunk::pageEnd)).containsExactly(1,2);
    }
    @Test void longPdfParagraphGroupsWholeSentencesAcrossSoftLineBreaks() {
        String one = "First complete\nwrapped sentence.\n";
        String two = "Second complete\nwrapped sentence.\n";
        String three = "Third complete\nwrapped sentence.\n";
        String text = one+two+three;
        var extracted = new DocumentParser.Extracted(text,List.of(new DocumentParser.PageSpan(0,text.length(),1)));
        var chunks = parser.split("doc",extracted,80,10);
        assertRanges(text,chunks,80,10,one.length()+two.length(),text.length());
    }
    @Test void markdownFormatUsesTheSameHeadingAndOverlapContract() {
        String first = "# One\nFirst body.\n\n", second = "# Two\nSecond body.";
        String text = first+second;
        var chunks = parser.parse("doc","md",text.getBytes(StandardCharsets.UTF_8),800,5);
        assertRanges(text,chunks,800,5,first.length(),text.length());
    }
    private List<ApiTypes.Chunk> parse(String text, int size, int overlap) {
        return parser.parse("doc","txt",text.getBytes(StandardCharsets.UTF_8),size,overlap);
    }
    private static int points(String text) { return text.codePointCount(0,text.length()); }
    private static void assertUnsplit(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) { assertApi(call,"UNSPLITTABLE_TEXT"); }
    private static void assertApi(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,e -> {
            assertThat(e.status).isEqualTo(422); assertThat(e.code).isEqualTo(code);
        });
    }
    private static void assertRanges(String source, List<ApiTypes.Chunk> chunks, int size, int overlap, int... bodyEnds) {
        assertThat(chunks).hasSize(bodyEnds.length);
        StringBuilder rebuilt = new StringBuilder();
        List<String> ids = new ArrayList<>();
        int bodyStart = 0;
        for (int i=0; i<bodyEnds.length; i++) {
            int bodyEnd = bodyEnds[i];
            var chunk = chunks.get(i);
            assertThat(bodyEnd).isGreaterThan(bodyStart);
            String newBody = source.substring(bodyStart,bodyEnd);
            assertThat(newBody.isBlank()).isFalse();
            int prefixPoints = i==0 ? 0 : Math.min(overlap,source.codePointCount(0,bodyStart));
            int rangeStart = source.offsetByCodePoints(bodyStart,-prefixPoints);
            assertThat(chunk.text()).as("chunk %s must be one exact original source range",i+1).isEqualTo(source.substring(rangeStart,bodyEnd));
            assertThat(points(chunk.text())).isLessThanOrEqualTo(size);
            assertThat(chunk.index()).isEqualTo(i+1);
            assertThat(chunk.documentId()).isEqualTo("doc");
            assertThat(chunk.id()).isNotBlank(); ids.add(chunk.id());
            for (int j=0; j<chunk.text().length(); j++) {
                char c=chunk.text().charAt(j);
                if (Character.isHighSurrogate(c)) {
                    assertThat(j+1).isLessThan(chunk.text().length());
                    assertThat(Character.isLowSurrogate(chunk.text().charAt(++j))).isTrue();
                } else assertThat(Character.isLowSurrogate(c)).isFalse();
            }
            // Remove only the known original-prefix overlap, never deduplicate by text equality.
            rebuilt.append(chunk.text().substring(chunk.text().offsetByCodePoints(0,prefixPoints)));
            bodyStart = bodyEnd;
        }
        assertThat(bodyStart).isEqualTo(source.length());
        assertThat(rebuilt.toString()).isEqualTo(source);
        assertThat(ids).doesNotHaveDuplicates();
    }
}
