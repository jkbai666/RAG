package local.rag;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual LangChain4j serialization over loopback HTTP. NOT a DeepSeek/DashScope connectivity test. */
class CloudModelProtocolTest {
    private static final String CHAT_KEY="loopback-chat-test-key";
    private static final String EMBEDDING_KEY="loopback-embedding-test-key";
    private final ObjectMapper json=new ObjectMapper();
    private final LinkedBlockingQueue<CapturedRequest> requests=new LinkedBlockingQueue<>();
    private HttpServer server;
    private String base;
    private volatile int responseStatus=200;
    private volatile int responseDimensions=1024;

    record CapturedRequest(String method,String path,String authorization,String contentType,JsonNode body) {}

    @BeforeEach void startLoopbackOnly() throws IOException {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",this::respond);
        server.start();
        base="http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void stopLoopback() { if (server!=null) server.stop(0); }

    private RagProperties properties(String chatKey,String embeddingKey) {
        return new RagProperties(base,"rag_qwen_v4_1024",base,chatKey,"deepseek-v4-flash",
            base+"/compatible-mode/v1",embeddingKey,"text-embedding-v4",1024,3,3,700,7000,800,100,0.6);
    }
    private LangChainGateway gateway() { return new LangChainGateway(properties(CHAT_KEY,EMBEDDING_KEY)); }
    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            JsonNode request=json.readTree(exchange.getRequestBody());
            requests.add(new CapturedRequest(exchange.getRequestMethod(),exchange.getRequestURI().toString(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"),request));
            Object response;
            if (responseStatus!=200) {
                response=Map.of("error",Map.of("message","UPSTREAM_PRIVATE_DETAIL "+CHAT_KEY+" "+EMBEDDING_KEY,"type","test_error"));
            } else if (exchange.getRequestURI().getPath().endsWith("/embeddings")) {
                List<Object> data=new ArrayList<>();
                for (int i=0;i<request.path("input").size();i++) {
                    List<Float> vector=new ArrayList<>(java.util.Collections.nCopies(responseDimensions,0.0f));
                    vector.set(0,Float.parseFloat(request.path("input").get(i).asText().substring("text-".length()))+1);
                    data.add(Map.of("object","embedding","index",i,"embedding",vector));
                }
                response=Map.of("object","list","model","text-embedding-v4","data",data,
                    "usage",Map.of("prompt_tokens",10,"total_tokens",10));
            } else {
                String answer="{\"answer\":\"loopback answer\",\"grounded\":true,\"citationIds\":[\"provided-id\"]}";
                response=Map.of("id","local-chat","object","chat.completion","created",1,"model","deepseek-v4-flash",
                    "choices",List.of(Map.of("index",0,"message",Map.of("role","assistant","content",answer),"finish_reason","stop")),
                    "usage",Map.of("prompt_tokens",10,"completion_tokens",5,"total_tokens",15));
            }
            byte[] bytes=json.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
            exchange.sendResponseHeaders(responseStatus,bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
    private CapturedRequest takeRequest() throws InterruptedException {
        CapturedRequest request=requests.poll(1,TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.contentType()).startsWith("application/json");
        return request;
    }

    @ParameterizedTest @ValueSource(ints={1,8,10,17})
    void qwenActualRequestsUseFloat1024DimensionsAndAtMostEightInputs(int count) throws Exception {
        List<String> texts=IntStream.range(0,count).mapToObj(i -> "text-"+i).toList();
        var vectors=gateway().embed(texts);
        assertThat(vectors).hasSize(count);
        for (int i=0;i<count;i++) {
            assertThat(vectors.get(i).vector()).hasSize(1024);
            assertThat(vectors.get(i).vector()[0]).isEqualTo((float)i+1);
        }
        List<String> serializedInputs=new ArrayList<>();
        for (int offset=0;offset<count;offset+=8) {
            CapturedRequest request=takeRequest();
            assertThat(request.path()).isEqualTo("/compatible-mode/v1/embeddings");
            assertThat(request.authorization()).isEqualTo("Bearer "+EMBEDDING_KEY);
            JsonNode body=request.body();
            assertThat(body.path("model").asText()).isEqualTo("text-embedding-v4");
            assertThat(body.path("dimensions").asInt()).isEqualTo(1024);
            assertThat(body.path("encoding_format").asText()).isEqualTo("float");
            assertThat(body.path("input").isArray()).isTrue();
            assertThat(body.path("input").size()).isEqualTo(Math.min(8,count-offset)).isLessThanOrEqualTo(10);
            body.path("input").forEach(input -> serializedInputs.add(input.asText()));
        }
        assertThat(serializedInputs).containsExactlyElementsOf(texts);
        assertThat(requests).isEmpty();
    }
    @Test void deepSeekActualRequestDisablesThinkingAndKeepsJsonObject() throws Exception {
        String user="{\"question\":\"test\",\"context\":[{\"id\":\"provided-id\",\"text\":\"loopback answer\"}]}";
        String answer=gateway().generate(RagService.SYSTEM,user);
        assertThat(json.readTree(answer).path("citationIds").get(0).asText()).isEqualTo("provided-id");
        CapturedRequest request=takeRequest();
        assertThat(request.path()).isEqualTo("/chat/completions");
        assertThat(request.authorization()).isEqualTo("Bearer "+CHAT_KEY);
        JsonNode body=request.body();
        assertThat(body.path("model").asText()).isEqualTo("deepseek-v4-flash");
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(700);
        assertThat(body.path("temperature").asDouble()).isZero();
        assertThat(body.path("messages").size()).isEqualTo(2);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo(RagService.SYSTEM);
        assertThat(body.path("messages").get(1).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo(user);
        assertThat(requests).isEmpty();
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ","\t"})
    void missingKeysRejectBeforeAnyHttpRequestOrFallback(String key) {
        var noChat=new LangChainGateway(properties(key,EMBEDDING_KEY));
        var noEmbedding=new LangChainGateway(properties(CHAT_KEY,key));
        assertThatThrownBy(() -> noChat.generate("JSON","test")).isInstanceOfSatisfying(ApiException.class,e -> {
            assertThat(e.status).isEqualTo(503); assertThat(e.code).isEqualTo("MODEL_NOT_CONFIGURED");
        });
        assertThatThrownBy(() -> noEmbedding.embed(List.of("text-0"))).isInstanceOfSatisfying(ApiException.class,e -> {
            assertThat(e.status).isEqualTo(503); assertThat(e.code).isEqualTo("MODEL_NOT_CONFIGURED");
        });
        assertThat(requests).isEmpty();
    }
    @ParameterizedTest @ValueSource(ints={401,429,500})
    void upstreamErrorsAreSanitizedWithoutRetriesOrFallback(int status) {
        responseStatus=status;
        assertThatThrownBy(() -> gateway().generate("JSON","test")).isInstanceOfSatisfying(ApiException.class,e -> {
            assertThat(e.status).isEqualTo(502); assertThat(e.code).isEqualTo("UPSTREAM_FAILURE");
            assertThat(e.getMessage()).doesNotContain("UPSTREAM_PRIVATE_DETAIL",CHAT_KEY,EMBEDDING_KEY);
        });
        assertThatThrownBy(() -> gateway().embed(List.of("text-0"))).isInstanceOfSatisfying(ApiException.class,e -> {
            assertThat(e.status).isEqualTo(502); assertThat(e.code).isEqualTo("UPSTREAM_FAILURE");
            assertThat(e.getMessage()).doesNotContain("UPSTREAM_PRIVATE_DETAIL",CHAT_KEY,EMBEDDING_KEY);
        });
        assertThat(requests).hasSize(2);
    }
    @Test void unexpectedEmbeddingDimensionsFailInsteadOfBeingAcceptedOrReplaced() {
        responseDimensions=3;
        assertThatThrownBy(() -> gateway().embed(List.of("text-0"))).isInstanceOfSatisfying(ApiException.class,e -> {
            assertThat(e.status).isEqualTo(502); assertThat(e.code).isEqualTo("UPSTREAM_FAILURE");
        });
        assertThat(requests).hasSize(1);
    }
}
