package local.rag;

import java.time.Instant;
import java.util.List;

public final class ApiTypes {
    private ApiTypes() {}
    public record Document(String id, String fileName, String format, long sizeBytes, String status,
                           int chunkCount, Instant createdAt, String errorMessage) {}
    public record Chunk(String id, String documentId, int index, String text, Integer pageStart, Integer pageEnd) {}
    public record Documents(List<Document> items, long total) {}
    public record Chunks(List<Chunk> items, long total, int page, int pageSize) {}
    public record KnowledgeStatus(long readyDocuments, long totalDocuments, boolean modelConfigured) {}
    public record Question(String question) {}
    public record Source(String chunkId, String documentId, String fileName, int chunkIndex,
                         String text, Integer pageStart, Integer pageEnd) {}
    public record Answer(String answer, boolean grounded, List<Source> sources) {}
    public record HistoryTurn(String question, String answer) {}
    public record Message(String question, String requestId) {}
    public record Exchange(String requestId, String question, Answer response, @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) Instant createdAt) {}
    public record Conversation(String id, List<Exchange> exchanges) {}
    public record Error(String code, String message) {}
}
