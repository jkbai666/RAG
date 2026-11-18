package local.rag;

import static local.rag.EvaluationDataset.require;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import local.rag.ApiTypes.Answer;
import local.rag.ApiTypes.Chunk;
import local.rag.ApiTypes.Source;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Standalone test-classpath entry point: no SpringApplication, production DB, or ordinary KB. */
public final class RagEvaluationMain {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private final Path project, run;
    private final Map<String, Object> state = new LinkedHashMap<>();
    private final List<Map<String, Object>> collectionRecords = new ArrayList<>();
    private final Map<String, Embedding> queryCache = new HashMap<>();
    private final List<Map<String, Object>> blind = new ArrayList<>(), mapping = new ArrayList<>();
    private final java.security.SecureRandom random = new java.security.SecureRandom();
    private EvaluationBudget budget;
    private EvaluationDataset dataset;

    private RagEvaluationMain(Path project, Path run) { this.project = project; this.run = run; }
    public static void main(String[] args) {
        // Third-party exception logging may contain HTTP bodies. Only structured safe diagnostics leave this process.
        if (org.slf4j.LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext logs) {
            logs.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.OFF);
        }
        int code;
        try { code = execute(args); }
        catch (Exception e) { System.err.println("Evaluation could not start; " + safeError(e)); code = 2; }
        if (code != 0) System.exit(code);
    }
    static int execute(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        if (options.containsKey("help")) {
            System.out.println("RagEvaluationMain [--offline | --live --price-file FILE] [--project-root DIR] [--dataset DIR]"
                + " [--chroma-url http://127.0.0.1:18000] [--preflight] [--regression-evidence FILE]");
            return 0;
        }
        boolean live = options.containsKey("live");
        require(!(live && options.containsKey("offline")), "Choose offline or live");
        require(!live || options.containsKey("price-file"), "Live requires an explicit confirmed --price-file");
        require(live || !options.containsKey("preflight"), "Preflight requires --live");
        Path project = Path.of(options.getOrDefault("project-root", ".")).toAbsolutePath().normalize();
        Path evaluation = project.resolve("evaluation"); Files.createDirectories(evaluation.resolve("runs"));
        String id = Instant.now().toString().replaceAll("[^0-9]", "") + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path run = evaluation.resolve("runs").resolve(id); Files.createDirectory(run);
        RagEvaluationMain runner = new RagEvaluationMain(project, run);
        runner.state.put("schemaVersion", 1); runner.state.put("runId", id); runner.state.put("startedAt", Instant.now().toString());
        runner.state.put("mode", live ? "live" : "offline"); runner.state.put("status", "incomplete");
        runner.state.put("acquisitionComplete", false); runner.state.put("reviewComplete", false);
        runner.state.put("cleanupComplete", true); runner.state.put("collections", runner.collectionRecords);
        runner.state.put("regressionComplete", false); runner.saveState();
        try {
            runner.snapshotProduction();
            runner.dataset = new EvaluationDataset(Path.of(options.getOrDefault("dataset", evaluation.resolve("dataset").toString())), JSON);
            runner.snapshotDataset();
            if (options.containsKey("regression-evidence")) {
                Path proof = Path.of(options.get("regression-evidence"));
                JsonNode evidence = JSON.readTree(Files.readAllBytes(proof));
                write(run.resolve("regression.json"), evidence);
                runner.state.put("regressionComplete", evidence.path("passed").asBoolean() && evidence.path("tests").asInt() > 0
                    && evidence.path("failures").asInt(-1) == 0 && evidence.path("errors").asInt(-1) == 0);
            }
            if (live) runner.live(options, evaluation.resolve("runs/.cost-ledger.lock")); else runner.offline();
        } catch (Exception e) {
            runner.state.put("error", safeError(e));
            runner.state.put("status", "incomplete");
        } finally {
            if (runner.budget != null) {
                runner.state.put("accountedRmb", runner.budget.charged());
                runner.state.put("runAccountedRmb", runner.budget.runSpent());
                runner.state.put("deepSeekCalls", runner.budget.chatCalls()); runner.state.put("qwenCalls", runner.budget.embeddingCalls());
                runner.state.put("budgetBlocked", runner.budget.blocked()); runner.state.put("budgetBlockedReason", runner.budget.blockedReason());
                try { runner.budget.close(); } catch (Exception e) { runner.state.put("error", "ledger_close_failure"); }
            }
            runner.writeBlind(); runner.state.put("finishedAt", Instant.now().toString()); runner.saveState();
        }
        System.out.println("Evaluation artifacts: " + run);
        System.out.println("Status: " + runner.state.get("status") + "; no quality pass without independent review and regression.");
        return runner.state.containsKey("error") ? 2 : 0;
    }
    static Map<String, String> parse(String[] args) {
        var result = new LinkedHashMap<String, String>();
        var flags = java.util.Set.of("live", "offline", "preflight", "help");
        var values = java.util.Set.of("project-root", "dataset", "chroma-url", "price-file", "regression-evidence");
        for (int i = 0; i < args.length; i++) {
            require(args[i].startsWith("--"), "Expected named argument"); String key = args[i].substring(2);
            require(flags.contains(key) || values.contains(key), "Unknown evaluation option");
            require(!result.containsKey(key), "Duplicate evaluation option");
            if (flags.contains(key)) result.put(key, "true");
            else { require(i + 1 < args.length && !args[i + 1].startsWith("--"), "Missing evaluation option value"); result.put(key, args[++i]); }
        }
        return result;
    }
    private void snapshotDataset() throws Exception {
        Files.copy(dataset.root.resolve("manifest.json"), run.resolve("manifest.json"));
        Files.copy(dataset.root.resolve("freeze.json"), run.resolve("freeze.json"));
        if (Files.exists(dataset.root.resolve("pages.json"))) Files.copy(dataset.root.resolve("pages.json"), run.resolve("pages.json"));
        state.put("datasetVerified", true);
        state.put("manifestSha256", dataset.manifestHash); state.put("freezeSha256", dataset.freezeHash);
        state.put("expectedDocuments", dataset.documents.size() * 2); state.put("expectedQuestions", dataset.questions.size() * 2);
        state.put("seed", 20261118); state.put("armA", "RULE"); state.put("armB", "LLM"); saveState();
    }
    private void snapshotProduction() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String name : List.of("ChunkingService", "DocumentParser", "RagService", "LangChainGateway", "ModelGateway")) {
            String relative = "backend/src/main/java/local/rag/" + name + ".java";
            sources.put(relative, EvaluationDataset.hash(Files.readAllBytes(project.resolve(relative))));
        }
        state.put("productionSourceSha256", sources);
        Map<String, String> prompts = Map.of("chunkSystem", ChunkingService.SYSTEM, "answerSystem", RagService.SYSTEM,
            "rewriteSystem", RagService.REWRITE_SYSTEM);
        Map<String, String> hashes = new LinkedHashMap<>();
        for (var entry : prompts.entrySet()) hashes.put(entry.getKey(), EvaluationDataset.hash(entry.getValue().getBytes(StandardCharsets.UTF_8)));
        write(run.resolve("prompts.json"), Map.of("schemaVersion", 1, "systems", prompts, "sha256", hashes));
        state.put("promptSha256", hashes); saveState();
    }
    private void offline() throws Exception {
        ModelGateway forbidden = new ModelGateway() {
            private IllegalStateException no() { return new IllegalStateException("Offline prohibits HTTP"); }
            public List<Embedding> embed(List<String> t) { throw no(); }
            public void index(List<Chunk> c, List<Embedding> v) { throw no(); }
            public List<String> retrieve(Embedding e) { throw no(); }
            public void remove(List<String> ids) { throw no(); }
            public String generate(String s, String u) { throw no(); }
        };
        DocumentParser parser = new DocumentParser();
        for (var d : dataset.documents) {
            long start = System.nanoTime();
            byte[] bytes = Files.readAllBytes(dataset.file(d.path(), "documents"));
            var result = new ChunkingService(parser, forbidden, new ChunkingProperties(), JSON).chunk(d.id(), d.format(), bytes, 800, 100);
            List<EvaluationMetrics.Span> spans = spans(result);
            EvaluationMetrics.validateCoverage(d.id(), EvaluationDataset.extract(parser, d.format(), bytes), result.chunks(), spans, 800, 100);
            documentArtifact(d, "A", result, spans, elapsed(start), "offline_validated", null);
        }
        state.put("offlineCoverageComplete", true); state.put("status", "offline_validated");
    }
    private void live(Map<String, String> options, Path lock) throws Exception {
        var config = new EvaluationConfig(project, options.getOrDefault("chroma-url", "http://127.0.0.1:18000"),
            Path.of(options.get("price-file")), JSON);
        // Verify trace API and all migrations before making even a preflight request.
        RagService.class.getMethod("chat", String.class, List.class, Consumer.class);
        for (int v = 1; v <= 3; v++) {
            final String prefix = "V" + v + "__";
            try (var paths = Files.list(project.resolve("backend/src/main/resources/db/migration"))) {
                require(paths.anyMatch(p -> p.getFileName().toString().startsWith(prefix)), "Required V1/V2/V3 migration missing");
            }
        }
        write(run.resolve("pricing.json"), config.price); state.put("pricingSha256", config.priceHash);
        state.put("settings", config.publicSettings());
        state.put("chunkingSettingsA", config.chunking("A")); state.put("chunkingSettingsB", config.chunking("B"));
        budget = new EvaluationBudget(lock, run.resolve("cost-ledger.jsonl"), config.rates, lock.getParent());
        state.put("budgetScope", "all_live_runs_in_this_evaluation_directory");
        state.put("priorAccountedRmb", budget.priorSpent()); state.put("priorDeepSeekCalls", budget.priorChatCalls());
        state.put("priorQwenCalls", budget.priorEmbeddingCalls()); state.put("stopNewRequestsRmb", EvaluationBudget.STOP);
        state.put("totalCapRmb", EvaluationBudget.TOTAL_CAP); budget.ensureOpen();
        saveState();
        if (options.containsKey("preflight")) {
            budget.labels("preflight", "shared", "", "");
            var gateway = new LangChainGateway(config.properties("evaluation_preflight_unused"), budget);
            long at = System.nanoTime();
            try {
                gateway.generate("Return only a JSON object.", "{\"ping\":true}"); budget.ensureOpen();
                gateway.embed(List.of("evaluation connectivity check")); budget.ensureOpen();
                write(run.resolve("preflight.json"), Map.of("passed", true, "latencyMs", elapsed(at)));
            } finally { budget.failedRequest(); }
        }
        for (String split : List.of("dev", "validation")) {
            for (String arm : List.of("A", "B")) {
                budget.ensureOpen(); runArm(config, split, arm);
            }
        }
        budget.ensureOpen();
        boolean questionsOk = !Boolean.TRUE.equals(state.get("questionExecutionFailed"));
        state.put("acquisitionComplete", questionsOk); state.put("status", questionsOk ? "awaiting_review" : "incomplete");
    }
    private void runArm(EvaluationConfig config, String split, String arm) throws Exception {
        String collection = "eval_" + UUID.randomUUID().toString().replace("-", "");
        RagProperties p = config.properties(collection);
        var record = new LinkedHashMap<String, Object>(); record.put("name", collection); record.put("split", split);
        record.put("arm", arm); record.put("created", false); record.put("cleaned", false);
        collectionRecords.add(record); state.put("cleanupComplete", false); saveState();
        var chroma = new TemporaryCollection(p, collection);
        var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:eval_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate db = new JdbcTemplate(ds);
        RagService service = null;
        try {
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
            RagRepository repo = new RagRepository(db, new TransactionTemplate(new DataSourceTransactionManager(ds)));
            DocumentParser parser = new DocumentParser();
            CachedGateway gateway = new CachedGateway(new LangChainGateway(p, budget), budget, queryCache);
            ChunkingService chunker = new ChunkingService(parser, gateway, config.chunking(arm), JSON);
            chroma.create(); record.put("created", true); saveState();
            Map<String, EvaluationMetrics.Span> allSpans = new HashMap<>();
            service = new RagService(repo, p, parser, gateway, JSON);
            boolean importFailure = false;
            for (var d : dataset.documents.stream().filter(d -> d.split().equals(split)).toList()) {
                budget.labels(split, arm, d.id(), ""); budget.ensureOpen(); long start = System.nanoTime();
                ChunkingService.Result result = null; List<EvaluationMetrics.Span> docSpans = List.of();
                try {
                    byte[] bytes = Files.readAllBytes(dataset.file(d.path(), "documents"));
                    result = chunker.chunk(d.id(), d.format(), bytes, p.chunkSize(), p.chunkOverlap());
                    // Chunker may swallow a budget rejection as a RULE fallback. Check the latched state before embedding.
                    budget.failedRequest(); budget.ensureOpen();
                    docSpans = spans(result);
                    EvaluationMetrics.validateCoverage(d.id(), EvaluationDataset.extract(parser, d.format(), bytes),
                        result.chunks(), docSpans, p.chunkSize(), p.chunkOverlap());
                    repo.insertDocument(d.id(), Path.of(d.path()).getFileName().toString(), d.format(), bytes.length);
                    repo.setChunking(d.id(), result.strategy(), result.fallbackReason());
                    repo.insertChunks(result.chunks());
                    gateway.index(result.chunks(), gateway.embed(result.chunks().stream().map(Chunk::text).toList()));
                    budget.ensureOpen(); repo.markReady(d.id(), result.chunks().size());
                    docSpans.forEach(s -> allSpans.put(s.chunkId(), s));
                    documentArtifact(d, arm, result, docSpans, elapsed(start), "ready", null);
                } catch (Exception e) {
                    budget.failedRequest(); documentArtifact(d, arm, result, docSpans, elapsed(start), "failed", safeError(e));
                    importFailure = true; budget.ensureOpen();
                }
            }
            // A split must be fully imported: evaluating a reduced corpus would create a false comparison.
            require(!importFailure, "Incomplete split corpus; questions were not evaluated");
            for (var q : dataset.questions.stream().filter(q -> q.split().equals(split)).toList()) {
                budget.labels(split, arm, q.documentId(), q.id()); budget.ensureOpen();
                Map<String, Object> row = new LinkedHashMap<>(); row.put("questionId", q.id()); row.put("documentId", q.documentId());
                row.put("split", split); row.put("arm", arm); row.put("question", q.question()); row.put("gold", q);
                AtomicReference<RagService.RetrievalTrace> trace = new AtomicReference<>(); long start = System.nanoTime(); gateway.questionMode = true;
                try {
                    Answer answer = service.chat(q.question(), List.of(), trace::set); budget.ensureOpen();
                    require(trace.get() != null, "Production did not emit retrieval trace");
                    List<Source> retrieved = trace.get().retrieved();
                    List<Source> context = trace.get().context();
                    require(retrieved.size() <= 5 && retrieved.containsAll(context) && context.containsAll(answer.sources()), "Invalid production trace");
                    row.put("retrieved", sourceRows(retrieved, allSpans)); row.put("context", sourceRows(context, allSpans));
                    row.put("cited", sourceRows(answer.sources(), allSpans)); row.put("answer", answer.answer()); row.put("grounded", answer.grounded());
                    row.put("status", "ok");
                    String token = UUID.randomUUID().toString().replace("-", ""); row.put("reviewToken", token);
                    Map<String, Object> review = new LinkedHashMap<>(); review.put("token", token); review.put("question", q.question());
                    review.put("gold", Map.of("answerable", q.answerable(), "referenceAnswer", q.referenceAnswer(),
                        "requiredFacts", q.requiredFacts(), "forbiddenConfusions", q.forbiddenConfusions(), "evidence", q.evidence()));
                    review.put("answer", answer.answer()); review.put("grounded", answer.grounded());
                    review.put("sourceText", dataset.texts.get(q.documentId()));
                    review.put("citedTexts", answer.sources().stream().map(Source::text).toList()); blind.add(review);
                    mapping.add(Map.of("token", token, "arm", arm, "split", split, "questionId", q.id(), "documentId", q.documentId()));
                } catch (Exception e) {
                    budget.failedRequest(); row.put("status", "error"); row.put("error", safeError(e));
                    state.put("questionExecutionFailed", true);
                    if (trace.get() != null) row.put("partialTrace", trace.get());
                } finally {
                    gateway.questionMode = false; row.put("latencyMs", elapsed(start));
                    append(run.resolve("questions.jsonl"), row); writeBlind();
                }
                budget.ensureOpen();
            }
        } finally {
            if (service != null) service.shutdown();
            try { db.execute("SHUTDOWN"); } catch (RuntimeException ignored) { }
            if (chroma.created) {
                try { chroma.close(); record.put("cleaned", true); }
                catch (Exception e) { record.put("cleanupError", safeError(e)); }
            }
            state.put("cleanupComplete", collectionRecords.stream().allMatch(r -> Boolean.TRUE.equals(r.get("cleaned")))); saveState();
        }
    }
    private static List<EvaluationMetrics.Span> spans(ChunkingService.Result result) {
        return result.spans().stream().map(s -> new EvaluationMetrics.Span(s.chunkId(), s.bodyStart(), s.bodyEnd(), s.start(), s.end())).toList();
    }
    private List<Map<String, Object>> sourceRows(List<Source> sources, Map<String, EvaluationMetrics.Span> spans) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Source s : sources) {
            EvaluationMetrics.Span span = spans.get(s.chunkId()); require(span != null, "Source has no original interval");
            var row = new LinkedHashMap<String, Object>(); row.put("source", s);
            row.put("interval", new EvaluationMetrics.Interval(s.documentId(), span.start(), span.end())); row.put("bodyStart", span.bodyStart()); row.put("bodyEnd", span.bodyEnd());
            result.add(row);
        }
        return result;
    }
    private void documentArtifact(EvaluationDataset.Document d, String arm, ChunkingService.Result result,
                                  List<EvaluationMetrics.Span> spans, long latency, String status, String error) throws Exception {
        Path dir = run.resolve(d.split()).resolve(arm).resolve("documents"); Files.createDirectories(dir);
        Map<String, Object> row = new LinkedHashMap<>(); row.put("documentId", d.id()); row.put("split", d.split()); row.put("arm", arm);
        row.put("status", status); row.put("latencyMs", latency); row.put("coverageValid", status.equals("ready") || status.equals("offline_validated"));
        row.put("error", error); row.put("strategy", result == null ? null : result.strategy()); row.put("fallbackReason", result == null ? null : result.fallbackReason());
        row.put("chunks", result == null ? List.of() : result.chunks()); row.put("spans", spans); write(dir.resolve(d.id() + ".json"), row);
        append(run.resolve("documents.jsonl"), row);
    }
    private void writeBlind() throws IOException {
        if (blind.isEmpty()) return;
        List<Map<String, Object>> shuffled = new ArrayList<>(blind); java.util.Collections.shuffle(shuffled, random);
        String rubric = dataset != null && Files.exists(dataset.root.resolve("rubric.md")) ? Files.readString(dataset.root.resolve("rubric.md")) : "";
        write(run.resolve("blind-review.json"), Map.of("schemaVersion", 1, "instructions",
            "Independent Codex agent blind review: answerCorrect/refusalCorrect booleans as applicable; citationSupported boolean per citedTexts entry; rationale required. Optional dimensions/atomicFacts. Return token and reviewer identity; do not invent an unavailable underlying model ID. Do not inspect private-mapping.json or other run artifacts.",
            "rubric", rubric, "items", shuffled));
        write(run.resolve("private-mapping.json"), Map.of("schemaVersion", 1, "items", mapping));
    }
    private void saveState() throws IOException { write(run.resolve("run.json"), state); }
    static long elapsed(long start) { return Duration.ofNanos(System.nanoTime() - start).toMillis(); }
    static String safeError(Throwable e) {
        // Never serialize exception messages/causes: they can contain URLs, keys, or model response bodies.
        return e instanceof ApiException api ? "ApiException:" + api.code : e.getClass().getSimpleName();
    }
    static void write(Path file, Object value) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        try (FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer b = ByteBuffer.wrap(bytes); while (b.hasRemaining()) out.write(b); out.force(true);
        }
        try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    }
    static void append(Path file, Object row) throws IOException {
        try (FileChannel out = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
            ByteBuffer b = ByteBuffer.wrap((JSON.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8));
            while (b.hasRemaining()) out.write(b); out.force(true);
        }
    }
    static final class CachedGateway implements ModelGateway {
        final ModelGateway delegate; final EvaluationBudget budget; final Map<String, Embedding> cache;
        boolean questionMode;
        CachedGateway(ModelGateway delegate, EvaluationBudget budget, Map<String, Embedding> cache) {
            this.delegate = delegate; this.budget = budget; this.cache = cache;
        }
        public List<Embedding> embed(List<String> texts) {
            budget.ensureOpen();
            if (questionMode && texts.size() == 1) {
                Embedding e = cache.get(texts.get(0)); if (e != null) return List.of(e);
                List<Embedding> result = delegate.embed(texts); budget.ensureOpen(); cache.put(texts.get(0), result.get(0)); return result;
            }
            List<Embedding> result = delegate.embed(texts); budget.ensureOpen(); return result;
        }
        public void index(List<Chunk> chunks, List<Embedding> vectors) { budget.ensureOpen(); delegate.index(chunks, vectors); }
        public List<String> retrieve(Embedding e) { budget.ensureOpen(); return delegate.retrieve(e); }
        public void remove(List<String> ids) { budget.ensureOpen(); delegate.remove(ids); }
        public String generate(String system, String user) { budget.ensureOpen(); String s = delegate.generate(system, user); budget.ensureOpen(); return s; }
        public String generateChunk(String system, String user, int maxOutputTokens, int timeoutSeconds) {
            budget.ensureOpen(); String s = delegate.generateChunk(system, user, maxOutputTokens, timeoutSeconds); budget.ensureOpen(); return s;
        }
    }
    private static final class TemporaryCollection implements AutoCloseable {
        final RagProperties p; final String name; final HttpClient http;
        boolean created;
        TemporaryCollection(RagProperties p, String name) {
            this.p = p; this.name = name;
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(p.chromaTimeoutSeconds())).followRedirects(HttpClient.Redirect.NEVER).build();
        }
        void create() throws Exception {
            JsonNode result = request("POST", "", Map.of("name", name, "get_or_create", false, "metadata", Map.of("hnsw:space", "cosine")));
            require(result.path("name").asText().equals(name), "Unexpected Chroma collection response"); created = true;
        }
        @Override public void close() throws Exception { if (created) { request("DELETE", "/" + name, null); created = false; } }
        JsonNode request(String method, String suffix, Object body) throws Exception {
            String url = EvaluationConfig.normalize(p.chromaBaseUrl()) + "/api/v2/tenants/default_tenant/databases/default_database/collections" + suffix;
            var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(p.chromaTimeoutSeconds())).header("Content-Type", "application/json");
            var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
            require(response.statusCode() >= 200 && response.statusCode() < 300, "Temporary Chroma request failed");
            return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
        }
    }
}
