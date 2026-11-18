package local.rag;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
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
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class LangChainGateway implements ModelGateway {
    private final RagProperties p;
    /** Internal, opt-in accounting hook. Never receives prompts, responses, keys or URLs. */
    public interface CallObserver {
        void before(String stage, int inputUpperBound, int maxOutputTokens);
        void after(String stage, Integer inputTokens, Integer outputTokens, String responseModel);
    }
    private static final CallObserver NO_OBSERVER = new CallObserver() {
        public void before(String stage,int input,int output) {}
        public void after(String stage,Integer input,Integer output,String model) {}
    };
    private final CallObserver observer;
    private volatile ChromaEmbeddingStore store;
    @Autowired public LangChainGateway(RagProperties p) { this(p,NO_OBSERVER); }
    public LangChainGateway(RagProperties p, CallObserver observer) {
        this.p=p; this.observer=java.util.Objects.requireNonNull(observer);
    }
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
            var model=builder.build();
            List<Embedding> result=new ArrayList<>();
            // Explicitly batch here: accounting must reserve before EACH HTTP request,
            // not after an SDK has silently split a large embedAll into several calls.
            for (int i=0;i<texts.size();i+=8) {
                List<String> batch=texts.subList(i,Math.min(i+8,texts.size()));
                int input=128;
                for (String text:batch) input=Math.addExact(input,Math.addExact(text.getBytes(StandardCharsets.UTF_8).length,16));
                observer.before("embedding",input,0);
                var response=model.embedAll(batch.stream().map(TextSegment::from).toList());
                var usage=response.tokenUsage();
                observer.after("embedding",usage==null?null:usage.inputTokenCount(),0,p.embeddingModel());
                if (response.content()==null || response.content().size()!=batch.size())
                    throw new IllegalStateException("Invalid embedding count");
                result.addAll(response.content());
            }
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
        return generate(system,user,p.chatMaxOutputTokens(),p.timeoutSeconds(),"chat");
    }
    @Override public String generateChunk(String system,String user,int maxOutputTokens,int timeoutSeconds) {
        if (maxOutputTokens<1 || timeoutSeconds<1) throw new IllegalArgumentException("Invalid chunk request limits");
        return generate(system,user,maxOutputTokens,timeoutSeconds,"chunk");
    }
    private String generate(String system,String user,int maxOutputTokens,int timeoutSeconds,String stage) {
        if (!p.chatConfigured()) throw new ApiException(503,"MODEL_NOT_CONFIGURED","生成模型尚未配置");
        try {
            var model=OpenAiChatModel.builder().baseUrl(p.chatBaseUrl()).apiKey(p.chatApiKey()).modelName(p.chatModel())
                .temperature(0.0).maxTokens(maxOutputTokens).responseFormat("json_object")
                .customParameters(Map.of("thinking",Map.of("type","disabled")))
                .timeout(Duration.ofSeconds(timeoutSeconds)).maxRetries(0).logRequests(false).logResponses(false).build();
            observer.before(stage,Math.addExact(Math.addExact(system.getBytes(StandardCharsets.UTF_8).length,
                user.getBytes(StandardCharsets.UTF_8).length),128),maxOutputTokens);
            var response=model.chat(SystemMessage.from(system),UserMessage.from(user));
            var usage=response==null?null:response.tokenUsage();
            observer.after(stage,usage==null?null:usage.inputTokenCount(),usage==null?null:usage.outputTokenCount(),
                response==null?null:response.modelName());
            if (response==null || response.aiMessage()==null || response.aiMessage().text()==null)
                throw new IllegalStateException("Missing model response");
            return response.aiMessage().text();
        } catch (ApiException e) { throw e; }
        catch (RuntimeException e) { throw ApiException.upstream(e,"生成模型"); }
    }
}
