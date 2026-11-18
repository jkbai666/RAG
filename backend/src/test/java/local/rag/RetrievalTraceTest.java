package local.rag;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Observe the production selection path, not a separate evaluation implementation. */
class RetrievalTraceTest {
    @Test void traceSeparatesRetrievedCandidatesFromCompleteContextAndIsImmutable() throws Exception {
        check(false);
    }
    @Test void emptyRetrievalStillEmitsTraceWithoutCallingGenerator() throws Exception {
        check(true);
    }
    private void check(boolean empty) throws Exception {
        var ds=new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:trace_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        var db=new JdbcTemplate(ds);
        var repo=new RagRepository(db,new TransactionTemplate(new DataSourceTransactionManager(ds)));
        var json=new ObjectMapper().findAndRegisterModules();
        repo.insertDocument("doc","trace.txt","txt",100);
        var chunks=IntStream.range(0,5).mapToObj(i -> new ApiTypes.Chunk("c"+i,"doc",i+1,
            "完整证据。".repeat(100),null,null)).toList();
        repo.insertChunks(chunks); repo.markReady("doc",5);
        var gateway=new ModelGateway() {
            public List<Embedding> embed(List<String> texts) { return List.of(Embedding.from(new float[]{1,0})); }
            public void index(List<ApiTypes.Chunk> c,List<Embedding> e) { throw new AssertionError(); }
            public List<String> retrieve(Embedding e) { return empty?List.of():chunks.stream().map(ApiTypes.Chunk::id).toList(); }
            public void remove(List<String> ids) { throw new AssertionError(); }
            public String generate(String system,String user) {
                assertThat(empty).isFalse();
                try {
                    var context=json.readTree(user).get("context");
                    return json.writeValueAsString(java.util.Map.of("answer","完整证据。","grounded",true,
                        "citationIds",List.of(context.get(0).get("id").asText())));
                } catch (Exception e) { throw new AssertionError(e); }
            }
        };
        var p=new RagProperties("http://127.0.0.1:18000","trace","http://chat","fake","chat",
            "http://embed","fake","embed",2,3,3,700,4000,800,100,0.6);
        var service=new RagService(repo,p,new DocumentParser(),gateway,json);
        try {
            var observed=new AtomicReference<RagService.RetrievalTrace>();
            var answer=service.chat("证据是什么？",List.of(),observed::set);
            var trace=observed.get(); assertThat(trace).isNotNull();
            if (empty) {
                assertThat(trace.retrieved()).isEmpty(); assertThat(trace.context()).isEmpty();
                assertThat(answer.grounded()).isFalse();
            } else {
                assertThat(trace.retrieved()).hasSize(5);
                assertThat(trace.context()).isNotEmpty().hasSizeLessThan(5);
                assertThat(trace.retrieved()).containsAll(trace.context());
                assertThat(trace.context()).containsAll(answer.sources());
                assertThat(answer.grounded()).isTrue();
                assertThatThrownBy(() -> trace.context().clear()).isInstanceOf(UnsupportedOperationException.class);
            }
        } finally { service.shutdown(); db.execute("SHUTDOWN"); }
    }
}
