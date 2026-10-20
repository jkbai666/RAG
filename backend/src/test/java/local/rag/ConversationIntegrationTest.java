package local.rag;

import static local.rag.ApiTypes.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

// Isolated H2/MySQL-mode + Flyway + deterministic gateway, never application.yml or cloud APIs.
class ConversationIntegrationTest {
    JdbcTemplate db;
    TransactionTemplate tx;
    RagRepository documents;
    ConversationRepository repository;
    ConversationService conversations;
    RagService rag;
    MockMvc mvc;
    FakeGateway gateway;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    List<RagService> services=new ArrayList<>();
    @BeforeEach void setup() {
        var ds=new JdbcDataSource(); ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        db=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        gateway=new FakeGateway(); wire(16000); ready("doc","chunk","当前有效文档原文");
    }
    void wire(int budget) {
        documents=new RagRepository(db,tx);
        var p=new RagProperties("http://not-used","test","http://not-used/v1","fake","fake","http://not-used/v1","fake","fake",0,5,5,700,budget,800,100,0.6);
        rag=new RagService(documents,p,new DocumentParser(),gateway,json); services.add(rag);
        repository=new ConversationRepository(db,tx,json); conversations=new ConversationService(repository,rag);
        mvc=MockMvcBuilders.standaloneSetup(new ApiController(rag),new ConversationController(conversations)).setControllerAdvice(new ApiErrors()).build();
    }
    void ready(String doc,String chunk,String text) {
        documents.insertDocument(doc,doc+".txt","txt",text.length());
        documents.insertChunks(List.of(new Chunk(chunk,doc,1,text,null,null))); documents.markReady(doc,1);
    }
    @AfterEach void close() { services.forEach(RagService::shutdown); }
    String id() { return conversations.create().id(); }
    String requestId() { return UUID.randomUUID().toString(); }
    Answer send(String id,String question) { return conversations.send(id,new Message(question,requestId())); }
    JsonNode lastAnswerPrompt() { return gateway.calls.stream().filter(c -> c.system().equals(RagService.SYSTEM)).reduce((a,b)->b).orElseThrow().body(); }
    @Test void exactHttpContractAndLegacySingleTurnCompatibility() throws Exception {
        var created=json.readTree(mvc.perform(post("/api/conversations")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(created.size()).isEqualTo(2); assertThat(created.path("exchanges").isEmpty()).isTrue();
        String id=created.path("id").textValue(); UUID.fromString(id); String request=requestId();
        mvc.perform(post("/api/conversations/"+id+"/messages").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Message("first",request))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value("当前文档回答")).andExpect(jsonPath("$.grounded").value(true)).andExpect(jsonPath("$.sources[0].chunkId").value("chunk")).andExpect(jsonPath("$.response").doesNotExist());
        mvc.perform(get("/api/conversations/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.exchanges[0].requestId").value(request))
            .andExpect(jsonPath("$.exchanges[0].question").value("first")).andExpect(jsonPath("$.exchanges[0].createdAt").isString()).andExpect(jsonPath("$.exchanges[0].response.answer").value("当前文档回答"));
        int before=gateway.calls.size();
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"legacy\"}")).andExpect(status().isOk());
        assertThat(gateway.calls).hasSize(before+1); assertThat(lastAnswerPrompt().path("history").isEmpty()).isTrue();
        assertThat(conversations.get(id).exchanges()).hasSize(1);
    }
    @Test void invalidInputAndUnknownConversationNeverCallModels() throws Exception {
        String id=id();
        for (Message invalid:List.of(new Message("x","invalid"),new Message(" ",requestId()),new Message("😀".repeat(2001),requestId())))
            mvc.perform(post("/api/conversations/"+id+"/messages").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(invalid))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/conversations/bad")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/conversations/"+UUID.randomUUID())).andExpect(status().isNotFound());
        assertThatThrownBy(() -> send(UUID.randomUUID().toString(),"x")).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status).isEqualTo(404));
        assertThat(gateway.calls).isEmpty(); assertThat(gateway.embedded).isEmpty();
    }
    @Test void repositoryAndServiceReconstructionRestoresAnswersAndIdempotence() {
        String id=id(),request=requestId(); Answer original=conversations.send(id,new Message("最初问题",request));
        int calls=gateway.calls.size(); wire(16000);
        assertThat(conversations.get(id).exchanges().get(0).response()).isEqualTo(original);
        assertThat(conversations.send(id,new Message("最初问题",request))).isEqualTo(original);
        assertThat(gateway.calls).hasSize(calls); assertThat(gateway.embedded).hasSize(1);
    }
    @Test void followupRewritesBeforeEmbeddingAndHistoryContainsOnlyQA() {
        String id=id(); send(id,"休假规则是什么"); gateway.rewritten="休假规则适用哪些员工"; send(id,"它适用谁");
        assertThat(gateway.embedded).containsExactly("休假规则是什么","休假规则适用哪些员工");
        var rewrite=gateway.calls.get(1); assertThat(rewrite.system()).isEqualTo(RagService.REWRITE_SYSTEM);
        assertThat(rewrite.body().path("history").get(0).size()).isEqualTo(2);
        assertThat(rewrite.body().path("history").get(0).path("question").textValue()).isEqualTo("休假规则是什么");
        assertThat(rewrite.body().toString()).doesNotContain("sources","chunk","当前有效文档原文");
        assertThat(lastAnswerPrompt().path("question").textValue()).isEqualTo("它适用谁");
        assertThat(lastAnswerPrompt().path("retrievalQuestion").textValue()).isEqualTo(gateway.rewritten);
    }
    @Test void recentFiveAndNewConversationIsolation() {
        String id=id(); for(int i=0;i<8;i++) send(id,"问题"+i);
        var history=lastAnswerPrompt().path("history"); assertThat(history.size()).isEqualTo(5);
        assertThat(history.get(0).path("question").textValue()).isEqualTo("问题2");
        assertThat(history.get(4).path("question").textValue()).isEqualTo("问题6");
        int before=gateway.calls.size(); send(id(),"新会话");
        assertThat(gateway.calls).hasSize(before+1); assertThat(lastAnswerPrompt().path("history").isEmpty()).isTrue();
        assertThat(conversations.get(id).exchanges()).hasSize(8);
    }
    @Test void budgetDropsOldTurnsBeforeCurrentQuestionAndFullEvidence() {
        wire(5300); var history=new ArrayList<HistoryTurn>();
        for (int i=0;i<8;i++) history.add(new HistoryTurn("历史问题"+i,"旧".repeat(400)));
        rag.chat("当前问题",history);
        assertThat(lastAnswerPrompt().path("question").textValue()).isEqualTo("当前问题");
        assertThat(lastAnswerPrompt().path("context").get(0).path("text").textValue()).isEqualTo("当前有效文档原文");
        assertThat(lastAnswerPrompt().path("history").size()).isLessThan(5);
        for (Call c:gateway.calls) assertThat(c.system().getBytes(StandardCharsets.UTF_8).length+c.user().getBytes(StandardCharsets.UTF_8).length+700+128).isLessThanOrEqualTo(5300);
        gateway.calls.clear(); rag.chat("当前问题",List.of(new HistoryTurn("超长旧轮","旧".repeat(10000))));
        assertThat(gateway.calls).hasSize(1); assertThat(lastAnswerPrompt().path("history").isEmpty()).isTrue();
    }
    @Test void impossibleCurrentContextFailsWithoutAnswerGeneration() {
        wire(2500); db.update("UPDATE rag_chunks SET text=? WHERE id='chunk'","证据".repeat(3000));
        assertThatThrownBy(() -> send(id(),"当前问题")).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code).isEqualTo("CONTEXT_LIMIT"));
        assertThat(gateway.calls).isEmpty();
    }
    @Test void duplicateIsResolvedBeforePaidCallsAndConflictingQuestionIs409() {
        String id=id(),request=requestId(); Message message=new Message("问题",request);
        Answer first=conversations.send(id,message); assertThat(conversations.send(id,message)).isEqualTo(first);
        assertThatThrownBy(() -> conversations.send(id,new Message("其他问题",request))).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status).isEqualTo(409));
        assertThat(gateway.calls).hasSize(1); assertThat(gateway.embedded).hasSize(1); assertThat(conversations.get(id).exchanges()).hasSize(1);
    }
    @Test void failureNotInHistoryAndSameIdRetryBindingSurvivesReconstruction() {
        String id=id(),request=requestId(); gateway.fail=true;
        assertThatThrownBy(() -> conversations.send(id,new Message("问题",request))).isInstanceOf(ApiException.class);
        assertThat(conversations.get(id).exchanges()).isEmpty(); wire(16000);
        assertThatThrownBy(() -> conversations.send(id,new Message("异问题",request))).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status).isEqualTo(409));
        gateway.fail=false; conversations.send(id,new Message("问题",request)); assertThat(conversations.get(id).exchanges()).hasSize(1);
    }
    @Test void retryCompletionIsAfterInterveningSuccessInRecoveryAndRecentHistory() throws Exception {
        String id=id(),a=requestId(); gateway.fail=true;
        assertThatThrownBy(() -> conversations.send(id,new Message("A",a))).isInstanceOf(ApiException.class);
        gateway.fail=false; send(id,"B"); Thread.sleep(3); conversations.send(id,new Message("A",a));
        assertThat(conversations.get(id).exchanges()).extracting(Exchange::question).containsExactly("B","A");
        send(id,"C"); assertThat(lastAnswerPrompt().path("history").get(0).path("question").textValue()).isEqualTo("B");
        assertThat(lastAnswerPrompt().path("history").get(1).path("question").textValue()).isEqualTo("A");
    }
    @Test void requestIdentityIsConversationScoped() {
        String a=id(),b=id(),request=requestId(); conversations.send(a,new Message("会话A",request)); conversations.send(b,new Message("会话B",request));
        assertThat(conversations.get(a).exchanges()).extracting(Exchange::question).containsExactly("会话A");
        assertThat(conversations.get(b).exchanges()).extracting(Exchange::question).containsExactly("会话B"); assertThat(gateway.calls).hasSize(2);
    }
    @Test void simultaneousDuplicatesGenerateOnlyOnce() throws Exception {
        String id=id(),request=requestId(); gateway.entered=new CountDownLatch(1); gateway.release=new CountDownLatch(1);
        var first=CompletableFuture.supplyAsync(() -> conversations.send(id,new Message("问题",request)));
        assertThat(gateway.entered.await(3,TimeUnit.SECONDS)).isTrue();
        var second=CompletableFuture.supplyAsync(() -> conversations.send(id,new Message("问题",request)));
        try { Thread.sleep(60); assertThat(second).isNotDone(); assertThat(gateway.calls).hasSize(1); } finally { gateway.release.countDown(); }
        assertThat(second.get(3,TimeUnit.SECONDS)).isEqualTo(first.get(3,TimeUnit.SECONDS));
        assertThat(gateway.calls).hasSize(1); assertThat(conversations.get(id).exchanges()).hasSize(1);
    }
    @Test void sameConversationMessagesSerializeWithPredecessorHistory() throws Exception {
        String id=id(); gateway.entered=new CountDownLatch(1); gateway.release=new CountDownLatch(1);
        var first=CompletableFuture.supplyAsync(() -> send(id,"第一问")); assertThat(gateway.entered.await(3,TimeUnit.SECONDS)).isTrue();
        var second=CompletableFuture.supplyAsync(() -> send(id,"第二问"));
        try { Thread.sleep(60); assertThat(second).isNotDone(); } finally { gateway.release.countDown(); }
        first.get(3,TimeUnit.SECONDS); second.get(3,TimeUnit.SECONDS);
        assertThat(lastAnswerPrompt().path("history").get(0).path("question").textValue()).isEqualTo("第一问");
    }
    @Test void invalidRewriteNeverFallsBackOrPersistsSuccess() {
        // The service must remain strict even with Spring/Jackson default reader settings.
        var ordinaryMapper=new ObjectMapper().findAndRegisterModules();
        rag=new RagService(documents,new RagProperties("http://not-used","test","http://not-used/v1","fake","fake","http://not-used/v1","fake","fake",0,5,5,700,16000,800,100,0.6),new DocumentParser(),gateway,ordinaryMapper);
        services.add(rag); conversations=new ConversationService(repository,rag);
        String id=id(); send(id,"第一问");
        for(String raw:List.of("not json","{}","{\"question\":\"\"}","{\"question\":\"x\",\"answer\":\"假的\"}","{\"question\":null}","{\"question\":\"x\"} trailing","{\"question\":\"a\",\"question\":\"b\"}")) {
            gateway.rewriteRaw=raw; int embedded=gateway.embedded.size();
            assertThatThrownBy(() -> send(id,"追问")).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code).isEqualTo("INVALID_REWRITE_RESPONSE"));
            assertThat(gateway.embedded).hasSize(embedded); assertThat(conversations.get(id).exchanges()).hasSize(1);
        }
    }
    @Test void noMatchesReturnsUngroundedDespiteHistoricalAnswer() {
        String id=id(); send(id,"第一问"); gateway.candidates=List.of();
        Answer answer=send(id,"追问"); assertThat(answer.grounded()).isFalse(); assertThat(answer.sources()).isEmpty();
        assertThat(gateway.calls).hasSize(2); assertThat(conversations.get(id).exchanges()).hasSize(2);
    }
    @Test void deletedSourceIsHistoricalSnapshotOnlyAndNewAnswersCannotCiteIt() {
        String id=id(),request=requestId(); Answer first=conversations.send(id,new Message("第一问",request)); ready("other","other-chunk","其他有效依据"); rag.delete("doc");
        assertThat(conversations.get(id).exchanges().get(0).response()).isEqualTo(first);
        int before=gateway.calls.size(); assertThat(conversations.send(id,new Message("第一问",request))).isEqualTo(first); assertThat(gateway.calls).hasSize(before);
        assertThat(send(id,"旧主题追问").grounded()).isFalse();
        gateway.candidates=List.of("other-chunk"); gateway.answerRaw="{\"answer\":\"旧依据\",\"grounded\":true,\"citationIds\":[\"chunk\"]}";
        assertThatThrownBy(() -> send(id,"再次追问")).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code).isEqualTo("INVALID_MODEL_RESPONSE"));
        assertThat(conversations.get(id).exchanges()).hasSize(2);
        assertThat(lastAnswerPrompt().path("context").get(0).path("id").textValue()).isEqualTo("other-chunk");
        assertThat(lastAnswerPrompt().path("history").toString()).doesNotContain("当前有效文档原文","sources");
    }
    @Test void v2UpgradesExistingV1WithoutChangingKnowledgeDocumentsOrChunks() {
        var existing=new JdbcDataSource(); existing.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(existing).locations("classpath:db/migration").target("1").load().migrate();
        var oldDb=new JdbcTemplate(existing);
        var oldTx=new TransactionTemplate(new DataSourceTransactionManager(existing));
        var oldDocs=new RagRepository(oldDb,oldTx);
        oldDocs.insertDocument("preserved","preserved.txt","txt",5);
        oldDocs.insertChunks(List.of(new Chunk("preserved-chunk","preserved",1,"旧原文",null,null))); oldDocs.markReady("preserved",1);
        var before=oldDocs.getDocument("preserved"); var beforeChunks=oldDocs.chunks("preserved",0,10);
        Flyway.configure().dataSource(existing).locations("classpath:db/migration").load().migrate();
        assertThat(oldDocs.getDocument("preserved")).isEqualTo(before);
        assertThat(oldDocs.chunks("preserved",0,10)).isEqualTo(beforeChunks);
        var migrated=new ConversationRepository(oldDb,oldTx,json);
        assertThat(migrated.get(migrated.create().id()).exchanges()).isEmpty();
    }
    @Test void unrelatedConversationCompletesWhileAnotherConversationIsGenerating() throws Exception {
        String a=id(),b=id();
        while (Math.floorMod(a.hashCode(),256)==Math.floorMod(b.hashCode(),256)) b=id();
        final String other=b;
        gateway.entered=new CountDownLatch(1); var gate=new CountDownLatch(1); gateway.release=gate;
        var first=CompletableFuture.supplyAsync(() -> send(a,"会话A")); assertThat(gateway.entered.await(3,TimeUnit.SECONDS)).isTrue();
        // The first call captured its gate; subsequent gateway calls can finish independently.
        gateway.entered=null;
        try {
            var second=CompletableFuture.supplyAsync(() -> send(other,"会话B"));
            assertThat(second.get(2,TimeUnit.SECONDS).grounded()).isTrue(); assertThat(first).isNotDone();
            assertThat(conversations.get(other).exchanges()).extracting(Exchange::question).containsExactly("会话B");
        } finally { gate.countDown(); }
        first.get(3,TimeUnit.SECONDS);
    }
    record Call(String system,String user,JsonNode body) {}
    class FakeGateway implements ModelGateway {
        List<Call> calls=new CopyOnWriteArrayList<>(); List<String> embedded=new CopyOnWriteArrayList<>();
        volatile List<String> candidates=List.of("chunk"); volatile boolean fail;
        String rewritten="补全后的独立问题",rewriteRaw,answerRaw;
        volatile CountDownLatch entered,release;
        public List<Embedding> embed(List<String> text) { embedded.addAll(text); return text.stream().map(t -> Embedding.from(new float[]{1,0})).toList(); }
        public void index(List<Chunk> chunks,List<Embedding> vectors) {}
        public List<String> retrieve(Embedding vector) { return candidates; }
        public void remove(List<String> ids) {}
        public String generate(String system,String user) {
            try {
                JsonNode body=json.readTree(user); calls.add(new Call(system,user,body));
                if (fail) throw new ApiException(502,"UPSTREAM_FAILURE","模型替身失败");
                if (system.equals(RagService.REWRITE_SYSTEM)) return rewriteRaw!=null ? rewriteRaw : json.writeValueAsString(java.util.Map.of("question",rewritten));
                CountDownLatch entry=entered,gate=release;
                if (entry!=null) { entry.countDown(); if (!gate.await(3,TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timeout"); }
                return answerRaw!=null ? answerRaw : json.writeValueAsString(java.util.Map.of("answer","当前文档回答","grounded",true,"citationIds",List.of(body.path("context").get(0).path("id").textValue())));
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        }
    }
}
