package local.rag;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluationConfigTest {
    @TempDir Path dir;
    Path config(String chatEndpoint) throws Exception {
        Files.createDirectories(dir.resolve("backend/src/main/resources")); Files.createDirectories(dir.resolve(".local"));
        String yaml = """
            rag:
              chroma-base-url: http://127.0.0.1:8000
              chroma-collection: daily_must_not_be_used
              chat-base-url: %s
              chat-api-key: "${CHAT_SECRET}"
              chat-model: deepseek-v4-flash
              embedding-base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
              embedding-api-key: "${EMBED_SECRET}"
              embedding-model: text-embedding-v4
              embedding-dimensions: 1024
              timeout-seconds: 60
              chroma-timeout-seconds: 10
              chat-max-output-tokens: 512
              chat-context-budget: 16000
              chunk-size: 800
              chunk-overlap: 100
              min-score: 0.6
              chunking:
                strategy: RULE
                max-output-tokens: 1024
            """.formatted(chatEndpoint);
        Files.writeString(dir.resolve("backend/src/main/resources/application.yml"), yaml);
        Files.writeString(dir.resolve(".local/app.env"), "CHAT_SECRET=fake-chat-key\nEMBED_SECRET=fake-embed-key\nDB_PASSWORD=never-bind-db\nCHAT_MODEL=unapproved-env-override\n");
        Path price = dir.resolve("price.json");
        Files.writeString(price, """
            {"schemaVersion":1,"confirmed":true,"verifiedAt":"%s","currency":"CNY","unit":"per_million_tokens",
             "sources":["https://api-docs.deepseek.com/zh-cn/quick_start/pricing/","https://help.aliyun.com/zh/model-studio/text-embedding-v4"],
             "maxRates":{"deepseekInputCacheMiss":2,"deepseekOutput":8,"qwenInput":0.514}}
            """.formatted(LocalDate.now(ZoneOffset.UTC)));
        return price;
    }
    @Test void safelyBindsYamlPlaceholdersAndPreservesProductionParametersWithoutSpring() throws Exception {
        Path price = config("https://api.deepseek.com");
        var config = new EvaluationConfig(dir, "http://127.0.0.1:18000", price, RagEvaluationMain.JSON);
        RagProperties p = config.properties("eval_independent");
        assertThat(p.chromaBaseUrl()).isEqualTo("http://127.0.0.1:18000"); assertThat(p.chromaCollection()).isEqualTo("eval_independent");
        assertThat(p.chatModel()).isEqualTo("deepseek-v4-flash"); assertThat(p.chatApiKey()).isEqualTo("fake-chat-key");
        assertThat(p.chunkSize()).isEqualTo(800); assertThat(p.chunkOverlap()).isEqualTo(100); assertThat(p.minScore()).isEqualTo(0.6);
        assertThat(config.chunking("A").getStrategy()).isEqualTo(ChunkingProperties.Strategy.RULE);
        assertThat(config.chunking("B").getStrategy()).isEqualTo(ChunkingProperties.Strategy.LLM);
        assertThat(config.chunking("B").getMaxOutputTokens()).isEqualTo(1024);
        assertThat(RagEvaluationMain.JSON.writeValueAsString(config.publicSettings())).doesNotContain("fake-chat-key", "fake-embed-key", "never-bind-db");
    }
    @Test void rejectsUnconfirmedEndpointsAndDuplicateYamlKeys() throws Exception {
        Path price = config("https://api.deepseek.com.attacker.example");
        assertThatThrownBy(() -> new EvaluationConfig(dir, "http://127.0.0.1:18000", price, RagEvaluationMain.JSON)).isInstanceOf(IllegalArgumentException.class);
        config("https://api.deepseek.com");
        Files.writeString(dir.resolve("backend/src/main/resources/application.yml"), "rag:\n  chat-api-key: fake-secret-one\n  chat-api-key: fake-secret-two\n");
        assertThatThrownBy(() -> new EvaluationConfig(dir, "http://127.0.0.1:18000", price, RagEvaluationMain.JSON))
            .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("fake-secret");
    }
    @Test void rejectsFutureAndStalePriceVerificationWithoutHardcodingTheExperimentDate() {
        LocalDate today = LocalDate.of(2026, 11, 18);
        EvaluationConfig.validatePriceDate("2026-11-18", today); EvaluationConfig.validatePriceDate("2026-11-17", today);
        assertThatThrownBy(() -> EvaluationConfig.validatePriceDate("2026-11-16", today)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvaluationConfig.validatePriceDate("2026-11-19", today)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvaluationConfig.validatePriceDate("not-a-date", today)).isInstanceOf(RuntimeException.class);
    }
}
