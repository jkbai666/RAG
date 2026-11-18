package local.rag;

import static local.rag.EvaluationDataset.require;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class EvaluationConfig {
    private static final Set<String> CHAT_ENDPOINTS = Set.of("https://api.deepseek.com", "https://api.deepseek.com/v1");
    private static final Set<String> EMBED_ENDPOINTS = Set.of("https://dashscope.aliyuncs.com/compatible-mode/v1",
        "https://dashscope-intl.aliyuncs.com/compatible-mode/v1");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?}");
    private final Binder binder;
    private final RagProperties base;
    final JsonNode price;
    final EvaluationBudget.Rates rates;
    final String priceHash;

    EvaluationConfig(Path project, String chroma, Path priceFile, ObjectMapper json) throws Exception {
        validateChroma(chroma);
        Map<String, String> env = new HashMap<>();
        Path appEnv = project.resolve(".local/app.env");
        if (Files.exists(appEnv)) for (String line : Files.readAllLines(appEnv)) {
            String stripped = line.strip();
            int eq = stripped.indexOf('=');
            if (!stripped.startsWith("#") && eq > 0) {
                String value = stripped.substring(eq + 1).strip();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
                env.put(stripped.substring(0, eq).strip(), value);
            }
        }
        LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(30);
        Object yaml;
        try (var reader = Files.newBufferedReader(project.resolve("backend/src/main/resources/application.yml"))) {
            yaml = new Yaml(new SafeConstructor(options)).load(reader);
        } catch (RuntimeException e) { throw new IllegalArgumentException("Cannot safely read application YAML"); }
        require(yaml instanceof Map<?, ?> && ((Map<?, ?>) yaml).get("rag") instanceof Map<?, ?>, "Missing rag configuration");
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten("rag", ((Map<?, ?>) yaml).get("rag"), env, flat);
        flat.put("rag.chroma-base-url", chroma); flat.put("rag.chroma-collection", "evaluation_unassigned");
        binder = new Binder(new MapConfigurationPropertySource(flat));
        try { base = binder.bind("rag", Bindable.of(RagProperties.class)).orElseThrow(() -> new IllegalArgumentException("Missing rag properties")); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid RAG configuration (values suppressed)"); }
        require(base.modelConfigured(), "Model keys/configuration missing");
        require(CHAT_ENDPOINTS.contains(normalize(base.chatBaseUrl())) && "deepseek-v4-flash".equals(base.chatModel()),
            "Unconfirmed DeepSeek endpoint/model");
        require(EMBED_ENDPOINTS.contains(normalize(base.embeddingBaseUrl())) && "text-embedding-v4".equals(base.embeddingModel()),
            "Unconfirmed Qwen endpoint/model");
        byte[] bytes = Files.readAllBytes(priceFile); priceHash = EvaluationDataset.hash(bytes); price = json.readTree(bytes);
        require(price.path("schemaVersion").asInt() == 1 && price.path("confirmed").asBoolean()
            && price.path("currency").asText().equals("CNY")
            && price.path("unit").asText().equals("per_million_tokens"), "Unconfirmed price file");
        validatePriceDate(price.path("verifiedAt").asText(), LocalDate.now(ZoneOffset.UTC));
        require(price.path("sources").isArray() && price.path("sources").toString().contains("https://api-docs.deepseek.com/zh-cn/quick_start/pricing/")
            && price.path("sources").toString().contains("https://help.aliyun.com/zh/model-studio/text-embedding-v4"), "Missing official price sources");
        rates = new EvaluationBudget.Rates(decimal(price, "deepseekInputCacheMiss"), decimal(price, "deepseekOutput"), decimal(price, "qwenInput"));
    }
    private static BigDecimal decimal(JsonNode price, String key) {
        JsonNode n = price.path("maxRates").path(key);
        require(n.isNumber(), "Missing maximum price rate"); return n.decimalValue();
    }
    static void validatePriceDate(String value, LocalDate todayUtc) {
        LocalDate verified = LocalDate.parse(value);
        long age = ChronoUnit.DAYS.between(verified, todayUtc);
        require(verified.toString().equals(value) && age >= 0 && age <= 1, "Price verification must be today or yesterday in UTC");
    }
    static String normalize(String value) { return value.replaceAll("/+$", ""); }
    static void validateChroma(String value) {
        URI uri = URI.create(value);
        require("http".equals(uri.getScheme()) && Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())
            && uri.getPort() > 0 && uri.getPort() != 8000 && uri.getRawUserInfo() == null
            && (uri.getPath().isEmpty() || uri.getPath().equals("/")) && uri.getQuery() == null && uri.getFragment() == null,
            "Chroma must use an isolated explicit loopback port other than 8000");
    }
    static void flatten(String prefix, Object value, Map<String, String> env, Map<String, Object> flat) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) flatten(prefix + "." + entry.getKey(), entry.getValue(), env, flat);
        } else if (value != null) {
            String raw = value.toString(); Matcher matcher = PLACEHOLDER.matcher(raw); StringBuffer resolved = new StringBuffer();
            while (matcher.find()) {
                String replacement = env.getOrDefault(matcher.group(1), matcher.group(2));
                require(replacement != null, "Unresolved YAML placeholder (value suppressed)");
                matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(resolved); require(!resolved.toString().contains("${"), "Unsupported YAML placeholder");
            flat.put(prefix, resolved.toString());
        }
    }
    RagProperties properties(String collection) {
        return new RagProperties(base.chromaBaseUrl(), collection, base.chatBaseUrl(), base.chatApiKey(), base.chatModel(),
            base.embeddingBaseUrl(), base.embeddingApiKey(), base.embeddingModel(), base.embeddingDimensions(),
            base.timeoutSeconds(), base.chromaTimeoutSeconds(), base.chatMaxOutputTokens(), base.chatContextBudget(),
            base.chunkSize(), base.chunkOverlap(), base.minScore());
    }
    ChunkingProperties chunking(String arm) {
        ChunkingProperties p = new ChunkingProperties();
        binder.bind("rag.chunking", Bindable.ofInstance(p));
        p.setStrategy(ChunkingProperties.Strategy.valueOf(arm.equals("A") ? "RULE" : "LLM"));
        return p;
    }
    Map<String, Object> publicSettings() {
        return Map.of("chatModel", base.chatModel(), "embeddingModel", base.embeddingModel(),
            "embeddingDimensions", base.embeddingDimensions(), "minScore", base.minScore(), "topK", 5,
            "chatMaxOutputTokens", base.chatMaxOutputTokens(), "chatContextBudget", base.chatContextBudget(),
            "chunkSize", base.chunkSize(), "chunkOverlap", base.chunkOverlap(), "timeoutSeconds", base.timeoutSeconds());
    }
}
