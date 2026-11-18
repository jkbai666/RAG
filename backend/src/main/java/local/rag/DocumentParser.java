package local.rag;

import static local.rag.ApiTypes.Chunk;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class DocumentParser {
    private static final int MAX_TEXT_CHARS = 4_000_000;
    private static final Pattern HEADING = Pattern.compile(" {0,3}#{1,6}(?:\\s+.*|$)");
    private static final Pattern SETEXT = Pattern.compile(" {0,3}(?:={3,}|-{3,})\\s*");
    private static final Pattern LIST_ITEM = Pattern.compile(" {0,3}(?:[-+*]|[0-9]{1,9}[.)])\\s+.*");
    private static final Set<String> ABBREVIATIONS = Set.of("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e");
    record PageSpan(int start, int end, int page) {}
    record Extracted(String text, List<PageSpan> pages) {}
    record SourceSpan(int bodyStart, int bodyEnd, int start, int end) {}
    record Split(List<Chunk> chunks, List<SourceSpan> spans) {}
    record Unit(int id, int start, int end, String kind) {}
    private enum Kind { PROSE, LIST, CODE, TABLE }
    private record Block(int start, int end, Kind kind) {}
    private record Line(int start, int end, String content) {}

    public List<Chunk> parse(String documentId, String format, byte[] bytes, int size, int overlap) {
        validateParameters(size, overlap);
        Extracted extracted = extract(format, bytes);
        return split(documentId, extracted, size, overlap);
    }
    Extracted extract(String format, byte[] bytes) {
        Extracted extracted = "pdf".equals(format) ? pdf(bytes) : utf8(bytes);
        validateText(extracted.text());
        return extracted;
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
                    // A page break is not a blank paragraph. Preserve extracted soft line breaks.
                    if (!part.endsWith("\n") && !part.endsWith("\r")) text.append('\n');
                }
                if (text.length()>MAX_TEXT_CHARS) throw new ApiException(422,"TEXT_TOO_LARGE","PDF提取正文过长，请拆小文档后重新上传");
            }
            return new Extracted(text.toString(),pages);
        } catch (IOException e) { throw new ApiException(422,"INVALID_PDF","PDF无法读取、已加密或文件损坏"); }
    }
    List<Chunk> split(String id, Extracted extracted, int size, int overlap) {
        return splitWithSpans(id, extracted, size, overlap).chunks();
    }
    Split splitWithSpans(String id, Extracted extracted, int size, int overlap) {
        validateParameters(size, overlap);
        String text = extracted.text();
        validateText(text);

        // All boundaries are original UTF-16 offsets; lengths and overlap are Unicode code points.
        int[] points = new int[text.length()+1];
        for (int i=0; i<text.length();) {
            int next = i + Character.charCount(text.codePointAt(i));
            points[next] = points[i]+1;
            i = next;
        }
        List<Block> bodies = new ArrayList<>();
        for (Block block : paragraphs(text, !extracted.pages().isEmpty())) {
            int capacity = size - Math.min(overlap, points[block.start()]);
            if (points[block.end()] - points[block.start()] <= capacity) {
                bodies.add(block);
                continue;
            }
            if (block.kind() == Kind.CODE) throw unsplittable();
            int start = block.start(), end = start;
            for (int boundary : sentenceEnds(text, block)) {
                if (points[boundary] - points[start] > capacity) {
                    if (end == start) throw unsplittable();
                    bodies.add(new Block(start, end, block.kind()));
                    start = end;
                    capacity = size - Math.min(overlap, points[start]);
                    if (points[boundary] - points[start] > capacity) throw unsplittable();
                }
                end = boundary;
            }
            if (end > start) bodies.add(new Block(start, end, block.kind()));
        }

        return materialize(id, extracted, bodies.stream().map(Block::end).toList(), size, overlap);
    }

    Split materialize(String id, Extracted extracted, List<Integer> bodyEnds, int size, int overlap) {
        validateParameters(size, overlap);
        String text = extracted.text();
        validateText(text);
        List<Chunk> result = new ArrayList<>();
        List<SourceSpan> spans = new ArrayList<>();
        int firstPage = 0;
        int bodyStart = 0;
        for (int bodyEnd : bodyEnds) {
            if (bodyEnd <= bodyStart || bodyEnd > text.length()
                    || (bodyEnd < text.length() && Character.isLowSurrogate(text.charAt(bodyEnd))))
                throw new IllegalArgumentException("invalid body boundary");
            int start = bodyStart;
            // Walk at most overlap code points; avoid scanning the entire prefix for each body.
            for (int n=0; n<overlap && start>0; n++) start = text.offsetByCodePoints(start,-1);
            if (text.codePointCount(start,bodyEnd) > size || text.substring(bodyStart,bodyEnd).isBlank())
                throw unsplittable();
            while (firstPage < extracted.pages().size() && extracted.pages().get(firstPage).end() <= start) firstPage++;
            Integer pageStart = null, pageEnd = null;
            for (int p=firstPage; p<extracted.pages().size(); p++) {
                PageSpan span = extracted.pages().get(p);
                if (span.start() >= bodyEnd) break;
                if (pageStart == null) pageStart = span.page();
                pageEnd = span.page();
            }
            result.add(new Chunk(UUID.randomUUID().toString(),id,result.size()+1,
                    text.substring(start,bodyEnd),pageStart,pageEnd));
            spans.add(new SourceSpan(bodyStart,bodyEnd,start,bodyEnd));
            bodyStart = bodyEnd;
        }
        if (bodyStart != text.length()) throw new IllegalArgumentException("incomplete body coverage");
        return new Split(List.copyOf(result),List.copyOf(spans));
    }

    static void validateParameters(int size, int overlap) {
        if (size < 1 || overlap < 0 || overlap >= size) throw new IllegalArgumentException("invalid chunk parameters");
    }
    private static void validateText(String text) {
        if (text.length() > MAX_TEXT_CHARS) throw new ApiException(422,"TEXT_TOO_LARGE","提取正文过长，超过400万UTF-16单元，请拆小文档后重新上传");
        if (text.isBlank()) throw new ApiException(422,"NO_TEXT","文档没有可提取正文；扫描型PDF不支持OCR");
    }

    List<Unit> units(Extracted extracted, int size, int overlap) {
        validateParameters(size,overlap);
        String text = extracted.text();
        validateText(text);
        List<Unit> units = new ArrayList<>();
        int precedingPoints = 0;
        for (Block paragraph : paragraphs(text,!extracted.pages().isEmpty())) {
            for (Block block : tableBlocks(text,paragraph)) {
                int capacity = size - Math.min(overlap,precedingPoints);
                int length = text.codePointCount(block.start(),block.end());
                if (length <= capacity) {
                    units.add(new Unit(units.size()+1,block.start(),block.end(),block.kind().name()));
                } else {
                    if (block.kind()==Kind.CODE) throw unsplittable();
                    List<Integer> ends = block.kind()==Kind.TABLE ? tableRowEnds(text,block) : sentenceEnds(text,block);
                    int start = block.start(), end = start, consumed = 0;
                    for (int boundary : ends) {
                        if (boundary <= end) continue;
                        if (text.codePointCount(start,boundary)>capacity) {
                            if (end==start) throw unsplittable();
                            units.add(new Unit(units.size()+1,start,end,block.kind().name()));
                            consumed += text.codePointCount(start,end);
                            start = end;
                            capacity = size - Math.min(overlap,precedingPoints+consumed);
                            if (text.codePointCount(start,boundary)>capacity) throw unsplittable();
                        }
                        end = boundary;
                    }
                    if (end>start) units.add(new Unit(units.size()+1,start,end,block.kind().name()));
                }
                precedingPoints += length;
            }
        }
        return List.copyOf(units);
    }

    private static List<Block> tableBlocks(String text, Block block) {
        if (block.kind()!=Kind.PROSE) return List.of(block);
        List<Line> lines = lines(text.substring(block.start(),block.end()));
        List<Block> result = new ArrayList<>();
        int cursor = block.start();
        for (int i=0; i+1<lines.size(); i++) {
            String header = lines.get(i).content(), delimiter = lines.get(i+1).content();
            if (!header.contains("|") || !delimiter.matches("\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*")) continue;
            int tableStart = block.start()+lines.get(i).start();
            if (!addBlock(result,text,cursor,tableStart,Kind.PROSE)) tableStart = cursor;
            int last = i+1;
            while (last+1<lines.size() && lines.get(last+1).content().contains("|") && !lines.get(last+1).content().isBlank()) last++;
            while (last+1<lines.size() && lines.get(last+1).content().isBlank()) last++;
            cursor = block.start()+lines.get(last).end();
            result.add(new Block(tableStart,cursor,Kind.TABLE));
            i = last;
        }
        if (cursor<block.end()) {
            if (!addBlock(result,text,cursor,block.end(),Kind.PROSE) && !result.isEmpty()) {
                Block last = result.remove(result.size()-1);
                result.add(new Block(last.start(),block.end(),last.kind()));
            }
        }
        return result.isEmpty() ? List.of(block) : result;
    }

    private static List<Integer> tableRowEnds(String text, Block block) {
        List<Line> rows = lines(text.substring(block.start(),block.end()));
        List<Integer> ends = new ArrayList<>();
        int first = 0;
        while (first<rows.size() && rows.get(first).content().isBlank()) first++;
        // A header and delimiter stay with the first data row. Never cut inside a row.
        for (int i=first+2; i<rows.size(); i++) {
            int end = rows.get(i).end();
            while (i+1<rows.size() && rows.get(i+1).content().isBlank()) end = rows.get(++i).end();
            ends.add(block.start()+end);
        }
        if (ends.isEmpty() || ends.get(ends.size()-1)!=block.end()) ends.add(block.end());
        return ends;
    }
    private static ApiException unsplittable() {
        return new ApiException(422,"UNSPLITTABLE_TEXT","完整句子或代码块超过切块安全上限（已预留重叠容量），请增加块大小或在原文补充段落/句子边界；不会按字符截断");
    }

    private static List<Line> lines(String text) {
        List<Line> lines = new ArrayList<>();
        for (int start=0; start<text.length();) {
            int end = start;
            while (end<text.length() && text.charAt(end)!='\r' && text.charAt(end)!='\n'
                    && text.charAt(end)!='\u2028' && text.charAt(end)!='\u2029') end++;
            String content = text.substring(start,end);
            if (end<text.length() && text.charAt(end++)=='\r' && end<text.length() && text.charAt(end)=='\n') end++;
            lines.add(new Line(start,end,content));
            start = end;
        }
        return lines;
    }

    private static List<Block> paragraphs(String text, boolean pdf) {
        List<Line> lines = lines(text);
        List<Block> result = new ArrayList<>();
        int start = 0, fenceLength = 0;
        char fence = 0;
        Kind kind = Kind.PROSE;
        boolean gap = false, headingOnly = false;
        String previous = "";
        for (int i=0; i<lines.size(); i++) {
            Line line = lines.get(i);
            String value = line.content(), stripped = value.stripLeading();
            if (kind == Kind.CODE) {
                if (fenceRun(stripped, fence) >= fenceLength && stripped.substring(fenceRun(stripped, fence)).isBlank()) {
                    addBlock(result,text,start,line.end(),kind);
                    start = line.end(); kind = Kind.PROSE; headingOnly = false; gap = false;
                }
                continue;
            }
            if (value.isBlank()) { gap = true; continue; }
            boolean heading = HEADING.matcher(value).matches();
            boolean setext = i+1<lines.size() && SETEXT.matcher(lines.get(i+1).content()).matches();
            int run = stripped.isEmpty() ? 0 : fenceRun(stripped,stripped.charAt(0));
            boolean opensFence = value.length()-stripped.length()<=3 && run>=3;
            boolean item = LIST_ITEM.matcher(value).matches();
            boolean listContinuation = kind == Kind.LIST && !heading && !opensFence && !setext
                    && (item || value.startsWith("  ") || value.startsWith("\t"));
            boolean naturalBreak = !pdf && !headingOnly && endsSentence(previous) && !value.startsWith(" ") && !value.startsWith("\t");
            if (heading || setext || opensFence || (kind == Kind.LIST && !listContinuation)
                    || (item && kind != Kind.LIST) || (kind == Kind.PROSE && !headingOnly && (gap || naturalBreak))) {
                // Keep blank separators with the previous block; never normalize source text.
                if (addBlock(result,text,start,line.start(),kind)) start = line.start();
                kind = Kind.PROSE;
            }
            if (opensFence) {
                kind = Kind.CODE; fence = stripped.charAt(0); fenceLength = run; headingOnly = false;
            } else if (heading || setext) {
                headingOnly = true;
                if (setext) i++;
            } else {
                if (item || listContinuation) kind = Kind.LIST;
                headingOnly = false;
            }
            previous = value;
            gap = false;
        }
        if (!addBlock(result,text,start,text.length(),kind) && !result.isEmpty()) {
            Block last = result.remove(result.size()-1);
            result.add(new Block(last.start(),text.length(),last.kind()));
        }
        return result;
    }

    private static boolean addBlock(List<Block> result, String text, int start, int end, Kind kind) {
        if (start == end || text.substring(start,end).isBlank()) return false;
        result.add(new Block(start,end,kind));
        return true;
    }
    private static int fenceRun(String value, char marker) {
        if (marker!='`' && marker!='~') return 0;
        int i = 0;
        while (i<value.length() && value.charAt(i)==marker) i++;
        return i;
    }
    private static boolean endsSentence(String value) {
        int end = value.stripTrailing().length();
        while (end>0 && isCloser(value.charAt(end-1))) end--;
        return end>0 && sentenceMark(value,end-1,end);
    }
    private static boolean isCloser(char c) {
        return "\"'”’」』】）》〉)]}".indexOf(c)>=0;
    }
    private static boolean sentenceMark(String text, int at, int end) {
        char c = text.charAt(at);
        if ("。！？!?".indexOf(c)>=0) return true;
        if (c!='.') return false;
        if (at>0 && at+1<end && Character.isDigit(text.charAt(at-1)) && Character.isDigit(text.charAt(at+1))) return false;
        int tokenStart = at;
        while (tokenStart>0 && at-tokenStart<8 && (Character.isLetter(text.charAt(tokenStart-1)) || text.charAt(tokenStart-1)=='.')) tokenStart--;
        String token = text.substring(tokenStart,at);
        return !(token.length()==1 && Character.isLetter(token.charAt(0))) && !ABBREVIATIONS.contains(token.toLowerCase(java.util.Locale.ROOT));
    }
    private static List<Integer> sentenceEnds(String text, Block block) {
        List<Integer> ends = new ArrayList<>();
        for (int i=block.start(); i<block.end();) {
            if (!sentenceMark(text,i,block.end())) { i++; continue; }
            boolean cjk = "。！？".indexOf(text.charAt(i))>=0;
            int end = i+1;
            while (end<block.end() && ("。！？.!?".indexOf(text.charAt(end))>=0 || isCloser(text.charAt(end)))) end++;
            if (!cjk && end<block.end() && !Character.isWhitespace(text.codePointAt(end))) { i=end; continue; }
            while (end<block.end() && Character.isWhitespace(text.codePointAt(end))) end+=Character.charCount(text.codePointAt(end));
            ends.add(end);
            i = end;
        }
        // Add structural fallbacks only when an entire paragraph/list cannot fit.
        // A heading can then stand alone, and a list can end between whole items.
        List<Line> blockLines = lines(text.substring(block.start(),block.end()));
        for (int i=0; i<blockLines.size(); i++) {
            Line line = blockLines.get(i);
            if (block.kind()==Kind.LIST && line.start()>0 && LIST_ITEM.matcher(line.content()).matches()) {
                ends.add(block.start()+line.start());
            }
            if (block.kind()==Kind.PROSE) {
                boolean heading = HEADING.matcher(line.content()).matches();
                boolean setext = i+1<blockLines.size() && SETEXT.matcher(blockLines.get(i+1).content()).matches();
                if (heading || setext) {
                    int next = i+(setext ? 2 : 1);
                    while (next<blockLines.size() && blockLines.get(next).content().isBlank()) next++;
                    if (next<blockLines.size()) ends.add(block.start()+blockLines.get(next).start());
                }
            }
        }
        ends.sort(Integer::compareTo);
        if (ends.isEmpty() || ends.get(ends.size()-1)!=block.end()) ends.add(block.end());
        return ends;
    }
}
