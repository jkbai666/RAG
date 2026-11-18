package local.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rag.chunking")
public class ChunkingProperties {
    public enum Strategy { RULE, LLM }

    private Strategy strategy = Strategy.RULE;
    private int contextBudget = 16000;
    private int maxOutputTokens = 2048;
    private int maxUnitsPerWindow = 120;
    private int maxCallsPerDocument = 24;
    private int requestTimeoutSeconds = 60;
    private int deadlineSeconds = 300;

    public Strategy getStrategy() { return strategy; }
    public void setStrategy(Strategy strategy) { this.strategy = strategy; }
    public int getContextBudget() { return contextBudget; }
    public void setContextBudget(int contextBudget) { this.contextBudget = contextBudget; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public int getMaxUnitsPerWindow() { return maxUnitsPerWindow; }
    public void setMaxUnitsPerWindow(int maxUnitsPerWindow) { this.maxUnitsPerWindow = maxUnitsPerWindow; }
    public int getMaxCallsPerDocument() { return maxCallsPerDocument; }
    public void setMaxCallsPerDocument(int maxCallsPerDocument) { this.maxCallsPerDocument = maxCallsPerDocument; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }
    public int getDeadlineSeconds() { return deadlineSeconds; }
    public void setDeadlineSeconds(int deadlineSeconds) { this.deadlineSeconds = deadlineSeconds; }
}
