package local.rag;

import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

/** Binds the shipped YAML without loading private env files or contacting model services. */
class CloudModelConfigurationTest {
    private RagProperties bind(Map<String,Object> legacy) throws IOException {
        var sources=new MutablePropertySources();
        sources.addLast(new MapPropertySource("synthetic-legacy-env",legacy));
        // Always test with empty keys, even after a developer fills the YAML locally.
        sources.addLast(new MapPropertySource("isolated-test-keys",Map.of("rag.chat-api-key","","rag.embedding-api-key","")));
        for (var source:new YamlPropertySourceLoader().load("application",new ClassPathResource("application.yml"))) {
            for (String key:java.util.List.of("rag.chat-api-key","rag.embedding-api-key")) {
                Object value=source.getProperty(key);
                // Assert only booleans so a locally filled real key can never appear in a failure report.
                assertThat(value instanceof String).as("YAML key slot must be a literal string").isTrue();
                assertThat(((String)value).contains("${")).as("YAML keys must not reference legacy env variables").isFalse();
            }
            sources.addLast(source);
        }
        return new Binder(ConfigurationPropertySources.from(sources),new PropertySourcesPlaceholdersResolver(sources))
            .bind("rag",Bindable.of(RagProperties.class)).orElseThrow(IllegalStateException::new);
    }
    private void assertCloudDefaults(RagProperties p) {
        assertThat(p.chatBaseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(p.chatModel()).isEqualTo("deepseek-v4-flash");
        assertThat(p.embeddingBaseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(p.embeddingModel()).isEqualTo("text-embedding-v4");
        assertThat(p.embeddingDimensions()).isEqualTo(1024);
        assertThat(p.chromaCollection()).isEqualTo("rag_qwen_v4_1024");
        assertThat(p.chunkSize()).isEqualTo(800);
        assertThat(p.chunkOverlap()).isEqualTo(100);
        // Boolean assertions avoid ever echoing a locally filled real key in failure reports.
        assertThat(p.chatApiKey()!=null && p.chatApiKey().isEmpty()).as("isolated DeepSeek key must be empty").isTrue();
        assertThat(p.embeddingApiKey()!=null && p.embeddingApiKey().isEmpty()).as("isolated Qwen key must be empty").isTrue();
        assertThat(p.modelConfigured()).isFalse();
        assertThat(p.toString()).isEqualTo("RagProperties[credentials redacted]");
    }
    @Test void yamlHasApprovedCloudDefaultsAndLiteralKeySlots() throws Exception {
        assertCloudDefaults(bind(Map.of()));
    }
    @Test void legacyModelAndIndexVariablesCannotReplaceYamlCloudDefaultsOrEmptyKeys() throws Exception {
        var legacy=Map.<String,Object>ofEntries(
            Map.entry("CHAT_BASE_URL","http://127.0.0.1:8091/v1"),Map.entry("CHAT_MODEL","local-chat"),
            Map.entry("CHAT_API_KEY","synthetic-legacy-chat-key"),
            Map.entry("EMBEDDING_BASE_URL","http://127.0.0.1:8092/v1"),Map.entry("EMBEDDING_MODEL","local-embedding"),
            Map.entry("EMBEDDING_API_KEY","synthetic-legacy-embedding-key"),Map.entry("EMBEDDING_DIMENSIONS","512"),
            Map.entry("CHROMA_COLLECTION","rag_mvp"),Map.entry("CHUNK_SIZE","300"),Map.entry("CHUNK_OVERLAP","50"));
        assertCloudDefaults(bind(legacy));
    }
}
