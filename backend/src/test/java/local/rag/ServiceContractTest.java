package local.rag;

import static local.rag.ApiTypes.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

// In-process SQL + deterministic gateway tests. These do not claim MySQL/Chroma/model integration coverage.
class ServiceContractTest {
    RagRepository repo;
    RagService service;
    FakeGateway gateway;
    MockMvc mvc;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    static RagProperties configured() {
        return new RagProperties("http://127.0.0.1:8000","rag_mvp","http://chat/v1","secret-chat","chat",
            "http://embed/v1","secret-embed","embed",0,5,5,700,16000,800,100,0.6);
    }
    @BeforeEach void setup() throws Exception {
        var ds=new JdbcDataSource(); ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try(var c=ds.getConnection()) { ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V1__initial.sql")); }
        repo=new RagRepository(new JdbcTemplate(ds),new TransactionTemplate(new DataSourceTransactionManager(ds)));
        gateway=new FakeGateway();
        service=new RagService(repo,configured(),new DocumentParser(),gateway,json); service.recover();
        mvc=MockMvcBuilders.standaloneSetup(new ApiController(service)).setControllerAdvice(new ApiErrors()).build();
    }
    @AfterEach void close() { service.shutdown(); }
    MockMultipartFile file(String name,String text) { return new MockMultipartFile("file",name,"text/plain",text.getBytes(StandardCharsets.UTF_8)); }
    Document ready(String text) {
        Document doc=service.upload(file("source.txt",text));
        await().atMost(Duration.ofSeconds(5)).until(() -> !"PROCESSING".equals(repo.getDocument(doc.id()).status()));
        assertThat(repo.getDocument(doc.id()).status()).isEqualTo("READY"); return repo.getDocument(doc.id());
    }
    @Test void healthStatusAndMissingDocumentFollowContract() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/knowledge/status")).andExpect(status().isOk()).andExpect(jsonPath("$.readyDocuments").value(0)).andExpect(jsonPath("$.modelConfigured").value(true));
        mvc.perform(get("/api/documents/missing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }
    // Multi-chunk fixtures use real sentence boundaries; unsplittable sentences are tested by the parser suite.
    @Test void uploadReturns202ProcessingThenReadyAndPaginatedFullText() throws Exception {
        var result=mvc.perform(multipart("/api/documents").file(file("source.txt","<b>"+("😀".repeat(99)+"。").repeat(16))))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PROCESSING")).andReturn();
        String id=json.readTree(result.getResponse().getContentAsString()).get("id").textValue();
        await().atMost(Duration.ofSeconds(5)).until(() -> repo.getDocument(id).status().equals("READY"));
        mvc.perform(get("/api/documents/"+id+"/chunks?page=2&pageSize=1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.page").value(2))
            .andExpect(jsonPath("$.items[0].index").value(2)).andExpect(jsonPath("$.items[0].pageStart").isEmpty());
        assertThat(service.chunks(id,1,100).items().get(0).text()).startsWith("<b>");
        mvc.perform(get("/api/documents")).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].chunkCount").value(3));
    }
    @Test void rejectsDuplicateFilesAndBadPaginationAndMalformedJson() throws Exception {
        mvc.perform(multipart("/api/documents").file(file("a.txt","a")).file(file("b.txt","b"))).andExpect(status().isBadRequest());
        for(String q:List.of("page=0","page=-1","page=2147483648","pageSize=101","pageSize=0","page=no"))
            mvc.perform(get("/api/documents/x/chunks?"+q)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{" )).andExpect(status().isBadRequest());
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\" \"}" )).andExpect(status().isBadRequest());
    }
    @Test void supportsLargeValidPageWithoutIntegerOverflow() {
        Document doc=ready("text");
        assertThat(service.chunks(doc.id(),Integer.MAX_VALUE,100).items()).isEmpty();
    }
    @Test void invalidUploadNotStoredAndFilenameNeverUsedAsPath() {
        assertThatThrownBy(() -> service.upload(file("a.exe","hello"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.upload(file("a.txt",""))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.upload(new MockMultipartFile("file","a.txt","text/plain",new byte[20*1024*1024+1])))
            .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status).isEqualTo(413));
        assertThat(RagService.filename("C:\\fakepath\\../../example.txt")).isEqualTo("example.txt");
        assertThat(repo.total()).isZero();
    }
    @Test void emptyKnowledgeBaseIs409NotUngroundedSuccess() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"hello\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMPTY_KNOWLEDGE_BASE"));
    }
    @Test void unicodeQuestionLimitCountsCodePoints() {
        assertThat(RagService.validateQuestion("😀".repeat(2000))).hasSize(4000);
        assertThatThrownBy(() -> RagService.validateQuestion("😀".repeat(2001))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RagService.validateQuestion("\uD800")).isInstanceOf(ApiException.class);
    }
    @Test void partialIndexFailureNotRetrievableAndAllStableIdsCleaned() {
        gateway.failIndex=true;
        Document doc=service.upload(file("source.txt",("a".repeat(99)+"。").repeat(16)));
        await().atMost(Duration.ofSeconds(5)).until(() -> repo.getDocument(doc.id()).status().equals("FAILED"));
        await().atMost(Duration.ofSeconds(5)).until(() -> gateway.index.isEmpty());
        assertThat(repo.ready()).isZero(); assertThat(repo.getDocument(doc.id()).chunkCount()).isZero();
        assertThatThrownBy(() -> service.chunks(doc.id(),1,20)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status).isEqualTo(409));
    }
    @Test void processingDeleteReturns409AndRecoveryClearsResiduals() throws Exception {
        repo.insertDocument("interrupted","interrupted.txt","txt",50);
        var chunk=new Chunk("leftover","interrupted",1,"text",null,null); repo.insertChunks(List.of(chunk));
        gateway.index.put("leftover",chunk);
        mvc.perform(delete("/api/documents/interrupted")).andExpect(status().isConflict());
        service.recover(); assertThat(repo.getDocument("interrupted").status()).isEqualTo("FAILED");
        service.retryCleanup(); assertThat(gateway.index).isEmpty(); assertThat(repo.vectorIds("interrupted")).isEmpty();
    }
    @Test void failedDeleteImmediatelyDisablesSearchAndCanRetryAcrossStores() throws Exception {
        Document doc=ready("knowledge"); gateway.failRemove=true;
        mvc.perform(delete("/api/documents/"+doc.id())).andExpect(status().isBadGateway());
        assertThat(repo.getDocument(doc.id()).status()).isEqualTo("FAILED");
        assertThat(repo.readySource(gateway.index.keySet().iterator().next())).isNull();
        assertThat(gateway.index).isNotEmpty();
        gateway.failRemove=false;
        mvc.perform(delete("/api/documents/"+doc.id())).andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(repo.total()).isZero(); assertThat(gateway.index).isEmpty();
    }
    @Test void noCandidatesSkipsGeneratorButFailuresStayTechnical() {
        ready("hello"); gateway.noCandidates=true;
        assertThat(service.chat("why").grounded()).isFalse(); assertThat(gateway.generateCalls).isZero();
        gateway.failRetrieve=true;
        assertThatThrownBy(() -> service.chat("why")).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status).isEqualTo(502));
    }
    @Test void sourceIsAuthoritativeSqlSnapshotNotChromaText() {
        Document doc=ready("<script>untrusted html</script>");
        Answer answer=service.chat("what");
        assertThat(answer.grounded()).isTrue(); assertThat(answer.sources()).hasSize(1);
        assertThat(answer.sources().get(0).documentId()).isEqualTo(doc.id());
        assertThat(answer.sources().get(0).text()).isEqualTo("<script>untrusted html</script>");
    }
    @Test void inventedReferencesAndMalformedResponsesRejected502() {
        ready("facts");
        for(String raw:List.of("not json","{}","{\"answer\":\"x\",\"grounded\":true,\"citationIds\":[\"forged\"]}",
                "{\"answer\":\"x\",\"grounded\":true,\"citationIds\":[]}","{\"answer\":\"x\",\"grounded\":false,\"citationIds\":[\"forged\"]}")) {
            gateway.raw=raw;
            assertThatThrownBy(() -> service.chat("q")).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status).isEqualTo(502));
        }
    }
    @Test void insufficientEvidenceIs200NotTechnicalAndCannotCarryMadeUpAnswer() throws Exception {
        ready("known fact"); gateway.raw="{\"answer\":\"UNSUPPORTED CLAIM\",\"grounded\":false,\"citationIds\":[]}";
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"q\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.grounded").value(false))
            .andExpect(jsonPath("$.sources").isEmpty()).andExpect(jsonPath("$.answer").value(RagService.INSUFFICIENT));
    }
    @Test void deleteWaitsForInFlightChatAndSubsequentChatCannotUseDeletedDocument() throws Exception {
        Document doc=ready("answer in document");
        gateway.generationEntered=new CountDownLatch(1); gateway.generationRelease=new CountDownLatch(1);
        CompletableFuture<Answer> chat=CompletableFuture.supplyAsync(() -> service.chat("what"));
        assertThat(gateway.generationEntered.await(2,TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> deletion=CompletableFuture.runAsync(() -> service.delete(doc.id()));
        Thread.sleep(100); assertThat(deletion.isDone()).isFalse();
        gateway.generationRelease.countDown();
        assertThat(chat.get(2,TimeUnit.SECONDS).grounded()).isTrue(); deletion.get(2,TimeUnit.SECONDS);
        assertThatThrownBy(() -> service.chat("what")).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code).isEqualTo("EMPTY_KNOWLEDGE_BASE"));
    }
    @Test void missingModelsDoesNotBreakHealthOrKnowledgeStatus() throws Exception {
        var missing=new RagProperties("http://localhost:8000","rag_mvp","","","","","","",0,5,5,700,7000,800,100,0.6);
        RagService noModels=new RagService(repo,missing,new DocumentParser(),gateway,json);
        try {
            assertThat(noModels.knowledge().modelConfigured()).isFalse();
            assertThatThrownBy(() -> noModels.upload(file("a.txt","a"))).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status).isEqualTo(503));
            ready("facts");
            assertThatThrownBy(() -> noModels.chat("q")).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status).isEqualTo(503));
        } finally { noModels.shutdown(); }
    }
    @Test void wrongModelProfileBlockedWhileDataExistsAndErrorsNeverExposeSecrets() throws Exception {
        ready("facts");
        assertThatThrownBy(() -> repo.ensureProfile("other-model")).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status).isEqualTo(503));
        gateway.failRetrieve=true;
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"q\"}"))
            .andExpect(status().isBadGateway()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET"))));
        assertThat(configured().toString()).doesNotContain("secret-chat", "secret-embed");
    }
    @Test void configuredCloudModelsCannotReuseOrMigrateAnExistingLocalIndex() {
        Document old=ready("preserved local evidence");
        List<String> oldChunkIds=repo.vectorIds(old.id());
        var cloud=new RagProperties("http://127.0.0.1:1","rag_qwen_v4_1024",
            "http://127.0.0.1:1","synthetic-chat-key","deepseek-v4-flash",
            "http://127.0.0.1:1/compatible-mode/v1","synthetic-embedding-key","text-embedding-v4",
            1024,5,5,700,7000,800,100,0.6);
        ModelGateway untouched=org.mockito.Mockito.mock(ModelGateway.class);
        RagService cloudService=new RagService(repo,cloud,new DocumentParser(),untouched,json);
        try {
            cloudService.recover();
            assertThatThrownBy(() -> cloudService.upload(file("new.txt","new evidence")))
                .isInstanceOfSatisfying(ApiException.class,e -> {
                    assertThat(e.status).isEqualTo(503); assertThat(e.code).isEqualTo("EMBEDDING_PROFILE_MISMATCH");
                });
            assertThatThrownBy(() -> cloudService.chat("q")).isInstanceOfSatisfying(ApiException.class,e -> {
                assertThat(e.status).isEqualTo(503); assertThat(e.code).isEqualTo("EMBEDDING_PROFILE_MISMATCH");
            });
            org.mockito.Mockito.verifyNoInteractions(untouched);
            assertThat(repo.total()).isEqualTo(1);
            assertThat(repo.vectorIds(old.id())).containsExactlyElementsOf(oldChunkIds);
            assertThat(repo.getDocument(old.id()).status()).isEqualTo("READY");
            // Returning to the original profile still works: no automatic rewrite, cleanup or fallback occurred.
            assertThat(service.chat("q").grounded()).isTrue();
        } finally { cloudService.shutdown(); }
    }
    @Test void timeoutErrorsDistinctFromUpstreamFailuresAndDoNotLeak() {
        ApiException error=ApiException.upstream(new RuntimeException("SECRET",new java.net.http.HttpTimeoutException("SECRET")),"模型");
        assertThat(error.status).isEqualTo(504); assertThat(error.getMessage()).doesNotContain("SECRET");
    }
    @Test void onlyActualCitationsReturnedNotAllRetrievedCandidates() {
        ready("facts "+("a".repeat(99)+"。").repeat(17));
        assertThat(gateway.index).hasSizeGreaterThan(1);
        var answer=service.chat("q");
        assertThat(answer.sources()).hasSize(1);
    }
    @Test void invalidUtf8UploadAsynchronouslyFailsWithoutPersistingChunks() {
        Document doc=service.upload(new MockMultipartFile("file","invalid.txt","text/plain",new byte[]{(byte)0xff}));
        await().atMost(Duration.ofSeconds(5)).until(() -> repo.getDocument(doc.id()).status().equals("FAILED"));
        assertThat(repo.getDocument(doc.id()).errorMessage()).contains("UTF-8");
        assertThat(repo.vectorIds(doc.id())).isEmpty(); assertThat(gateway.index).isEmpty();
    }
    @Test void failedDocumentVectorsFilteredUsingReadyAuthority() {
        Document failed=ready("will be unavailable");
        gateway.failRemove=true;
        assertThatThrownBy(() -> service.delete(failed.id())).isInstanceOf(ApiException.class);
        Document good=ready("available evidence");
        var answer=service.chat("q");
        assertThat(answer.sources()).allSatisfy(s -> assertThat(s.documentId()).isEqualTo(good.id()));
    }
    @Test void referencesOutsideActualContextRejectedEvenWhenChunkExists() throws Exception {
        Document doc=ready(("a".repeat(99)+"。").repeat(16));
        List<Chunk> chunks=service.chunks(doc.id(),1,20).items();
        Source allowed=repo.readySource(chunks.get(0).id());
        String response=json.writeValueAsString(Map.of("answer","x","grounded",true,"citationIds",List.of(chunks.get(1).id())));
        assertThatThrownBy(() -> service.validateAnswer(response,List.of(allowed))).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status).isEqualTo(502));
        String duplicate="{\"answer\":\"a\",\"answer\":\"b\",\"grounded\":false,\"citationIds\":[]}";
        assertThatThrownBy(() -> service.validateAnswer(duplicate,List.of(allowed))).isInstanceOf(ApiException.class);
    }
    class FakeGateway implements ModelGateway {
        final Map<String,Chunk> index=new ConcurrentHashMap<>();
        volatile boolean failIndex,failRemove,noCandidates,failRetrieve;
        volatile String raw;
        volatile int generateCalls;
        volatile CountDownLatch generationEntered,generationRelease;
        public List<Embedding> embed(List<String> texts) { return texts.stream().map(t -> Embedding.from(new float[]{1,0,0})).toList(); }
        public void index(List<Chunk> chunks,List<Embedding> vectors) {
            for(Chunk c:chunks) index.put(c.id(),c);
            if(failIndex) throw new ApiException(502,"UPSTREAM_FAILURE","索引失败");
        }
        public List<String> retrieve(Embedding v) {
            if(failRetrieve) throw ApiException.upstream(new RuntimeException("SECRET"),"Chroma");
            return noCandidates?List.of():new ArrayList<>(index.keySet()).stream().limit(5).toList();
        }
        public void remove(List<String> ids) { if(failRemove) throw new ApiException(502,"UPSTREAM_FAILURE","Chroma调用失败"); for(String id:ids) index.remove(id); }
        public String generate(String system,String user) {
            generateCalls++;
            if(generationEntered!=null) {
                generationEntered.countDown();
                try { if(!generationRelease.await(3,TimeUnit.SECONDS)) throw new RuntimeException("test timeout"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            }
            if(raw!=null) return raw;
            try {
                String id=json.readTree(user).get("context").get(0).get("id").textValue();
                return json.writeValueAsString(Map.of("answer","依据文档回答","grounded",true,"citationIds",List.of(id)));
            } catch(Exception e) { throw new RuntimeException(e); }
        }
    }
}
