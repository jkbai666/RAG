import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/** Offline PDF extraction, using the production per-page algorithm. No Spring startup. */
public class PdfText {
    private static String json(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32) out.append(String.format("\\u%04x", (int)c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    private static Object productionExtract(String format, byte[] bytes) throws Exception {
        Class<?> type = Class.forName("local.rag.DocumentParser");
        Object parser = type.getConstructor().newInstance();
        Method method = type.getDeclaredMethod("extract", String.class, byte[].class);
        method.setAccessible(true);
        return method.invoke(parser, format, bytes);
    }

    private static Object field(Object record, String name) throws Exception {
        Method accessor = record.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return accessor.invoke(record);
    }

    public static void main(String[] args) throws Exception {
        boolean verifyParser = args.length > 0 && args[0].equals("--verify-parser");
        int first = verifyParser ? 1 : 0;
        StringBuilder result = new StringBuilder("[");
        for (int arg = first; arg < args.length; arg++) {
            Path path = Path.of(args[arg]);
            byte[] bytes = Files.readAllBytes(path);
            boolean pdf = path.getFileName().toString().endsWith(".pdf");
            StringBuilder text = new StringBuilder();
            StringBuilder spans = new StringBuilder("[");
            int count = 0;
            int unitCount = -1;
            if (pdf) {
                try (PDDocument document = Loader.loadPDF(bytes)) {
                    if (document.isEncrypted()) throw new IllegalStateException("Encrypted PDF");
                    PDFTextStripper stripper = new PDFTextStripper();
                    for (int page = 1; page <= document.getNumberOfPages(); page++) {
                        stripper.setStartPage(page);
                        stripper.setEndPage(page);
                        String part = stripper.getText(document);
                        if (part.isBlank()) throw new IllegalStateException("Empty PDF page: " + page);
                        int start = text.length();
                        text.append(part);
                        if (count++ > 0) spans.append(',');
                        spans.append("{\"page\":").append(page).append(",\"start\":").append(start)
                             .append(",\"end\":").append(text.length()).append('}');
                        if (!part.endsWith("\n") && !part.endsWith("\r")) text.append('\n');
                    }
                }
            } else {
                String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                if (decoded.startsWith("\uFEFF")) decoded = decoded.substring(1);
                text.append(decoded);
            }
            spans.append(']');
            if (verifyParser) {
                Object extracted = productionExtract(pdf ? "pdf" : "txt", bytes);
                Class<?> parserType = Class.forName("local.rag.DocumentParser");
                Object parser = parserType.getConstructor().newInstance();
                Method units = parserType.getDeclaredMethod("units", extracted.getClass(), int.class, int.class);
                units.setAccessible(true);
                unitCount = ((java.util.List<?>)units.invoke(parser, extracted, 800, 100)).size();
                if (!text.toString().equals(field(extracted, "text")))
                    throw new IllegalStateException("Production parser text differs: " + path.getFileName());
                if (pdf) {
                    java.util.List<?> pages = (java.util.List<?>)field(extracted, "pages");
                    if (pages.size() != count) throw new IllegalStateException("Production page count differs");
                    // The JSON and production spans must agree exactly, including UTF-16 offsets.
                    StringBuilder actual = new StringBuilder("[");
                    for (Object page : pages) {
                        if (actual.length() > 1) actual.append(',');
                        actual.append("{\"page\":").append(field(page, "page"))
                              .append(",\"start\":").append(field(page, "start"))
                              .append(",\"end\":").append(field(page, "end")).append('}');
                    }
                    if (!actual.append(']').toString().equals(spans.toString()))
                        throw new IllegalStateException("Production page spans differ");
                }
            }
            if (arg > first) result.append(',');
            result.append("{\"file\":").append(json(path.getFileName().toString()))
                  .append(",\"text\":").append(json(text.toString()))
                  .append(",\"pages\":").append(spans)
                  .append(",\"unitCount\":").append(unitCount).append('}');
        }
        byte[] output = result.append(']').toString().getBytes(StandardCharsets.UTF_8);
        System.out.write(output);
    }
}
