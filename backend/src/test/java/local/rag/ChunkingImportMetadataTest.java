package local.rag;

import static local.rag.ApiTypes.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockMultipartFile;

// Import ordering with in-process mocks only: no datasource, HTTP client or paid model is constructed.
class ChunkingImportMetadataTest {
    final RagRepository repo = mock(RagRepository.class);
    final ModelGateway gateway = mock(ModelGateway.class);
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final RagProperties properties = new RagProperties("http://unused","test","http://unused","fake","fake",
            "http://unused","fake","fake",0,5,5,700,16000,800,100,0.6);

    MockMultipartFile file() {
        when(repo.insertDocument(anyString(),anyString(),anyString(),anyLong())).thenAnswer(call ->
            new Document(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),
                    "PROCESSING",0,Instant.now(),null));
        return new MockMultipartFile("file","source.txt","text/plain","完整原文。".getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @CsvSource(value = {"RULE,NULL", "LLM,NULL", "RULE,MODEL_TIMEOUT"}, nullValues = "NULL")
    void completeChunkingAndPersistMetadataBeforeWritingOrEmbedding(String strategy, String reason) {
        ChunkingService chunking = mock(ChunkingService.class);
        var chunks = List.of(new Chunk("chunk","doc",1,"完整原文。",null,null));
        var vectors = List.of(Embedding.from(new float[] {1,0}));
        when(chunking.chunk(anyString(),eq("txt"),any(byte[].class),eq(800),eq(100)))
                .thenReturn(new ChunkingService.Result(chunks,List.of(),strategy,reason));
        when(gateway.embed(List.of("完整原文。"))).thenReturn(vectors);
        var service = new RagService(repo,properties,new DocumentParser(),gateway,json,chunking);
        try { service.upload(file()); } finally { service.shutdown(); }

        var order = inOrder(chunking,repo,gateway);
        order.verify(chunking).chunk(anyString(),eq("txt"),any(byte[].class),eq(800),eq(100));
        order.verify(repo).setChunking(anyString(),eq(strategy),eq(reason));
        order.verify(repo).insertChunks(chunks);
        order.verify(gateway).embed(List.of("完整原文。"));
        order.verify(gateway).index(chunks,vectors);
        order.verify(repo).markReady(anyString(),eq(1));
        verify(repo,never()).markFailed(anyString(),anyString());
    }

    @Test void chunkingFailureNeverPublishesMetadataOrPartialChunks() {
        ChunkingService chunking = mock(ChunkingService.class);
        when(chunking.chunk(anyString(),anyString(),any(byte[].class),anyInt(),anyInt()))
                .thenThrow(new IllegalStateException("test-only failure"));
        var service = new RagService(repo,properties,new DocumentParser(),gateway,json,chunking);
        try { service.upload(file()); } finally { service.shutdown(); }
        verify(repo,never()).setChunking(anyString(),anyString(),any());
        verify(repo,never()).insertChunks(anyList());
        verify(gateway,never()).embed(anyList());
        verify(gateway,never()).index(anyList(),anyList());
        verify(repo).markFailed(anyString(),eq("文档处理失败，请检查文件和服务状态后重新上传"));
        verify(repo,never()).markReady(anyString(),anyInt());
    }

    @Test void existingFiveArgumentConstructorKeepsRuleStrategyAndNeverCallsAiChunking() {
        var service = new RagService(repo,properties,new DocumentParser(),gateway,json);
        try { service.upload(file()); } finally { service.shutdown(); }
        verify(repo).setChunking(anyString(),eq("RULE"),isNull());
        verify(repo).markReady(anyString(),eq(1));
        verify(gateway,never()).generate(anyString(),anyString());
        verify(gateway,never()).generateChunk(anyString(),anyString(),anyInt(),anyInt());
    }
}
