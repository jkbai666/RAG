package local.rag;

import java.util.List;
import dev.langchain4j.data.embedding.Embedding;
import local.rag.ApiTypes.Chunk;

public interface ModelGateway {
    List<Embedding> embed(List<String> texts);
    void index(List<Chunk> chunks, List<Embedding> embeddings);
    List<String> retrieve(Embedding embedding);
    void remove(List<String> ids);
    String generate(String system, String user);
    /** Chunking has its own output limit and remaining-deadline timeout. */
    default String generateChunk(String system, String user, int maxOutputTokens, int timeoutSeconds) {
        return generate(system, user);
    }
}
