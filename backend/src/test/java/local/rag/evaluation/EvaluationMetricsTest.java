package local.rag;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvaluationMetricsTest {
    final DocumentParser parser = new DocumentParser();
    @Test void evidenceRecallUsesOriginalIntervalsAndUnionsOverlapsWithoutCrossDocumentMatches() {
        var intervals = List.of(new EvaluationMetrics.Interval("d", 0, 8), new EvaluationMetrics.Interval("d", 4, 10),
            new EvaluationMetrics.Interval("other", 0, 99));
        assertThat(EvaluationMetrics.covered("d", 0, 20, intervals)).isEqualTo(10);
        var gold = List.of(new EvaluationDataset.Evidence(0, 10, "abcdefghij"), new EvaluationDataset.Evidence(10, 20, "klmnopqrst"));
        assertThat(EvaluationMetrics.evidenceRecall("d", gold, intervals)).isEqualTo(0.5);
        assertThatThrownBy(() -> EvaluationMetrics.evidenceRecall("d", List.of(), intervals)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void validatesExactSourceCoverageUnicodeSizeOverlapAndPages() {
        String text = "# 标题\n😀示例一。\n\n第二段保留空行。\n\n最后一段。\n";
        var extracted = parser.utf8(text.getBytes(StandardCharsets.UTF_8));
        var split = parser.splitWithSpans("d", extracted, 40, 5);
        var spans = java.util.stream.IntStream.range(0, split.spans().size()).mapToObj(i -> {
            var s = split.spans().get(i);
            return new EvaluationMetrics.Span(split.chunks().get(i).id(), s.bodyStart(), s.bodyEnd(), s.start(), s.end());
        }).toList();
        EvaluationMetrics.validateCoverage("d", extracted, split.chunks(), spans, 40, 5);
        var broken = new ArrayList<>(spans); var last = broken.get(broken.size() - 1);
        broken.set(broken.size() - 1, new EvaluationMetrics.Span(last.chunkId(), last.bodyStart() + 1, last.bodyEnd(), last.start(), last.end()));
        assertThatThrownBy(() -> EvaluationMetrics.validateCoverage("d", extracted, split.chunks(), broken, 40, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvaluationMetrics.validateCoverage("d", extracted, split.chunks(), spans, 40, 4)).isInstanceOf(IllegalArgumentException.class);
        var pages = new DocumentParser.Extracted(text, List.of(new DocumentParser.PageSpan(0, text.length(), 1)));
        assertThatThrownBy(() -> EvaluationMetrics.validateCoverage("d", pages, split.chunks(), spans, 40, 5)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cliAndChromaChecksNeverPermitTheOrdinaryKnowledgeBase() {
        EvaluationConfig.validateChroma("http://127.0.0.1:18000");
        for (String url : List.of("http://127.0.0.1:8000", "https://example.com:18000", "http://user@localhost:18000", "http://localhost:18000/x"))
            assertThatThrownBy(() -> EvaluationConfig.validateChroma(url)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RagEvaluationMain.parse(new String[] { "--live", "--live" })).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RagEvaluationMain.parse(new String[] { "--unknown" })).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void yamlPlaceholderExpansionUsesLocalFileOnlyAndDoesNotEvaluateCode() {
        var flat = new java.util.LinkedHashMap<String, Object>();
        EvaluationConfig.flatten("rag", java.util.Map.of("value", "${LOCAL:http://fallback}", "literal", "$(not-a-command)"), java.util.Map.of("LOCAL", "actual"), flat);
        assertThat(flat).containsEntry("rag.value", "actual").containsEntry("rag.literal", "$(not-a-command)");
        assertThatThrownBy(() -> EvaluationConfig.flatten("rag", "${MISSING}", java.util.Map.of(), flat)).isInstanceOf(IllegalArgumentException.class);
    }
}
