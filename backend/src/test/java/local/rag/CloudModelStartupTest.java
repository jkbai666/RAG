package local.rag;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** Full application startup with isolated H2; all upstream addresses are loopback, never the cloud. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:cloud_startup;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "rag.chat-base-url=http://127.0.0.1:1","rag.embedding-base-url=http://127.0.0.1:1/compatible-mode/v1",
    "rag.chroma-base-url=http://127.0.0.1:1","rag.chat-api-key=","rag.embedding-api-key="
})
@AutoConfigureMockMvc
class CloudModelStartupTest {
    @Autowired MockMvc mvc;
    @Autowired RagProperties properties;
    @Autowired RagRepository repo;
    @Autowired JdbcTemplate db;
    @MockitoSpyBean LangChainGateway gateway;

    @Test void emptyKeysAllowStartupAndHealthButRejectModelWorkBeforeLegacyProfileOrHttp() throws Exception {
        assertThat(properties.modelConfigured()).isFalse();
        // Keep the existing contract order: an empty knowledge base returns 409 even with missing keys.
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"empty?\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMPTY_KNOWLEDGE_BASE"));
        repo.ensureProfile("preserved-legacy-index-profile");
        repo.insertDocument("legacy-document","legacy.md","md",20);
        repo.insertChunks(List.of(new ApiTypes.Chunk("legacy-chunk","legacy-document",1,"existing text",null,null)));
        repo.markReady("legacy-document",1);

        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/knowledge/status")).andExpect(status().isOk())
            .andExpect(jsonPath("$.modelConfigured").value(false)).andExpect(jsonPath("$.totalDocuments").value(1));
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file","new.txt","text/plain",
            "must not import".getBytes(StandardCharsets.UTF_8))))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MODEL_NOT_CONFIGURED"));
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"existing text?\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MODEL_NOT_CONFIGURED"));

        verifyNoInteractions(gateway);
        assertThat(repo.total()).isEqualTo(1);
        assertThat(repo.chunkCount("legacy-document")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT setting_value FROM rag_settings WHERE setting_key='embedding_profile'",String.class))
            .isEqualTo("preserved-legacy-index-profile");
    }
}
