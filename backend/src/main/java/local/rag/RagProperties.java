package local.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rag")
public record RagProperties(String chromaBaseUrl, String chromaCollection,
        String chatBaseUrl, String chatApiKey, String chatModel,
        String embeddingBaseUrl, String embeddingApiKey, String embeddingModel,
        int embeddingDimensions, int timeoutSeconds, int chromaTimeoutSeconds,
        int chatMaxOutputTokens, int chatContextBudget, int chunkSize, int chunkOverlap, double minScore) {
    public RagProperties {
        if (chunkSize < 1 || chunkSize > 800 || chunkOverlap < 0 || chunkOverlap >= chunkSize)
            throw new IllegalArgumentException("分块参数无效：大小须为1至800，重叠须小于大小");
        if (embeddingDimensions < 0 || timeoutSeconds < 1 || chromaTimeoutSeconds < 1
                || chatMaxOutputTokens < 64 || chatContextBudget < 1200 || chatContextBudget > 100000
                || minScore < 0 || minScore > 1) throw new IllegalArgumentException("RAG配置参数无效");
    }
    public boolean embeddingConfigured() { return present(embeddingBaseUrl, embeddingApiKey, embeddingModel); }
    public boolean chatConfigured() { return present(chatBaseUrl, chatApiKey, chatModel); }
    public boolean modelConfigured() { return embeddingConfigured() && chatConfigured(); }
    private static boolean present(String... values) {
        for (String v : values) if (v == null || v.isBlank()) return false;
        return true;
    }
    // Configuration records must never expose credentials through generated toString().
    @Override public String toString() { return "RagProperties[credentials redacted]"; }
}
