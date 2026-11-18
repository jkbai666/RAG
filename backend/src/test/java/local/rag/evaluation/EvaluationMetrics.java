package local.rag;

import static local.rag.EvaluationDataset.require;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import local.rag.ApiTypes.Chunk;

final class EvaluationMetrics {
    record Span(String chunkId, int bodyStart, int bodyEnd, int start, int end) {}
    record Interval(String documentId, int start, int end) {}

    static void validateCoverage(String documentId, DocumentParser.Extracted extracted, List<Chunk> chunks,
                                 List<Span> spans, int size, int overlap) {
        String text = extracted.text();
        require(!text.isEmpty() && !chunks.isEmpty() && chunks.size() == spans.size(), "Empty/mismatched chunks and spans");
        int next = 0;
        StringBuilder reconstructed = new StringBuilder();
        var ids = new HashSet<String>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i); Span s = spans.get(i);
            require(c.documentId().equals(documentId) && c.index() == i + 1 && ids.add(c.id()) && c.id().equals(s.chunkId()), "Invalid chunk identity/order");
            require(s.bodyStart() == next && s.bodyEnd() > next && s.bodyEnd() <= text.length()
                && s.start() >= 0 && s.start() <= s.bodyStart() && s.end() == s.bodyEnd(), "Body gap/overlap or invalid span");
            require(EvaluationDataset.boundary(text, s.start()) && EvaluationDataset.boundary(text, s.bodyStart())
                && EvaluationDataset.boundary(text, s.end()), "Split Unicode surrogate");
            int expected = text.offsetByCodePoints(s.bodyStart(), -Math.min(overlap, text.codePointCount(0, s.bodyStart())));
            require(s.start() == expected, "Overlap differs from configured codepoint overlap");
            require(c.text().equals(text.substring(s.start(), s.end())) && c.text().codePointCount(0, c.text().length()) <= size,
                "Chunk text/size differs from source");
            Integer first = null, last = null;
            for (var page : extracted.pages()) {
                if (page.end() <= s.start() || page.start() >= s.end()) continue;
                if (first == null) first = page.page(); last = page.page();
            }
            require(java.util.Objects.equals(c.pageStart(), first) && java.util.Objects.equals(c.pageEnd(), last), "Incorrect original PDF pages");
            reconstructed.append(text, s.bodyStart(), s.bodyEnd()); next = s.bodyEnd();
        }
        require(next == text.length() && reconstructed.toString().equals(text), "Incomplete original text coverage");
    }

    /** Count covered UTF-16 units after union; duplicates and overlapping chunks cannot inflate recall. */
    static int covered(String documentId, int start, int end, List<Interval> intervals) {
        List<int[]> clipped = new ArrayList<>();
        for (Interval i : intervals) if (documentId.equals(i.documentId()) && i.start() < end && i.end() > start)
            clipped.add(new int[] { Math.max(start, i.start()), Math.min(end, i.end()) });
        clipped.sort(java.util.Comparator.comparingInt(i -> i[0]));
        int total = 0, left = -1, right = -1;
        for (int[] i : clipped) {
            if (i[0] > right) { if (left >= 0) total += right - left; left = i[0]; right = i[1]; }
            else right = Math.max(right, i[1]);
        }
        return total + (left < 0 ? 0 : right - left);
    }
    static double evidenceRecall(String documentId, List<EvaluationDataset.Evidence> evidence, List<Interval> intervals) {
        if (evidence.isEmpty()) throw new IllegalArgumentException("Evidence recall requires answerable gold");
        return evidence.stream().filter(e -> covered(documentId, e.start(), e.end(), intervals) == e.end() - e.start()).count() / (double) evidence.size();
    }
}
