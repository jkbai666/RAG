package local.rag;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluationBudgetTest {
    @TempDir Path dir;
    final EvaluationBudget.Rates rates = new EvaluationBudget.Rates(new BigDecimal("2"), new BigDecimal("8"), new BigDecimal("0.514"));
    EvaluationBudget open() throws Exception { return new EvaluationBudget(dir.resolve("lock"), dir.resolve("ledger.jsonl"), rates); }

    @Test void reservationIsDurableBeforeHttpAndCompleteUsageReleasesOnlyUnusedReservation() throws Exception {
        try (var b = open()) {
            b.labels("validation", "B", "doc", "q");
            b.before("chat", 1000, 100);
            var rows = Files.readAllLines(dir.resolve("ledger.jsonl"));
            var reserve = new ObjectMapper().readTree(rows.get(1));
            assertThat(reserve.path("event").asText()).isEqualTo("reserve");
            assertThat(reserve.path("document").asText()).isEqualTo("doc");
            assertThat(b.charged()).isEqualByComparingTo("0.0028");
            b.after("chat", 200, 10, "DeepSeek-V4.1-Flash-1017");
            assertThat(b.charged()).isEqualByComparingTo("0.00048");
            assertThat(b.chatCalls()).isEqualTo(1);
        }
        String ledger = Files.readString(dir.resolve("ledger.jsonl"));
        assertThat(ledger).contains("DeepSeek-V4.1-Flash-1017", "settle", "close");
        assertThat(ledger).doesNotContain("apiKey", "prompt", "responseContent");
    }
    @Test void missingUsageAndFailuresRetainWorstCaseAndStillConsumeRequestCount() throws Exception {
        try (var b = open()) {
            b.before("chunk", 1000, 100); b.after("chunk", null, 10, null);
            assertThat(b.charged()).isEqualByComparingTo("0.0028");
            b.before("chat", 1000, 100); b.failedRequest();
            assertThat(b.charged()).isEqualByComparingTo("0.0056");
            b.before("embedding", 1000, 0); // Another failed request, followed by next before.
            b.before("embedding", 1000, 0); b.after("embedding", 10, 0, "text-embedding-v4");
            assertThat(b.charged()).isEqualByComparingTo("0.00611914");
            assertThat(b.chatCalls()).isEqualTo(2); assertThat(b.embeddingCalls()).isEqualTo(2);
        }
    }
    @Test void refusesReservationAt16AndLatchSurvivesAnUpstreamExceptionWrapper() throws Exception {
        try (var b = open()) {
            assertThatThrownBy(() -> b.before("chat", 8_000_000, 0)).isInstanceOf(IllegalStateException.class);
            assertThat(b.blocked()).isTrue(); assertThat(b.chatCalls()).isZero();
            assertThatThrownBy(b::ensureOpen).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> b.before("embedding", 128, 0)).isInstanceOf(IllegalStateException.class);
            assertThat(b.charged()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }
    @Test void failedReservationCannotBeForgottenBeforeNextRequest() throws Exception {
        try (var b = open()) {
            b.before("chat", 7_999_999, 0); b.failedRequest();
            assertThatThrownBy(() -> b.before("embedding", 128, 0)).isInstanceOf(IllegalStateException.class);
            assertThat(b.charged()).isEqualByComparingTo("15.999998");
            assertThat(b.embeddingCalls()).isZero();
        }
    }
    @Test void unexpectedLargerUsageIsRecordedAndStopsFurtherSpending() throws Exception {
        try (var b = open()) {
            b.before("chat", 128, 64);
            assertThatThrownBy(() -> b.after("chat", 200, 64, "model")).isInstanceOf(IllegalStateException.class);
            assertThat(b.charged()).isEqualByComparingTo("0.000912"); assertThat(b.blockedReason()).isEqualTo("usage_exceeds_bound");
        }
    }
    @Test void enforcesCombinedChunkAndChatRequestLimit() throws Exception {
        try (var b = open()) {
            for (int i = 0; i < 400; i++) { String stage = i % 2 == 0 ? "chunk" : "chat"; b.before(stage, 128, 1); b.after(stage, 1, 1, "model"); }
            assertThatThrownBy(() -> b.before("chat", 128, 1)).isInstanceOf(IllegalStateException.class);
            assertThat(b.chatCalls()).isEqualTo(400);
        }
    }
    @Test void enforcesEmbeddingRequestLimit() throws Exception {
        try (var b = open()) {
            for (int i = 0; i < 600; i++) { b.before("embedding", 128, 0); b.after("embedding", 1, 0, "text-embedding-v4"); }
            assertThatThrownBy(() -> b.before("embedding", 128, 0)).isInstanceOf(IllegalStateException.class);
            assertThat(b.embeddingCalls()).isEqualTo(600);
        }
    }
    @Test void rejectsConcurrentLedgerOwnersAndReleasesLockOnClose() throws Exception {
        try (var b = open()) {
            assertThatThrownBy(() -> new EvaluationBudget(dir.resolve("lock"), dir.resolve("other.jsonl"), rates)).isInstanceOf(java.io.IOException.class);
        }
        try (var next = new EvaluationBudget(dir.resolve("lock"), dir.resolve("next.jsonl"), rates)) { assertThat(next.blocked()).isFalse(); }
    }
    @Test void rejectsCrossThreadCallsAndBelowOfficialRates() throws Exception {
        try (var b = open()) {
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread other = new Thread(() -> { try { b.before("chat", 128, 1); } catch (Throwable e) { failure.set(e); } });
            other.start(); other.join(); assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
            assertThat(b.blocked()).isTrue(); assertThat(b.chatCalls()).isZero();
        }
        assertThatThrownBy(() -> new EvaluationBudget.Rates(new BigDecimal("1.9"), new BigDecimal("8"), new BigDecimal("0.514")))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void restartingAccumulatesOnlyEachRunsComponentNotPriorTotalsTwice() throws Exception {
        Path runs = dir.resolve("runs"); Files.createDirectories(runs.resolve("one"));
        Files.createDirectories(runs.resolve("two")); Files.createDirectories(runs.resolve("three"));
        Path lock = runs.resolve(".lock");
        try (var first = new EvaluationBudget(lock, runs.resolve("one/cost-ledger.jsonl"), rates, runs)) {
            first.before("chat", 1_000_000, 0); first.failedRequest();
            assertThat(first.charged()).isEqualByComparingTo("2");
        }
        try (var second = new EvaluationBudget(lock, runs.resolve("two/cost-ledger.jsonl"), rates, runs)) {
            assertThat(second.priorSpent()).isEqualByComparingTo("2");
            second.before("embedding", 1_000_000, 0); second.failedRequest();
            assertThat(second.charged()).isEqualByComparingTo("2.514");
        }
        try (var third = new EvaluationBudget(lock, runs.resolve("three/cost-ledger.jsonl"), rates, runs)) {
            assertThat(third.priorSpent()).isEqualByComparingTo("2.514");
            assertThat(third.chatCalls()).isEqualTo(1); assertThat(third.embeddingCalls()).isEqualTo(1);
        }
    }
    @Test void incompleteHistoricalLedgerCannotSilentlyResetBudget() throws Exception {
        Path runs = dir.resolve("runs"); Files.createDirectories(runs.resolve("old")); Files.createDirectories(runs.resolve("new"));
        Files.writeString(runs.resolve("old/cost-ledger.jsonl"), "{broken");
        assertThatThrownBy(() -> new EvaluationBudget(runs.resolve("lock"), runs.resolve("new/cost-ledger.jsonl"), rates, runs))
            .isInstanceOf(java.io.IOException.class);
        assertThat(Files.exists(runs.resolve("new/cost-ledger.jsonl"))).isFalse();
    }
    @Test void fallbackCannotBypassABlockedBudget() throws Exception {
        try (var b = open()) {
            var fake = new ModelGateway() {
                public java.util.List<dev.langchain4j.data.embedding.Embedding> embed(java.util.List<String> t) { throw new AssertionError("must not embed"); }
                public void index(java.util.List<ApiTypes.Chunk> c, java.util.List<dev.langchain4j.data.embedding.Embedding> e) { throw new AssertionError(); }
                public java.util.List<String> retrieve(dev.langchain4j.data.embedding.Embedding e) { throw new AssertionError(); }
                public void remove(java.util.List<String> ids) { throw new AssertionError(); }
                public String generate(String s, String u) { b.before("chunk", 8_000_000, 2048); return "{}"; }
            };
            var gateway = new RagEvaluationMain.CachedGateway(fake, b, new java.util.HashMap<>());
            var properties = new ChunkingProperties(); properties.setStrategy(ChunkingProperties.Strategy.LLM);
            byte[] bytes = "第一段。\n\n第二段。\n\n第三段。".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var result = new ChunkingService(new DocumentParser(), gateway, properties, new ObjectMapper()).chunk("doc", "txt", bytes, 800, 100);
            assertThat(result.strategy()).isEqualTo("RULE"); assertThat(b.blocked()).isTrue();
            assertThatThrownBy(() -> gateway.embed(java.util.List.of("question"))).isInstanceOf(IllegalStateException.class);
        }
    }
}
