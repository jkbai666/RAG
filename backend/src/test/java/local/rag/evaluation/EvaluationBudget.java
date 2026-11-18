package local.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Append-only, forced-to-disk reservations. A failed/missing usage never releases money. */
final class EvaluationBudget implements LangChainGateway.CallObserver, AutoCloseable {
    static final BigDecimal STOP = new BigDecimal("16"), TOTAL_CAP = new BigDecimal("20");
    static final int CHAT_LIMIT = 400, EMBEDDING_LIMIT = 600;
    record Rates(BigDecimal chatInput, BigDecimal chatOutput, BigDecimal embeddingInput) {
        Rates {
            if (chatInput.compareTo(new BigDecimal("2")) < 0 || chatOutput.compareTo(new BigDecimal("8")) < 0
                    || embeddingInput.compareTo(new BigDecimal("0.514")) < 0)
                throw new IllegalArgumentException("Rates below confirmed official maximum");
        }
    }
    private record Reservation(long id, String stage, int input, int output, BigDecimal cost) {}
    private final Rates rates;
    private final ObjectMapper json = new ObjectMapper();
    private final FileChannel lockFile, ledger;
    private final FileLock lock;
    private final long thread = Thread.currentThread().getId();
    private BigDecimal charged = BigDecimal.ZERO, settled = BigDecimal.ZERO;
    private Reservation pending;
    private long sequence;
    private int chatCalls, embeddingCalls;
    private BigDecimal priorSpent = BigDecimal.ZERO;
    private int priorChat, priorEmbedding;
    private boolean blocked;
    private String reason = "";
    private Map<String, String> labels = Map.of();

    EvaluationBudget(Path lockPath, Path ledgerPath, Rates rates) throws IOException {
        this(lockPath, ledgerPath, rates, null);
    }
    EvaluationBudget(Path lockPath, Path ledgerPath, Rates rates, Path priorRuns) throws IOException {
        this.rates = rates;
        lockFile = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockFile.tryLock(); }
        catch (RuntimeException | IOException e) { lockFile.close(); throw new IOException("Evaluation budget already owned"); }
        if (acquired == null) { lockFile.close(); throw new IOException("Evaluation budget already owned"); }
        lock = acquired;
        try {
            if (priorRuns != null) loadPrior(priorRuns, ledgerPath);
            charged = priorSpent; chatCalls = priorChat; embeddingCalls = priorEmbedding;
            ledger = FileChannel.open(ledgerPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        catch (IOException e) { lock.release(); lockFile.close(); throw e; }
        append("open", Map.of("stopRmb", STOP, "totalCapRmb", TOTAL_CAP,
            "deepSeekLimit", CHAT_LIMIT, "qwenLimit", EMBEDDING_LIMIT, "ratesPerMillion", rates,
            "priorAccountedRmb", priorSpent, "priorDeepSeekCalls", priorChat, "priorQwenCalls", priorEmbedding,
            "scope", "all_live_runs_in_this_evaluation_directory"));
        if (charged.compareTo(STOP) >= 0 || chatCalls >= CHAT_LIMIT || embeddingCalls >= EMBEDDING_LIMIT) {
            blocked = true; reason = "prior_scope_limit"; append("blocked", Map.of("reason", reason));
        }
    }
    private void loadPrior(Path runs, Path currentLedger) throws IOException {
        // Holding the process lock is mandatory during both reconstruction and reservation.
        // Count this run's component of each prior ledger; summing cumulative totals would double count retries.
        try (var directories = Files.list(runs)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                Path file = directory.resolve("cost-ledger.jsonl");
                if (!Files.exists(file) || file.toAbsolutePath().normalize().equals(currentLedger.toAbsolutePath().normalize())) continue;
                try {
                    var rows = Files.readAllLines(file, StandardCharsets.UTF_8);
                    if (rows.isEmpty()) throw new IOException("Empty historical cost ledger");
                    com.fasterxml.jackson.databind.JsonNode last = null;
                    for (String row : rows) { last = json.readTree(row); if (last == null || !last.isObject()) throw new IOException("Invalid historical ledger"); }
                    String moneyKey = last.has("runAccountedRmb") ? "runAccountedRmb" : "accountedRmb";
                    String chatKey = last.has("runDeepSeekCalls") ? "runDeepSeekCalls" : "deepSeekCalls";
                    String embedKey = last.has("runQwenCalls") ? "runQwenCalls" : "qwenCalls";
                    if (!last.path(moneyKey).isNumber() || !last.path(chatKey).isIntegralNumber() || !last.path(embedKey).isIntegralNumber())
                        throw new IOException("Missing historical ledger totals");
                    BigDecimal money = last.path(moneyKey).decimalValue();
                    int chat = last.path(chatKey).intValue(), embed = last.path(embedKey).intValue();
                    if (money.signum() < 0 || chat < 0 || embed < 0) throw new IOException("Negative historical ledger totals");
                    priorSpent = priorSpent.add(money); priorChat = Math.addExact(priorChat, chat); priorEmbedding = Math.addExact(priorEmbedding, embed);
                } catch (RuntimeException e) { throw new IOException("Cannot audit historical evaluation budget"); }
            }
        }
    }
    void labels(String split, String arm, String document, String question) {
        owner(); labels = Map.of("split", split, "arm", arm, "document", document, "question", question);
    }
    boolean blocked() { return blocked; }
    String blockedReason() { return reason; }
    BigDecimal charged() { return charged; }
    BigDecimal priorSpent() { return priorSpent; }
    BigDecimal runSpent() { return charged.subtract(priorSpent); }
    int priorChatCalls() { return priorChat; }
    int priorEmbeddingCalls() { return priorEmbedding; }
    int chatCalls() { return chatCalls; }
    int embeddingCalls() { return embeddingCalls; }
    private void owner() {
        if (Thread.currentThread().getId() != thread) block("non_serial_request");
    }
    void ensureOpen() { owner(); if (blocked) throw new IllegalStateException("Evaluation budget blocked: " + reason); }
    private BigDecimal cost(String stage, int input, int output) {
        return (stage.equals("embedding") ? rates.embeddingInput.multiply(BigDecimal.valueOf(input))
            : rates.chatInput.multiply(BigDecimal.valueOf(input)).add(rates.chatOutput.multiply(BigDecimal.valueOf(output))))
            .movePointLeft(6);
    }
    @Override public void before(String stage, int inputUpperBound, int maxOutputTokens) {
        ensureOpen();
        if (!stage.equals("embedding") && !stage.equals("chat") && !stage.equals("chunk")) block("unknown_stage");
        if (inputUpperBound < 128 || maxOutputTokens < 0 || (stage.equals("embedding") && maxOutputTokens != 0))
            block("invalid_token_bound");
        // An exception has no after(). Keep its reservation, and detach it before another request.
        failedRequest();
        BigDecimal reserve = cost(stage, inputUpperBound, maxOutputTokens);
        if (charged.add(reserve).compareTo(STOP) >= 0) block("rmb_stop");
        if (stage.equals("embedding") ? embeddingCalls >= EMBEDDING_LIMIT : chatCalls >= CHAT_LIMIT) block("request_limit");
        if (stage.equals("embedding")) embeddingCalls++; else chatCalls++;
        charged = charged.add(reserve);
        pending = new Reservation(++sequence, stage, inputUpperBound, maxOutputTokens, reserve);
        append("reserve", Map.of("request", sequence, "stage", stage, "inputUpperBound", inputUpperBound,
            "maxOutputTokens", maxOutputTokens, "reservedRmb", reserve));
    }
    @Override public void after(String stage, Integer inputTokens, Integer outputTokens, String responseModel) {
        ensureOpen();
        if (pending == null || !pending.stage.equals(stage)) block("unpaired_usage");
        Reservation r = pending;
        boolean valid = inputTokens != null && outputTokens != null && inputTokens >= 0 && outputTokens >= 0;
        BigDecimal actual = valid ? cost(stage, inputTokens, outputTokens) : r.cost;
        // Never clamp an unexpected larger bill. Record it and latch the ledger closed.
        charged = charged.subtract(r.cost).add(actual);
        if (valid) settled = settled.add(actual);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("request", r.id); data.put("stage", stage); data.put("inputTokens", inputTokens);
        data.put("outputTokens", outputTokens); data.put("responseModel", safeModel(responseModel));
        data.put("requestAccountedRmb", actual); data.put("usageComplete", valid);
        pending = null;
        append("settle", data);
        if ((inputTokens != null && (inputTokens < 0 || inputTokens > r.input))
                || (outputTokens != null && (outputTokens < 0 || outputTokens > r.output))) block("usage_exceeds_bound");
        if (charged.compareTo(STOP) >= 0) block("rmb_stop");
    }
    void failedRequest() {
        owner();
        if (pending != null) {
            long id = pending.id; pending = null;
            append("unsettled", Map.of("request", id, "reason", "no_complete_usage_reservation_retained"));
        }
    }
    private String safeModel(String model) {
        return model != null && model.matches("[A-Za-z0-9._:/-]{1,128}") ? model : null;
    }
    private void block(String why) {
        blocked = true; reason = why;
        append("blocked", Map.of("reason", why));
        throw new IllegalStateException("Evaluation budget blocked: " + why);
    }
    private void append(String event, Map<String, ?> data) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("at", Instant.now().toString()); row.put("event", event); row.putAll(labels); row.putAll(data);
        row.put("accountedRmb", charged); row.put("settledUsageRmb", settled);
        row.put("deepSeekCalls", chatCalls); row.put("qwenCalls", embeddingCalls);
        row.put("runAccountedRmb", charged.subtract(priorSpent));
        row.put("runDeepSeekCalls", chatCalls - priorChat); row.put("runQwenCalls", embeddingCalls - priorEmbedding);
        try {
            ByteBuffer bytes = ByteBuffer.wrap((json.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) ledger.write(bytes);
            ledger.force(true);
        } catch (IOException e) {
            blocked = true; reason = "ledger_io_failure";
            throw new IllegalStateException("Cannot persist evaluation budget");
        }
    }
    @Override public void close() throws IOException {
        try { failedRequest(); append("close", Map.of("blocked", blocked, "reason", reason)); }
        finally { try { ledger.close(); } finally { try { lock.release(); } finally { lockFile.close(); } } }
    }
}
