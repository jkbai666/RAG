"""Offline validation for UC-004. Standard library only; no application config or network.

Usage: python evaluation/validate_dataset.py [--verify-parser | --verify-pdf]
       python evaluation/validate_dataset.py --self-test
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import re
import sys

DATASET = Path(__file__).resolve().parent / "dataset"
DOC_FIELDS = {"id", "split", "path", "format", "sha256", "textPath", "textSha256"}
QUESTION_FIELDS = {"id", "documentId", "split", "question", "answerable", "referenceAnswer",
                   "requiredFacts", "forbiddenConfusions", "evidence", "category"}
SHA = re.compile(r"[0-9a-f]{64}\Z")
IDENTIFIER = re.compile(r"[A-Za-z0-9_-]{1,80}\Z")
CATEGORIES = {"fact", "condition", "exception", "arithmetic", "table", "cross-section", "version", "procedure", "unanswerable"}


class InvalidDataset(ValueError):
    pass


def require(ok, message):
    if not ok:
        raise InvalidDataset(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def no_duplicates(pairs):
    out = {}
    for key, value in pairs:
        require(key not in out, f"Duplicate JSON key: {key}")
        out[key] = value
    return out


def read_json(path):
    return json.loads(path.read_bytes().decode("utf-8"), object_pairs_hook=no_duplicates)


def safe_path(root, value, prefix=None):
    require(isinstance(value, str) and bool(value) and "\\" not in value, "Invalid portable relative path")
    path = PurePosixPath(value)
    require(not path.is_absolute() and not any(p in {".", ".."} or ":" in p for p in value.split("/"))
            and "//" not in value, "Unsafe dataset path")
    require(prefix is None or len(path.parts) > 1 and path.parts[0] == prefix, "Dataset path prefix mismatch")
    resolved = (root / value).resolve()
    require(resolved.is_relative_to(root.resolve()) and resolved.is_file(), "Missing file or path escapes dataset")
    return resolved


def nonempty(value, label):
    require(isinstance(value, str) and bool(value.strip()) and "\x00" not in value, f"Empty/invalid {label}")
    try:
        value.encode("utf-16-le")
    except UnicodeEncodeError as error:
        raise InvalidDataset(f"Invalid Unicode in {label}") from error


def utf16_slice(text, start, end):
    require(type(start) is int and type(end) is int, "UTF-16 offsets must be integers")
    data = text.encode("utf-16-le")
    require(0 <= start < end <= len(data) // 2, "Invalid UTF-16 evidence range")
    try:
        return data[start * 2:end * 2].decode("utf-16-le")
    except UnicodeDecodeError as error:
        raise InvalidDataset("UTF-16 range splits a surrogate pair") from error


def normalize_question(value):
    return re.sub(r"[\W_]+", "", value, flags=re.UNICODE).casefold()


def validate_structure(root, manifest):
    require(isinstance(manifest, dict) and set(manifest) == {"schemaVersion", "seed", "frozenAt", "documents", "questions"},
            "Unexpected manifest fields")
    require(type(manifest["schemaVersion"]) is int and manifest["schemaVersion"] == 1, "Invalid schema version")
    require(type(manifest["seed"]) is int and manifest["seed"] == 20261118, "Invalid seed")
    require(manifest["frozenAt"] == "2026-11-18", "Invalid frozen date")
    documents, questions = manifest["documents"], manifest["questions"]
    require(isinstance(documents, list) and len(documents) == 12, "Expected 12 documents")
    require(isinstance(questions, list) and len(questions) == 120, "Expected 120 questions")
    by_id, texts, paths, hashes = {}, {}, set(), set()
    for doc in documents:
        require(isinstance(doc, dict) and set(doc) == DOC_FIELDS, "Unexpected document fields")
        doc_id = doc["id"]
        require(isinstance(doc_id, str) and IDENTIFIER.fullmatch(doc_id) and doc_id not in by_id, "Duplicate/invalid document id")
        require(doc["split"] in {"dev", "validation"} and doc["format"] in {"md", "txt", "pdf"}, "Invalid split or format")
        require(doc_id.startswith("dev-" if doc["split"] == "dev" else "val-"), "Document id/split mismatch")
        require(doc["path"].endswith("." + doc["format"]) and doc["textPath"].endswith(".txt"), "Format/path mismatch")
        require(isinstance(doc["sha256"], str) and SHA.fullmatch(doc["sha256"])
                and isinstance(doc["textSha256"], str) and SHA.fullmatch(doc["textSha256"]), "Invalid document SHA-256")
        for name, prefix in (("path", "documents"), ("textPath", "extracted")):
            require(doc[name] not in paths, "Duplicate document/text path")
            paths.add(doc[name])
            safe_path(root, doc[name], prefix)
        raw, extracted = (root / doc["path"]).read_bytes(), (root / doc["textPath"]).read_bytes()
        require(digest(raw) == doc["sha256"] and digest(extracted) == doc["textSha256"], "Document/text SHA-256 mismatch")
        require(doc["textSha256"] not in hashes, "Duplicate document content")
        hashes.add(doc["textSha256"])
        require(not extracted.startswith(b"\xef\xbb\xbf"), "Extracted text has a BOM")
        text = extracted.decode("utf-8", errors="strict")
        nonempty(text, "extracted text")
        require(len(text.encode("utf-16-le")) // 2 <= 4_000_000, "Text exceeds parser capacity")
        require("\f" not in text, "Raw page markers leaked into extracted text")
        if doc["format"] != "pdf":
            require(not raw.startswith(b"\xef\xbb\xbf"), "MD/TXT must be BOM-free")
            require(raw.decode("utf-8") == text, "MD/TXT differs from production UTF-8 decode")
        else:
            require(raw.startswith(b"%PDF-") and raw.rstrip().endswith(b"%%EOF"), "Not a real complete PDF")
        by_id[doc_id], texts[doc_id] = doc, text
    require(Counter(d["split"] for d in documents) == {"dev": 4, "validation": 8}, "Expected 4 dev / 8 validation documents")
    require({d["format"] for d in documents} == {"md", "txt", "pdf"}, "Missing required document format")
    question_ids, question_texts, counts, categories = set(), set(), Counter(), Counter()
    multispan, crosspage = Counter(), Counter()
    pages = validate_pages(root, documents, texts)
    for question in questions:
        require(isinstance(question, dict) and set(question) == QUESTION_FIELDS, "Unexpected question fields")
        qid = question["id"]
        require(isinstance(qid, str) and IDENTIFIER.fullmatch(qid) and qid not in question_ids, "Duplicate/invalid question id")
        question_ids.add(qid)
        doc = by_id.get(question["documentId"])
        require(doc is not None and question["split"] == doc["split"], "Question/document split mismatch")
        require(qid.startswith(doc["id"] + "-q"), "Question id/document mismatch")
        require(type(question["answerable"]) is bool, "answerable must be boolean")
        for key in ("question", "referenceAnswer", "category"):
            nonempty(question[key], key)
        require(question["question"].strip() == question["question"], "Question has outer whitespace")
        normalized = normalize_question(question["question"])
        require(normalized not in question_texts, "Duplicate normalized question / leakage")
        question_texts.add(normalized)
        for key in ("requiredFacts", "forbiddenConfusions"):
            require(isinstance(question[key], list) and bool(question[key]), f"Empty {key}")
            for value in question[key]:
                nonempty(value, key)
            require(len(set(question[key])) == len(question[key]), f"Duplicate {key}")
        category = question["category"]
        require(category in CATEGORIES, "Unknown category")
        require((category == "unanswerable") == (not question["answerable"]), "Refusal category/label mismatch")
        evidence = question["evidence"]
        require(isinstance(evidence, list), "Evidence must be an array")
        require(bool(evidence) == question["answerable"], "Evidence required only for answerable questions")
        prior_end, touched_pages = -1, set()
        for span in evidence:
            require(isinstance(span, dict) and set(span) == {"start", "end", "text"}, "Unexpected evidence fields")
            nonempty(span["text"], "evidence text")
            require(utf16_slice(texts[doc["id"]], span["start"], span["end"]) == span["text"], "Evidence is not the exact UTF-16 source substring")
            require(span["start"] >= prior_end, "Evidence must be sorted and non-overlapping")
            prior_end = span["end"]
            for page in pages.get(doc["id"], []):
                if page["start"] < span["end"] and page["end"] > span["start"]:
                    touched_pages.add(page["page"])
        if len(evidence) > 1:
            multispan[doc["split"]] += 1
        if len(touched_pages) > 1:
            crosspage[doc["split"]] += 1
        counts[(doc["id"], question["answerable"])] += 1
        categories[(question["split"], category)] += 1
    for doc_id in by_id:
        require(counts[(doc_id, True)] == 8 and counts[(doc_id, False)] == 2, "Each document needs 8 answerable + 2 unanswerable")
    require(Counter(q["split"] for q in questions) == {"dev": 40, "validation": 80}, "Expected 40 dev / 80 validation questions")
    for split in ("dev", "validation"):
        for category in CATEGORIES:
            require(categories[(split, category)] > 0, f"Missing category {category} in {split}")
        require(multispan[split] >= 4 and crosspage[split] >= 1, f"Insufficient cross-paragraph/page evidence in {split}")
    validate_coverage(root, documents, texts, pages)
    validate_surface_leakage(documents, texts)
    return dict(documents=len(documents), questions=len(questions),
                devQuestions=40, validationQuestions=80, answerable=96, unanswerable=24,
                multispan=dict(multispan), crosspage=dict(crosspage),
                codePoints=sum(len(t) for t in texts.values()),
                utf16Units=sum(len(t.encode("utf-16-le")) // 2 for t in texts.values()),
                pdfPages=sum(len(p) for p in pages.values()))


def validate_pages(root, documents, texts):
    index = read_json(root / "pages.json")
    require(set(index) == {"schemaVersion", "offsetEncoding", "documents"}
            and index["schemaVersion"] == 1 and index["offsetEncoding"] == "UTF-16", "Invalid page index schema")
    pages = index["documents"]
    pdf_ids = {d["id"] for d in documents if d["format"] == "pdf"}
    require(isinstance(pages, dict) and set(pages) == pdf_ids, "PDF page index/document mismatch")
    for doc_id, spans in pages.items():
        require(isinstance(spans, list) and len(spans) >= 2, "PDF must have multiple real text pages")
        previous = 0
        text = texts[doc_id]
        for number, span in enumerate(spans, 1):
            require(set(span) == {"page", "start", "end"} and type(span["page"]) is int and span["page"] == number,
                    "Invalid PDF page numbering")
            part = utf16_slice(text, span["start"], span["end"])
            require(part.strip() and span["start"] == previous, "Empty, overlapping or gapped PDF page")
            previous = span["end"]
        require(previous == len(text.encode("utf-16-le")) // 2, "PDF page index does not cover extracted text")
    return pages


def validate_coverage(root, documents, texts, pages):
    coverage = read_json(root / "coverage.json")
    require(coverage.get("schemaVersion") == 1 and isinstance(coverage.get("documents"), list), "Invalid coverage index")
    entries = coverage["documents"]
    require(len(entries) == 12 and {e["documentId"] for e in entries} == {d["id"] for d in documents}, "Coverage/document mismatch")
    families, split_by_doc = {}, {d["id"]: d["split"] for d in documents}
    for entry in entries:
        doc_id = entry["documentId"]
        nonempty(entry["ruleFamily"], "rule family")
        nonempty(entry["title"], "document title")
        family = entry["ruleFamily"]
        require(family not in families, "Rule family reused across documents or splits")
        families[family] = entry["split"]
        require(entry["split"] == split_by_doc[doc_id], "Coverage split mismatch")
        text = texts[doc_id]
        require(entry["codePoints"] == len(text) and entry["utf16Units"] == len(text.encode("utf-16-le")) // 2
                and entry["utf8Bytes"] == len(text.encode("utf-8")) and entry["pages"] == len(pages.get(doc_id, [])), "Incorrect coverage length/page statistics")
        require(entry["answerable"] == 8 and entry["unanswerable"] == 2, "Coverage label counts mismatch")
        require(type(entry.get("parserUnits800_100")) is int and entry["parserUnits800_100"] > 0, "Invalid production unit count")
        if doc_id in {"dev-01", "val-05", "val-06"}:
            require(entry["utf8Bytes"] >= 22000 and entry["utf16Units"] > 16000, "Missing required long document/window coverage")
    sizes = [len(t) for t in texts.values()]
    require(min(sizes) <= 1800 and max(sizes) >= 2500, "Expected short and long document coverage")
    require(coverage["totalCodePoints"] == sum(sizes) and coverage["totalUtf16Units"] == sum(len(t.encode("utf-16-le")) // 2 for t in texts.values()), "Incorrect total lengths")


def validate_surface_leakage(documents, texts):
    """Reject exact substantive cross-split copies; semantic independence also needs review."""
    fingerprints = {"dev": {}, "validation": {}}
    for doc in documents:
        compact = re.sub(r"\s+", "", texts[doc["id"]]).casefold()
        # 100 consecutive non-whitespace code points are too long for an incidental label.
        for at in range(max(0, len(compact) - 99)):
            fingerprints[doc["split"]].setdefault(compact[at:at + 100], doc["id"])
    overlap = set(fingerprints["dev"]) & set(fingerprints["validation"])
    require(not overlap, "Substantive exact cross-split source reuse detected")


def validate_freeze(root):
    frozen = read_json(root / "freeze.json")
    require(frozen.get("schemaVersion") == 1 and frozen.get("seed") == 20261118 and frozen.get("frozenAt") == "2026-11-18", "Invalid freeze metadata")
    files = frozen.get("files")
    require(isinstance(files, dict) and bool(files), "Empty freeze file map")
    actual = {p.relative_to(root).as_posix() for p in root.rglob("*")
              if p.is_file() and p.name != "freeze.json" and "__pycache__" not in p.parts and ".work" not in p.parts}
    require(set(files) == actual, "Freeze does not cover all dataset files (or has stale entries)")
    require(frozen.get("manifestSHA256") == digest((root / "manifest.json").read_bytes()) == files.get("manifest.json"), "Manifest freeze hash mismatch")
    for name, expected in files.items():
        require(isinstance(expected, str) and SHA.fullmatch(expected), "Invalid freeze SHA-256")
        require(digest(safe_path(root, name).read_bytes()) == expected, f"Frozen file changed: {name}")
    require(frozen.get("validatorSHA256") == digest(Path(__file__).read_bytes()), "Validator freeze hash mismatch")


def validate(root, verify_parser=False, verify_pdf=False):
    root = root.resolve()
    validate_freeze(root)
    manifest = read_json(root / "manifest.json")
    summary = validate_structure(root, manifest)
    if verify_parser or verify_pdf:
        # The import is local and read-only. No generation entry point is executed.
        sys.dont_write_bytecode = True
        sys.path.insert(0, str(DATASET))
        import generate
        docs = manifest["documents"] if verify_parser else [d for d in manifest["documents"] if d["format"] == "pdf"]
        extracted = generate.extract([root / d["path"] for d in docs], production=verify_parser)
        pages = read_json(root / "pages.json")["documents"]
        coverage = {entry["documentId"]: entry for entry in read_json(root / "coverage.json")["documents"]}
        for doc, actual in zip(docs, extracted, strict=True):
            require(actual["text"].encode("utf-8") == (root / doc["textPath"]).read_bytes(), "PDFBox/production extracted bytes differ")
            require(actual["pages"] == pages.get(doc["id"], []), "PDFBox/production pages differ")
            if verify_parser:
                require(actual["unitCount"] == coverage[doc["id"]]["parserUnits800_100"], "Production unit count differs")
        summary["productionParserVerified"] = verify_parser
        summary["pdfboxVerified"] = True
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DATASET)
    parser.add_argument("--verify-pdf", action="store_true", help="re-extract PDFs with cached PDFBox 3.0.7")
    parser.add_argument("--verify-parser", action="store_true", help="compare all texts and page spans with compiled production parser")
    parser.add_argument("--self-test", action="store_true", help="run mutation and UTF-16 regression tests")
    args = parser.parse_args()
    if args.self_test:
        sys.dont_write_bytecode = True
        sys.path.insert(0, str(DATASET))
        import unittest
        suite = unittest.defaultTestLoader.discover(str(DATASET), pattern="test_validation.py")
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        raise SystemExit(0 if result.wasSuccessful() else 1)
    try:
        result = validate(args.dataset, verify_parser=args.verify_parser, verify_pdf=args.verify_pdf)
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(f"FAIL dataset: {error}", file=sys.stderr)
        raise SystemExit(1) from None
    print("PASS dataset " + json.dumps(result, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
