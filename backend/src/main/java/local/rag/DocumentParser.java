package local.rag;

import static local.rag.ApiTypes.Chunk;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class DocumentParser {
    private static final int MAX_TEXT_CHARS = 4_000_000;
    record PageSpan(int start, int end, int page) {}
    record Extracted(String text, List<PageSpan> pages) {}
    public List<Chunk> parse(String documentId, String format, byte[] bytes, int size, int overlap) {
        Extracted extracted = "pdf".equals(format) ? pdf(bytes) : utf8(bytes);
        String text = extracted.text();
        if (text.length() > MAX_TEXT_CHARS) throw new ApiException(422,"TEXT_TOO_LARGE","提取正文过长，超过400万UTF-16单元，请拆小文档后重新上传");
        if (text.isBlank()) throw new ApiException(422,"NO_TEXT","文档没有可提取正文；扫描型PDF不支持OCR");
        return split(documentId, extracted, size, overlap);
    }
    Extracted utf8(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            if (text.indexOf('\0') >= 0) throw new ApiException(422,"INVALID_TEXT","文本包含二进制内容，请使用UTF-8纯文本");
            return new Extracted(text,List.of());
        } catch (CharacterCodingException e) { throw new ApiException(422,"INVALID_UTF8","TXT和Markdown必须使用UTF-8编码"); }
    }
    private Extracted pdf(byte[] bytes) {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            if (pdf.isEncrypted()) throw new ApiException(422,"ENCRYPTED_PDF","不支持加密PDF，请先解密");
            StringBuilder text = new StringBuilder();
            List<PageSpan> pages = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page=1;page<=pdf.getNumberOfPages();page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                String part = stripper.getText(pdf);
                if (!part.isEmpty()) {
                    int start = text.length(); text.append(part);
                    pages.add(new PageSpan(start,text.length(),page));
                    text.append('\n');
                }
                if (text.length()>MAX_TEXT_CHARS) throw new ApiException(422,"TEXT_TOO_LARGE","PDF提取正文过长，请拆小文档后重新上传");
            }
            return new Extracted(text.toString(),pages);
        } catch (IOException e) { throw new ApiException(422,"INVALID_PDF","PDF无法读取、已加密或文件损坏"); }
    }
    List<Chunk> split(String id, Extracted extracted, int size, int overlap) {
        if (size<1 || overlap<0 || overlap>=size) throw new IllegalArgumentException("invalid chunk parameters");
        String text=extracted.text();
        List<Chunk> result=new ArrayList<>();
        int start=0, firstPage=0, startPoint=0;
        int totalPoints=text.codePointCount(0,text.length());
        while (start<text.length()) {
            int remaining=totalPoints-startPoint;
            int end=text.offsetByCodePoints(start,Math.min(size,remaining));
            String piece=text.substring(start,end);
            if (!piece.isBlank()) {
                while (firstPage<extracted.pages().size() && extracted.pages().get(firstPage).end()<=start) firstPage++;
                Integer pageStart=null, pageEnd=null;
                for (int p=firstPage;p<extracted.pages().size();p++) {
                    PageSpan span=extracted.pages().get(p);
                    if (span.start()>=end) break;
                    if (pageStart==null) pageStart=span.page();
                    pageEnd=span.page();
                }
                result.add(new Chunk(UUID.randomUUID().toString(),id,result.size()+1,piece,pageStart,pageEnd));
            }
            if (end==text.length()) break;
            start=text.offsetByCodePoints(end,-overlap);
            startPoint+=size-overlap;
        }
        if (result.isEmpty()) throw new ApiException(422,"NO_TEXT","文档没有可提取正文");
        return result;
    }
}
