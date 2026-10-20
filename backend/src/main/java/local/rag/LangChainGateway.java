package local.rag;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import local.rag.ApiTypes.Chunk;
import org.springframework.stereotype.Component;

@Component
public class LangChainGateway implements ModelGateway {
    private final RagProperties p;
    private volatile ChromaEmbeddingStore store;
    public LangChainGateway(RagProperties p) { this.p=p; }
    private synchronized ChromaEmbeddingStore store() {
        if (store==null) store=ChromaEmbeddingStore.builder().baseUrl(p.chromaBaseUrl())
            .apiVersion(ChromaApiVersion.V2).tenantName("default_tenant").databaseName("default_database")
            .collectionName(p.chromaCollection()).timeout(Duration.ofSeconds(p.chromaTimeoutSeconds()))
            .logRequests(false).logResponses(false).build();
        return store;
    }
    @Override public List<Embedding> embed(List<String> texts) {
        if (!p.embeddingConfigured()) throw new ApiException(503,"MODEL_NOT_CONFIGURED","向量模型尚未配置");
        try {
            var builder=OpenAiEmbeddingModel.builder().baseUrl(p.embeddingBaseUrl()).apiKey(p.embeddingApiKey())
                .modelName(p.embeddingModel()).timeout(Duration.ofSeconds(p.timeoutSeconds())).maxRetries(0)
                .maxSegmentsPerBatch(8).encodingFormat("float").logRequests(false).logResponses(false);
            if (p.embeddingDimensions()>0) builder.dimensions(p.embeddingDimensions());
            List<Embedding> result=builder.build().embedAll(texts.stream().map(TextSegment::from).toList()).content();
            if (result==null || result.size()!=texts.size()) throw new IllegalStateException("Invalid embedding count");
            int dimension=result.isEmpty()?0:result.get(0).vector().length;
            if (dimension==0 || (p.embeddingDimensions()>0 && dimension!=p.embeddingDimensions()))
                throw new IllegalStateException("Invalid embedding dimension");
            for (Embedding e:result) {
                if (e.vector().length!=dimension) throw new IllegalStateException("Inconsistent embedding dimensions");
                for (float v:e.vector()) if (!Float.isFinite(v)) throw new IllegalStateException("Non-finite embedding");
            }
            return result;
        } catch (ApiException e) { throw e; }
        catch (RuntimeException e) { throw ApiException.upstream(e,"向量模型"); }
    }
    @Override public void index(List<Chunk> chunks,List<Embedding> vectors) {
        try {
            List<TextSegment> segments=chunks.stream().map(c -> TextSegment.from(c.text(),
                Metadata.from("documentId",c.documentId()))).toList();
            store().addAll(chunks.stream().map(Chunk::id).toList(),vectors,segments);
        } catch (RuntimeException e) { throw ApiException.upstream(e,"Chroma"); }
    }
    @Override public List<String> retrieve(Embedding embedding) {
        try {
            return store().search(EmbeddingSearchRequest.builder().queryEmbedding(embedding).maxResults(5)
                .minScore(p.minScore()).build()).matches().stream().map(m -> m.embeddingId()).toList();
        } catch (RuntimeException e) { throw ApiException.upstream(e,"Chroma"); }
    }
    @Override public void remove(List<String> ids) {
        if (ids.isEmpty()) return;
        try {
            for (int i=0;i<ids.size();i+=128) store().removeAll(ids.subList(i,Math.min(i+128,ids.size())));
        } catch (RuntimeException e) { throw ApiException.upstream(e,"Chroma"); }
    }
    @Override public String generate(String system,String user) {
        if (!p.chatConfigured()) throw new ApiException(503,"MODEL_NOT_CONFIGURED","生成模型尚未配置");
        try {
            var model=OpenAiChatModel.builder().baseUrl(p.chatBaseUrl()).apiKey(p.chatApiKey()).modelName(p.chatModel())
                .temperature(0.0).maxTokens(p.chatMaxOutputTokens()).responseFormat("json_object")
                .customParameters(Map.of("thinking",Map.of("type","disabled")))
                .timeout(Duration.ofSeconds(p.timeoutSeconds())).maxRetries(0).logRequests(false).logResponses(false).build();
            var response=model.chat(SystemMessage.from(system),UserMessage.from(user));
            if (response==null || response.aiMessage()==null || response.aiMessage().text()==null)
                throw new IllegalStateException("Missing model response");
            return response.aiMessage().text();
        } catch (ApiException e) { throw e; }
        catch (RuntimeException e) { throw ApiException.upstream(e,"生成模型"); }
    }
}
