package local.rag;

import static local.rag.ApiTypes.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

// Isolated, in-memory SQL only; never loads the application's datasource or model configuration.
class ChunkingMetadataTest {
    JdbcTemplate db;
    RagRepository repo;

    @BeforeEach void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        try (var connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V1__initial.sql"));
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V2__conversations.sql"));
        }
        db.update("INSERT INTO rag_documents(id,file_name,format,size_bytes,status,chunk_count,created_at) VALUES('legacy','old.txt','txt',8,'READY',1,?)",
                Timestamp.from(Instant.parse("2026-10-20T00:00:00Z")));
        db.update("INSERT INTO rag_chunks(id,document_id,chunk_index,text,page_start,page_end) VALUES('old-chunk','legacy',1,'旧原文',2,3)");
        db.update("INSERT INTO rag_settings(setting_key,setting_value) VALUES('embedding_profile','old-profile')");
        db.update("INSERT INTO rag_conversations(id,created_at) VALUES('conversation',?)",Timestamp.from(Instant.now()));
        db.update("INSERT INTO rag_conversation_exchanges(conversation_id,request_id,question,response_json) VALUES('conversation','request','旧问题','{}')");
        try (var connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V3__document_chunking_metadata.sql"));
        }
        repo = new RagRepository(db,new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    @Test void migrationLabelsLegacyWithoutRewritingContentIndexesOrHistory() {
        Document old = repo.getDocument("legacy");
        assertThat(old.chunkingStrategy()).isEqualTo("LEGACY");
        assertThat(old.chunkingFallbackReason()).isNull();
        assertThat(old.status()).isEqualTo("READY");
        assertThat(old.createdAt()).isEqualTo(Instant.parse("2026-10-20T00:00:00Z"));
        assertThat(repo.chunks("legacy",0,20)).containsExactly(new Chunk("old-chunk","legacy",1,"旧原文",2,3));
        assertThat(repo.readySource("old-chunk").text()).isEqualTo("旧原文");
        assertThat(db.queryForObject("SELECT setting_value FROM rag_settings WHERE setting_key='embedding_profile'",String.class)).isEqualTo("old-profile");
        assertThat(db.queryForObject("SELECT question FROM rag_conversation_exchanges WHERE request_id='request'",String.class)).isEqualTo("旧问题");
    }

    @Test void newImportsExplicitlyStartWithUnknownStrategyDespiteLegacyDefault() {
        Document pending = repo.insertDocument("new","new.txt","txt",10);
        assertThat(pending.status()).isEqualTo("PROCESSING");
        assertThat(pending.chunkingStrategy()).isNull();
        assertThat(pending.chunkingFallbackReason()).isNull();
        assertThat(repo.listDocuments()).anySatisfy(doc -> {
            assertThat(doc.id()).isEqualTo("new");
            assertThat(doc.chunkingStrategy()).isNull();
        });
    }

    @Test void selectedStrategyAndFallbackSurviveReadyAndFailureTransitions() {
        repo.insertDocument("fallback","fallback.txt","txt",10);
        repo.setChunking("fallback","RULE","AI_CHUNKING_TIMEOUT");
        repo.markReady("fallback",3);
        assertThat(repo.getDocument("fallback").chunkingStrategy()).isEqualTo("RULE");
        assertThat(repo.getDocument("fallback").chunkingFallbackReason()).isEqualTo("AI_CHUNKING_TIMEOUT");
        repo.markFailed("fallback","向量索引失败");
        assertThat(repo.getDocument("fallback").chunkingFallbackReason()).isEqualTo("AI_CHUNKING_TIMEOUT");
    }

    @Test void directRuleHasNoReasonAndAiSuccessClearsFallback() {
        repo.insertDocument("rule","rule.txt","txt",10);
        repo.setChunking("rule","RULE",null);
        assertThat(repo.getDocument("rule").chunkingFallbackReason()).isNull();
        repo.insertDocument("ai","ai.txt","txt",10);
        repo.setChunking("ai","LLM","stale-reason");
        assertThat(repo.getDocument("ai").chunkingStrategy()).isEqualTo("LLM");
        assertThat(repo.getDocument("ai").chunkingFallbackReason()).isNull();
    }

    @Test void finishedImportsAndInvalidStrategiesCannotBeRelabelled() {
        assertThatThrownBy(() -> repo.setChunking("legacy","LLM",null)).isInstanceOf(IllegalStateException.class);
        repo.insertDocument("new","new.txt","txt",10);
        assertThatThrownBy(() -> repo.setChunking("new","LEGACY",null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.getDocument("new").chunkingStrategy()).isNull();
    }

    @Test void fallbackMetadataCannotPersistUpstreamMessagesOrUnboundedText() {
        repo.insertDocument("unsafe","unsafe.txt","txt",10);
        for (String unsafe : new String[] {"upstream response contains private text", "X".repeat(500), "MODEL_ERROR\nbody"}) {
            repo.setChunking("unsafe","RULE",unsafe);
            assertThat(repo.getDocument("unsafe").chunkingFallbackReason()).isEqualTo("AI_CHUNKING_FAILED");
        }
        repo.setChunking("unsafe","RULE","  ");
        assertThat(repo.getDocument("unsafe").chunkingFallbackReason()).isNull();
    }

    @Test void oldDocumentConstructorAndJsonFieldsRemainCompatible() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        Document old = new Document("old","old.txt","txt",1,"READY",1,Instant.now(),null);
        assertThat(old.chunkingStrategy()).isNull();
        assertThat(old.chunkingFallbackReason()).isNull();
        var pendingJson = mapper.readTree(mapper.writeValueAsBytes(old));
        assertThat(pendingJson.get("chunkingStrategy").isNull()).isTrue();
        assertThat(pendingJson.get("chunkingFallbackReason").isNull()).isTrue();
        var legacyJson = mapper.readTree(mapper.writeValueAsBytes(repo.getDocument("legacy")));
        assertThat(legacyJson.get("chunkingStrategy").textValue()).isEqualTo("LEGACY");
    }
}
