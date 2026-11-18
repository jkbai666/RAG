package local.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class EvaluationDataset {
    record Document(String id, String split, String path, String format, String sha256,
                    String textPath, String textSha256) {}
    record Evidence(int start, int end, String text) {}
    record Question(String id, String documentId, String split, String question, boolean answerable,
                    String referenceAnswer, List<String> requiredFacts, List<String> forbiddenConfusions,
                    List<Evidence> evidence, String category) {}
    final List<Document> documents;
    final List<Question> questions;
    final Map<String, String> texts;
    final Path root;
    final String manifestHash, freezeHash;
    final JsonNode manifest, freeze;

    EvaluationDataset(Path directory, ObjectMapper json) throws Exception {
        root = directory.toRealPath();
        byte[] manifestBytes = Files.readAllBytes(root.resolve("manifest.json"));
        byte[] freezeBytes = Files.readAllBytes(root.resolve("freeze.json"));
        manifestHash = hash(manifestBytes); freezeHash = hash(freezeBytes);
        manifest = json.readTree(manifestBytes); freeze = json.readTree(freezeBytes);
        require(manifest.path("schemaVersion").asInt() == 1 && manifest.path("seed").asLong() == 20261118L,
            "Unsupported dataset schema/seed");
        require(manifest.path("frozenAt").isTextual() && freeze.isObject(), "Dataset freeze missing");
        require(freeze.path("schemaVersion").asInt() == 1 && freeze.path("seed").asLong() == 20261118L
            && freeze.path("manifestSHA256").asText().equals(manifestHash), "Missing/mismatched freeze manifest hash");
        require(freeze.path("files").isObject() && freeze.path("files").size() > 0, "Missing frozen file hashes");
        var frozenFiles = freeze.path("files").fields();
        Set<String> expectedFrozen = new HashSet<>();
        while (frozenFiles.hasNext()) {
            var entry = frozenFiles.next();
            require(!Path.of(entry.getKey()).isAbsolute() && !entry.getKey().contains("\\"), "Invalid frozen relative path");
            expectedFrozen.add(entry.getKey());
            Path file = root.resolve(entry.getKey()).normalize().toRealPath();
            require(file.startsWith(root) && entry.getValue().isTextual() && hash(Files.readAllBytes(file)).equals(entry.getValue().asText()),
                "Frozen file SHA-256 mismatch");
        }
        try (var files = Files.walk(root)) {
            Set<String> actual = new HashSet<>();
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (!relative.equals("freeze.json") && !List.of(relative.split("/")).contains("__pycache__")
                    && !List.of(relative.split("/")).contains(".work")) actual.add(relative);
            }
            require(actual.equals(expectedFrozen), "Frozen file set differs from dataset");
        }
        require(freeze.path("files").path("manifest.json").asText().equals(manifestHash), "Manifest missing from frozen files");
        Path validator = root.getParent().resolve("validate_dataset.py");
        require(freeze.path("validatorSHA256").asText().equals(hash(Files.readAllBytes(validator))), "Frozen validator SHA-256 mismatch");
        require(manifest.path("documents").isArray() && manifest.path("questions").isArray(), "Invalid manifest arrays");
        documents = new ArrayList<>(); questions = new ArrayList<>(); texts = new HashMap<>();
        Map<String, Document> byId = new HashMap<>();
        Set<String> questionIds = new HashSet<>();
        DocumentParser parser = new DocumentParser();
        for (JsonNode node : manifest.path("documents")) {
            Document d = json.treeToValue(node, Document.class);
            require(validId(d.id()) && byId.putIfAbsent(d.id(), d) == null, "Duplicate/invalid document id");
            require(Set.of("dev", "validation").contains(d.split()) && Set.of("md", "txt", "pdf").contains(d.format()), "Invalid document split/format");
            byte[] bytes = Files.readAllBytes(file(d.path(), "documents"));
            byte[] textBytes = Files.readAllBytes(file(d.textPath(), "extracted"));
            require(hash(bytes).equals(d.sha256()) && hash(textBytes).equals(d.textSha256()), "Dataset SHA-256 mismatch");
            require(freeze.path("files").path(d.path()).asText().equals(d.sha256())
                && freeze.path("files").path(d.textPath()).asText().equals(d.textSha256()), "Document/extracted file not frozen");
            String text = new String(textBytes, StandardCharsets.UTF_8);
            require(extract(parser, d.format(), bytes).text().equals(text), "Extracted text differs from production parser");
            documents.add(d); texts.put(d.id(), text);
        }
        for (JsonNode node : manifest.path("questions")) {
            Question q = json.treeToValue(node, Question.class);
            require(node.path("answerable").isBoolean(), "Missing answerable boolean");
            require(validId(q.id()) && questionIds.add(q.id()), "Duplicate/invalid question id");
            Document d = byId.get(q.documentId());
            require(d != null && d.split().equals(q.split()), "Question/document split mismatch");
            require(q.question() != null && RagService.validateQuestion(q.question()).equals(q.question()), "Non-canonical question");
            require(q.referenceAnswer() != null && q.requiredFacts() != null && q.forbiddenConfusions() != null && q.evidence() != null
                && q.category() != null, "Missing question gold fields");
            require(!q.answerable() || !q.evidence().isEmpty(), "Answerable question needs evidence");
            require(q.answerable() || q.evidence().isEmpty(), "Unanswerable question must have no evidence");
            String original = texts.get(q.documentId());
            for (Evidence e : q.evidence()) {
                require(e.start() >= 0 && e.end() > e.start() && e.end() <= original.length()
                    && boundary(original, e.start()) && boundary(original, e.end()), "Invalid UTF-16 evidence interval");
                require(original.substring(e.start(), e.end()).equals(e.text()), "Evidence text mismatch");
            }
            questions.add(q);
        }
        require(documents.size() == 12 && questions.size() == 120, "Evaluation requires exactly 12 documents and 120 questions");
        for (String split : List.of("dev", "validation")) {
            require(documents.stream().filter(d -> d.split().equals(split)).count() == (split.equals("dev") ? 4 : 8), "Wrong document split count");
            require(questions.stream().filter(q -> q.split().equals(split)).count() == (split.equals("dev") ? 40 : 80), "Wrong question split count");
        }
        for (Document d : documents) {
            require(questions.stream().filter(q -> q.documentId().equals(d.id()) && q.answerable()).count() == 8
                && questions.stream().filter(q -> q.documentId().equals(d.id()) && !q.answerable()).count() == 2, "Wrong per-document question counts");
        }
    }
    Path file(String relative, String prefix) throws Exception {
        require(relative != null && relative.replace('\\', '/').startsWith(prefix + "/"), "Invalid dataset path");
        Path file = root.resolve(relative).normalize().toRealPath();
        require(file.startsWith(root.resolve(prefix).toRealPath()), "Dataset path escapes root");
        return file;
    }
    static DocumentParser.Extracted extract(DocumentParser parser, String format, byte[] bytes) throws Exception {
        return parser.extract(format, bytes);
    }
    static String hash(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
    static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{1,80}"); }
    static boolean boundary(String text, int at) {
        return at == 0 || at == text.length() || !(Character.isLowSurrogate(text.charAt(at)) && Character.isHighSurrogate(text.charAt(at - 1)));
    }
    static void require(boolean ok, String message) { if (!ok) throw new IllegalArgumentException(message); }
}
