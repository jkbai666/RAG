"""Offline, standard-library-only paired evaluation and independent-review aggregation."""
from __future__ import annotations

import argparse
from collections import defaultdict
from decimal import Decimal
import hashlib
import json
import math
import os
from pathlib import Path
import random
import statistics
import sys
import tempfile
import unittest

SEED = 20261118
ARMS = ("A", "B")
SPLITS = ("dev", "validation")


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate JSON field")
            result[key] = value
        return result
    with Path(path).open(encoding="utf-8-sig") as stream:
        return json.load(stream, object_pairs_hook=unique)


def read_jsonl(path):
    if not Path(path).exists():
        return []
    rows = []
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        if not line.strip():
            raise ValueError("blank ledger/result row")
        rows.append(json.loads(line))
    return rows


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def save_json(path, value):
    path = Path(path)
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)


def covered(document, start, end, intervals):
    """Union original UTF-16 intervals, clipped to one gold evidence item."""
    ranges = sorted((max(start, i["start"]), min(end, i["end"])) for i in intervals
                    if i["documentId"] == document and i["start"] < end and i["end"] > start)
    total, right = 0, start
    for left, stop in ranges:
        if stop > right:
            total += stop - max(left, right)
            right = stop
    return total


def retrieval_metrics(gold, rows):
    if not gold["answerable"]:
        return {}
    evidence = gold["evidence"]
    if not evidence:
        raise ValueError("answerable gold without evidence")
    result = {}
    for field, label in (("retrieved", "evidenceRecall@5"), ("context", "contextEvidenceRecall"), ("cited", "citedEvidenceRecall")):
        intervals = [r["interval"] for r in rows[field]]
        amounts = [covered(gold["documentId"], e["start"], e["end"], intervals) for e in evidence]
        result[label] = sum(n == e["end"] - e["start"] for n, e in zip(amounts, evidence)) / len(evidence)
        result[label + "AllRequired"] = float(all(n == e["end"] - e["start"] for n, e in zip(amounts, evidence)))
        result[label + "Utf16Coverage"] = sum(amounts) / sum(e["end"] - e["start"] for e in evidence)
    return result


def percentile(values, probability):
    values = sorted(values)
    pos = (len(values) - 1) * probability
    low = math.floor(pos)
    high = math.ceil(pos)
    return values[low] + (values[high] - values[low]) * (pos - low)


def paired_cluster(rows, metric, iterations=5000):
    """Document-macro paired bootstrap; every sampled cluster keeps both arms and all its questions."""
    groups = defaultdict(list)
    for row in rows:
        a, b = row["A"].get(metric), row["B"].get(metric)
        if a is not None and b is not None:
            groups[row["documentId"]].append((a, b))
    if not groups:
        return {"A": None, "B": None, "deltaBminusA": None, "paired95CI": None, "documents": 0, "pairs": 0}
    clusters = [(statistics.mean(p[0] for p in group), statistics.mean(p[1] for p in group))
                for _, group in sorted(groups.items())]
    point_a = statistics.mean(c[0] for c in clusters)
    point_b = statistics.mean(c[1] for c in clusters)
    rng = random.Random(SEED)
    samples = [statistics.mean(b - a for a, b in rng.choices(clusters, k=len(clusters))) for _ in range(iterations)]
    return {"A": point_a, "B": point_b, "deltaBminusA": point_b - point_a,
            "paired95CI": [percentile(samples, .025), percentile(samples, .975)] if len(clusters) > 1 else None,
            "documents": len(clusters), "pairs": sum(map(len, groups.values())), "bootstrapIterations": iterations}


def budget_audit(run):
    rows = read_jsonl(run / "cost-ledger.jsonl")
    issues = []
    if not rows or rows[0].get("event") != "open":
        return {"valid": False, "issues": ["missing_cost_ledger"], "accountedRmb": None}
    opened = rows[0]
    rates = opened["ratesPerMillion"]
    d = lambda x: Decimal(str(x))
    rates = {k: d(v) for k, v in rates.items()}
    if rates["chatInput"] < 2 or rates["chatOutput"] < 8 or rates["embeddingInput"] < Decimal("0.514"):
        issues.append("below_official_rate")
    prior = d(opened.get("priorAccountedRmb", 0))
    prior_chat, prior_embed = opened.get("priorDeepSeekCalls", 0), opened.get("priorQwenCalls", 0)
    chat, embed = prior_chat, prior_embed
    total = prior
    requests = {}
    blocked = False
    def cost(stage, input_tokens, output_tokens):
        return ((rates["embeddingInput"] * input_tokens) if stage == "embedding" else
                rates["chatInput"] * input_tokens + rates["chatOutput"] * output_tokens) / 1_000_000
    for row in rows:
        event = row.get("event")
        if event == "reserve":
            ident, stage = row["request"], row["stage"]
            upper, maximum = row["inputUpperBound"], row["maxOutputTokens"]
            if ident in requests or stage not in ("chat", "chunk", "embedding") or upper < 128 or maximum < 0:
                issues.append("invalid_reservation")
            reserved = cost(stage, upper, maximum)
            if reserved != d(row["reservedRmb"]):
                issues.append("incorrect_reservation")
            if blocked or total + reserved >= 16:
                issues.append("request_after_stop")
            if stage == "embedding":
                embed += 1
            else:
                chat += 1
            requests[ident] = {"stage": stage, "cost": reserved, "settled": False, "usageComplete": False,
                              "inputUpperBound": upper, "maxOutputTokens": maximum, "inputTokens": None, "outputTokens": None, "labels": {
                key: row.get(key, "") for key in ("split", "arm", "document", "question")}}
            total += reserved
        elif event == "settle":
            request = requests.get(row["request"])
            if request is None or request["settled"] or request["stage"] != row["stage"]:
                issues.append("unpaired_usage")
                continue
            actual = request["cost"]
            if row["usageComplete"]:
                if type(row["inputTokens"]) is not int or type(row["outputTokens"]) is not int or min(row["inputTokens"], row["outputTokens"]) < 0:
                    issues.append("invalid_usage")
                else:
                    actual = cost(row["stage"], row["inputTokens"], row["outputTokens"])
            if actual != d(row["requestAccountedRmb"]):
                issues.append("incorrect_settlement")
            total += actual - request["cost"]
            request["cost"], request["settled"] = actual, True
            request["usageComplete"] = row["usageComplete"]
            request["inputTokens"], request["outputTokens"] = row.get("inputTokens"), row.get("outputTokens")
        elif event == "blocked":
            blocked = True
        elif event not in ("open", "unsettled", "close"):
            issues.append("unknown_ledger_event")
        if total != d(row["accountedRmb"]) or chat != row["deepSeekCalls"] or embed != row["qwenCalls"]:
            issues.append("ledger_total_mismatch")
        if total - prior != d(row.get("runAccountedRmb", total - prior)):
            issues.append("run_total_mismatch")
    if rows[-1].get("event") != "close":
        issues.append("ledger_not_closed")
    if total > 20 or chat > 400 or embed > 600:
        issues.append("hard_limit_exceeded")
    by_stage, by_arm = defaultdict(Decimal), defaultdict(Decimal)
    tokens = {}
    for request in requests.values():
        by_stage[request["stage"]] += request["cost"]
        by_arm[request["labels"]["arm"] or "unlabelled"] += request["cost"]
        stage = tokens.setdefault(request["stage"], {"requests": 0, "reservedInputUpperBound": 0, "reservedMaxOutputTokens": 0,
                                                    "actualInputTokensKnown": 0, "actualOutputTokensKnown": 0, "missingUsageOrFailureRequests": 0})
        stage["requests"] += 1
        stage["reservedInputUpperBound"] += request["inputUpperBound"]
        stage["reservedMaxOutputTokens"] += request["maxOutputTokens"]
        stage["actualInputTokensKnown"] += request["inputTokens"] or 0
        stage["actualOutputTokensKnown"] += request["outputTokens"] or 0
        stage["missingUsageOrFailureRequests"] += not request["usageComplete"]
    return {"valid": not issues, "issues": sorted(set(issues)), "blocked": blocked,
            "priorAccountedRmb": float(prior), "runAccountedRmb": float(total - prior), "accountedRmb": float(total),
            "deepSeekCalls": chat, "qwenCalls": embed, "priorDeepSeekCalls": prior_chat, "priorQwenCalls": prior_embed,
            "byStageRmb": {k: float(v) for k, v in by_stage.items()}, "byArmRmb": {k: float(v) for k, v in by_arm.items()},
            "tokensByStage": tokens,
            "missingUsageOrFailureRequests": sum(not r["usageComplete"] for r in requests.values()),
            "stopNewRequestsRmb": 16, "totalCapRmb": 20}


def load_reviews(run, review_file, mapping, reasons):
    if review_file is None:
        reasons.append("independent_reviews_missing")
        return {}
    payload = read_json(review_file)
    if (payload.get("independent") is not True or payload.get("reviewerKind") != "independent_codex_agent"
            or not isinstance(payload.get("reviewer"), str) or not payload["reviewer"].strip()):
        reasons.append("independent_reviewer_attestation_missing")
        return {}
    if payload.get("blindPackageSha256") != sha256(run / "blind-review.json"):
        reasons.append("blind_review_package_hash_mismatch")
        return {}
    save_json(run / "review-results.json", payload)
    result = {}
    for item in payload.get("items", []):
        token = item.get("token")
        if token not in mapping or token in result:
            reasons.append("unknown_or_duplicate_review_token")
            continue
        result[token] = item
    return result


def utf16_slice(value, start, end):
    return value.encode("utf-16-le")[start * 2:end * 2].decode("utf-16-le")


def document_geometry(manifest, documents, settings, pages, reasons):
    """Reconstruct each imported original body and verify the frozen extracted-text hash."""
    gold = {d["id"]: d for d in manifest["documents"]}
    lookup = {}
    for d in documents:
        if d.get("status") != "ready" or d["documentId"] not in gold:
            continue
        chunks, spans = d.get("chunks", []), d.get("spans", [])
        try:
            if not chunks or len(chunks) != len(spans):
                raise ValueError("empty/mismatched spans")
            original = "".join(utf16_slice(c["text"], s["bodyStart"] - s["start"], s["bodyEnd"] - s["start"])
                               for c, s in zip(chunks, spans))
            if hashlib.sha256(original.encode("utf-8")).hexdigest() != gold[d["documentId"]]["textSha256"]:
                raise ValueError("reconstructed source hash mismatch")
            next_body = 0
            for index, (chunk, span) in enumerate(zip(chunks, spans), 1):
                if (chunk["id"] != span["chunkId"] or chunk["index"] != index or chunk["documentId"] != d["documentId"] or
                        span["bodyStart"] != next_body or span["bodyEnd"] <= next_body or span["end"] != span["bodyEnd"] or
                        not 0 <= span["start"] <= span["bodyStart"]):
                    raise ValueError("invalid source span")
                preceding = utf16_slice(original, 0, span["bodyStart"])
                expected_start = len(preceding[:-settings["chunkOverlap"]].encode("utf-16-le")) // 2 if settings["chunkOverlap"] else span["bodyStart"]
                if span["start"] != expected_start or len(chunk["text"]) > settings["chunkSize"]:
                    raise ValueError("invalid overlap/size")
                if utf16_slice(original, span["start"], span["end"]) != chunk["text"]:
                    raise ValueError("chunk differs from original")
                original_pages = pages.get(d["documentId"], [])
                matching = [p["page"] for p in original_pages if p["end"] > span["start"] and p["start"] < span["end"]]
                if (chunk.get("pageStart") != (matching[0] if matching else None) or
                        chunk.get("pageEnd") != (matching[-1] if matching else None)):
                    raise ValueError("incorrect original pages")
                key = (d["split"], d["arm"], chunk["id"])
                if key in lookup:
                    raise ValueError("duplicate chunk")
                lookup[key] = (chunk, span)
                next_body = span["bodyEnd"]
            if next_body != len(original.encode("utf-16-le")) // 2:
                raise ValueError("incomplete source body")
        except (ValueError, KeyError, TypeError, UnicodeError):
            reasons.append("source_chunk_span_geometry_invalid")
    return lookup


def validate_question_sources(row, lookup, reasons):
    available = {}
    for field in ("retrieved", "context", "cited"):
        ids = []
        for item in row.get(field, []):
            source, interval = item["source"], item["interval"]
            key = (row["split"], row["arm"], source["chunkId"])
            expected = lookup.get(key)
            if expected is None:
                reasons.append("source_outside_split_corpus")
                continue
            chunk, span = expected
            if (source["documentId"] != chunk["documentId"] or source["text"] != chunk["text"] or
                    source["chunkIndex"] != chunk["index"] or source.get("pageStart") != chunk.get("pageStart") or
                    source.get("pageEnd") != chunk.get("pageEnd") or interval != {
                        "documentId": chunk["documentId"], "start": span["start"], "end": span["end"]}):
                reasons.append("source_original_interval_mismatch")
            ids.append(source["chunkId"])
        if len(ids) != len(set(ids)):
            reasons.append("duplicate_source")
        available[field] = set(ids)
    if (not available["context"].issubset(available["retrieved"]) or not available["cited"].issubset(available["context"]) or
            type(row.get("grounded")) is not bool or row["grounded"] != bool(available["cited"])):
        reasons.append("invalid_production_source_trace")


def completeness(manifest, documents, questions, reasons):
    docs, gold = manifest["documents"], manifest["questions"]
    if len(docs) != 12 or len(gold) != 120 or len({d["id"] for d in docs}) != 12 or len({q["id"] for q in gold}) != 120:
        reasons.append("dataset_cardinality_invalid")
    for split, nd, nq in (("dev", 4, 40), ("validation", 8, 80)):
        if sum(d["split"] == split for d in docs) != nd or sum(q["split"] == split for q in gold) != nq:
            reasons.append("dataset_split_counts_invalid")
    for doc in docs:
        group = [q for q in gold if q["documentId"] == doc["id"]]
        if sum(q["answerable"] is True for q in group) != 8 or sum(q["answerable"] is False for q in group) != 2:
            reasons.append("dataset_document_question_counts_invalid")
    expected_docs = {(d["split"], arm, d["id"]) for d in docs for arm in ARMS}
    expected_questions = {(q["split"], arm, q["id"]) for q in gold for arm in ARMS}
    doc_keys = [(d["split"], d["arm"], d["documentId"]) for d in documents]
    q_keys = [(q["split"], q["arm"], q["questionId"]) for q in questions]
    if set(doc_keys) != expected_docs or len(doc_keys) != len(expected_docs):
        reasons.append("document_coverage_incomplete_or_duplicate")
    if set(q_keys) != expected_questions or len(q_keys) != len(expected_questions):
        reasons.append("question_coverage_incomplete_or_duplicate")
    if any(d.get("status") != "ready" or d.get("coverageValid") is not True for d in documents):
        reasons.append("document_import_or_source_coverage_failed")
    if any(q.get("status") != "ok" for q in questions):
        reasons.append("question_execution_failed")


def compute_report(run, reviews=None, iterations=5000):
    run = Path(run)
    reasons = []
    state, manifest = read_json(run / "run.json"), read_json(run / "manifest.json")
    freeze = read_json(run / "freeze.json")
    documents, questions = read_jsonl(run / "documents.jsonl"), read_jsonl(run / "questions.jsonl")
    if state.get("mode") != "live":
        reasons.append("offline_run_is_not_live_evidence")
    if (state.get("datasetVerified") is not True or state.get("manifestSha256") != sha256(run / "manifest.json") or
            state.get("freezeSha256") != sha256(run / "freeze.json") or
            freeze.get("manifestSHA256") != sha256(run / "manifest.json") or
            freeze.get("files", {}).get("manifest.json") != sha256(run / "manifest.json")):
        reasons.append("frozen_dataset_binding_invalid")
    if state.get("acquisitionComplete") is not True or state.get("error"):
        reasons.append("acquisition_incomplete")
    if state.get("cleanupComplete") is not True:
        reasons.append("temporary_collection_cleanup_incomplete")
    completeness(manifest, documents, questions, reasons)
    page_payload = read_json(run / "pages.json") if (run / "pages.json").exists() else {"documents": {}}
    if (run / "pages.json").exists() and freeze.get("files", {}).get("pages.json") != sha256(run / "pages.json"):
        reasons.append("frozen_pages_hash_mismatch")
    if any(d["format"] == "pdf" for d in manifest["documents"]) and not (run / "pages.json").exists():
        reasons.append("pdf_page_map_missing")
    source_lookup = document_geometry(manifest, documents, state.get("settings", {}), page_payload["documents"], reasons)
    regression = read_json(run / "regression.json") if (run / "regression.json").exists() else {}
    regression_ok = (regression.get("passed") is True and regression.get("tests", 0) > 0 and
                     regression.get("failures") == 0 and regression.get("errors") == 0 and
                     regression.get("command") == "mvn test" and bool(regression.get("suites")) and
                     state.get("regressionComplete") is True)
    if not regression_ok:
        reasons.append("full_regression_evidence_missing_or_failed")
    suites = regression.get("suites", [])
    if regression_ok and (sum(s.get("tests", 0) for s in suites) != regression["tests"] or
                          any(s.get("failures", -1) != 0 or s.get("errors", -1) != 0 for s in suites)):
        regression_ok = False
        reasons.append("regression_suite_totals_mismatch")
    ledger = budget_audit(run)
    if not ledger["valid"] or ledger.get("blocked") or state.get("budgetBlocked"):
        reasons.append("cost_ledger_invalid_or_blocked")
    gold = {q["id"]: q for q in manifest["questions"]}
    private = read_json(run / "private-mapping.json") if (run / "private-mapping.json").exists() else {"items": []}
    mapping = {}
    for item in private["items"]:
        if item["token"] in mapping:
            reasons.append("duplicate_private_mapping_token")
        mapping[item["token"]] = item
    reviewed = load_reviews(run, reviews, mapping, reasons)
    grouped = defaultdict(dict)
    per_question = []
    reviewed_count = 0
    for row in questions:
        ident, arm = row["questionId"], row["arm"]
        if ident not in gold or arm not in ARMS:
            reasons.append("unknown_question_or_arm")
            continue
        q = gold[ident]
        if row.get("gold") != q or row.get("question") != q["question"] or row.get("split") != q["split"] or row.get("documentId") != q["documentId"]:
            reasons.append("question_or_gold_changed_between_arms")
        if row.get("status") != "ok":
            continue
        if len(row.get("retrieved", [])) > 5:
            reasons.append("retrieved_more_than_top5")
        validate_question_sources(row, source_lookup, reasons)
        scores = retrieval_metrics(q, row)
        token = row.get("reviewToken")
        expected_mapping = {"token": token, "arm": arm, "split": q["split"], "questionId": ident, "documentId": q["documentId"]}
        if mapping.get(token) != expected_mapping:
            reasons.append("private_mapping_mismatch")
        review = reviewed.get(token)
        needed = "answerCorrect" if q["answerable"] else "refusalCorrect"
        if (review is not None and type(review.get(needed)) is bool and isinstance(review.get("citationSupported"), list)
                and isinstance(review.get("rationale"), str) and review["rationale"].strip()
                and len(review["citationSupported"]) == len(row["cited"]) and all(type(v) is bool for v in review["citationSupported"])):
            reviewed_count += 1
            if q["answerable"]:
                scores["answerAccuracy"] = float(row["grounded"] is True and review["answerCorrect"])
                scores["citationSupport"] = float(bool(row["cited"]) and all(review["citationSupported"]) and scores["citedEvidenceRecallAllRequired"] == 1)
                scores["strictPassAccuracy"] = scores["answerAccuracy"] * scores["citationSupport"]
            else:
                scores["refusalAccuracy"] = float(row["grounded"] is False and not row["cited"] and review["refusalCorrect"])
                scores["strictPassAccuracy"] = scores["refusalAccuracy"]
        else:
            reasons.append("review_missing_or_invalid")
        # Exact strings are diagnostics only; they never populate adjudicated answer/citation scores.
        scores["auxRequiredFactStringRecall"] = (sum(f in row["answer"] for f in q["requiredFacts"]) / len(q["requiredFacts"])) if q["requiredFacts"] else None
        scores["auxForbiddenStringHit"] = float(any(f in row["answer"] for f in q["forbiddenConfusions"]))
        grouped[ident][arm] = scores
        per_question.append({"questionId": ident, "documentId": q["documentId"], "split": q["split"], "arm": arm, "scores": scores})
    if reviewed_count != 240 or len(reviewed) != 240:
        reasons.append("independent_review_coverage_incomplete")
    pairs = [{"documentId": gold[ident]["documentId"], "split": gold[ident]["split"], "A": values["A"], "B": values["B"]}
             for ident, values in grouped.items() if set(values) == set(ARMS)]
    metrics = {}
    names = ("evidenceRecall@5", "contextEvidenceRecall", "citedEvidenceRecall", "evidenceRecall@5Utf16Coverage",
             "evidenceRecall@5AllRequired", "contextEvidenceRecallAllRequired", "citedEvidenceRecallAllRequired",
             "answerAccuracy", "refusalAccuracy", "citationSupport", "strictPassAccuracy")
    for split in SPLITS:
        metrics[split] = {name: paired_cluster([p for p in pairs if p["split"] == split], name, iterations) for name in names}
    llm_docs = [d for d in documents if d["arm"] == "B"]
    llm_success = {}
    for label, expected in (("overall", 12), ("dev", 4), ("validation", 8)):
        selected = [d for d in llm_docs if label == "overall" or d["split"] == label]
        successes = sum(d.get("status") == "ready" and d.get("coverageValid") is True and
                        d.get("strategy") == "LLM" and not d.get("fallbackReason") for d in selected)
        llm_success[label] = {"successes": successes, "expected": expected, "rate": successes / expected,
                              "fallbacks": [d["documentId"] for d in selected if d.get("fallbackReason")]}
    holdout = metrics["validation"]
    delta = lambda name: holdout[name]["deltaBminusA"]
    gates = {
        "holdoutEvidenceRecall@5ImprovementAtLeast0.05": delta("evidenceRecall@5") is not None and delta("evidenceRecall@5") >= .05 - 1e-12,
        "holdoutAnswerNonRegression": delta("answerAccuracy") is not None and delta("answerAccuracy") >= -1e-12,
        "holdoutRefusalNonRegression": delta("refusalAccuracy") is not None and delta("refusalAccuracy") >= -1e-12,
        "holdoutCitationSupportNonRegression": delta("citationSupport") is not None and delta("citationSupport") >= -1e-12,
        "holdoutStrictPassNonRegression": delta("strictPassAccuracy") is not None and delta("strictPassAccuracy") >= -1e-12,
        "validationLlmDocumentSuccessAtLeast90Percent": llm_success["validation"]["rate"] >= .9,
        "fullRegression": regression_ok,
        "completeIndependentReview": reviewed_count == 240 and len(reviewed) == 240,
        "costLedger": ledger["valid"] and not ledger.get("blocked"),
    }
    reasons = sorted(set(reasons))
    complete = not reasons
    latency = {}
    lengths = {}
    for arm in ARMS:
        for label, rows in (("import", documents), ("question", questions)):
            values = [r["latencyMs"] for r in rows if r["arm"] == arm and isinstance(r.get("latencyMs"), (int, float))]
            latency[arm + "." + label] = {"n": len(values), "p50Ms": percentile(values, .5) if values else None,
                                         "p95Ms": percentile(values, .95) if values else None}
        for split in ("overall", *SPLITS):
            chunks = [c for d in documents if d["arm"] == arm and (split == "overall" or d["split"] == split) for c in d.get("chunks", [])]
            lengths[arm + "." + split] = {}
            for unit, sizes in (("codePoints", [len(c["text"]) for c in chunks]),
                                ("utf16Units", [len(c["text"].encode("utf-16-le")) // 2 for c in chunks])):
                lengths[arm + "." + split][unit] = {"count": len(sizes), "p50": percentile(sizes, .5) if sizes else None,
                                                     "p95": percentile(sizes, .95) if sizes else None, "max": max(sizes) if sizes else None}
    return {"schemaVersion": 1, "runId": state["runId"], "status": ("pass" if all(gates.values()) else "fail") if complete else "incomplete",
            "complete": complete, "incompleteReasons": reasons, "gates": gates, "metrics": metrics,
            "counts": {"expectedDocuments": 24, "observedDocuments": len(documents), "expectedQuestions": 240,
                       "observedQuestions": len(questions), "reviewedQuestions": reviewed_count, "pairedQuestions": len(pairs)},
            "llmDocumentSuccess": llm_success,
            "budget": ledger, "latency": latency, "chunkLengths": lengths, "perQuestion": per_question,
            "method": {"aggregation": "document macro mean; answerable evidence items fully covered by union of original UTF-16 intervals",
                       "confidenceInterval": "paired document-cluster percentile bootstrap, 95%, seed 20261118; descriptive, gate uses point delta",
                       "unanswerable": "excluded from evidence recall; independent refusal review required",
                       "auxiliary": "string/numeric checks cannot approve answers or citation support"}}


def review_template(run, destination):
    package = read_json(run / "blind-review.json")
    save_json(destination, {"schemaVersion": 1, "blindPackageSha256": sha256(run / "blind-review.json"),
                           "independent": False, "reviewerKind": "independent_codex_agent", "reviewer": "",
                           "underlyingModelId": None, "temperature": None, "modelMetadataNote": "Not exposed by the Codex agent tool; do not invent.", "items": [
                               {"token": item["token"], "answerCorrect": None, "refusalCorrect": None,
                                "citationSupported": [None] * len(item["citedTexts"]), "rationale": ""}
                               for item in package["items"]]})


class ReportTests(unittest.TestCase):
    def fixture(self, root):
        """A complete synthetic artifact set; this never makes a model/Chroma request."""
        root = Path(root)
        text = "abcdeFGHIJ"
        digest = hashlib.sha256(text.encode()).hexdigest()
        documents, gold, imports, questions, private, blind, reviews = [], [], [], [], [], [], []
        for doc_index in range(12):
            doc = "doc" + str(doc_index)
            split = "dev" if doc_index < 4 else "validation"
            documents.append({"id": doc, "split": split, "format": "txt", "path": "documents/" + doc + ".txt",
                              "sha256": digest, "textPath": "extracted/" + doc + ".txt", "textSha256": digest})
            for arm in ARMS:
                cuts = [5, 10] if arm == "A" else [10]
                chunks, spans = [], []
                prior = 0
                for number, end in enumerate(cuts, 1):
                    ident = arm + doc + "c" + str(number)
                    chunks.append({"id": ident, "documentId": doc, "index": number, "text": text[:end], "pageStart": None, "pageEnd": None})
                    spans.append({"chunkId": ident, "bodyStart": prior, "bodyEnd": end, "start": 0, "end": end})
                    prior = end
                imports.append({"documentId": doc, "split": split, "arm": arm, "status": "ready", "coverageValid": True,
                                "strategy": "RULE" if arm == "A" else "LLM", "fallbackReason": None,
                                "latencyMs": 1, "chunks": chunks, "spans": spans})
            for question_index in range(10):
                ident = doc + "q" + str(question_index)
                answerable = question_index < 8
                q = {"id": ident, "documentId": doc, "split": split, "question": "Question " + ident,
                     "answerable": answerable, "referenceAnswer": "abc" if answerable else "insufficient",
                     "requiredFacts": ["abc"] if answerable else [], "forbiddenConfusions": [],
                     "evidence": [{"start": 0, "end": 10, "text": text}] if answerable else [], "category": "fact"}
                gold.append(q)
                for arm in ARMS:
                    token = ident + arm
                    end = 5 if arm == "A" else 10
                    source = {"source": {"chunkId": arm + doc + "c1", "documentId": doc, "chunkIndex": 1,
                                         "fileName": doc + ".txt", "text": text[:end], "pageStart": None, "pageEnd": None},
                              "interval": {"documentId": doc, "start": 0, "end": end}, "bodyStart": 0, "bodyEnd": end}
                    cited = [source] if answerable else []
                    questions.append({"questionId": ident, "documentId": doc, "split": split, "arm": arm, "question": q["question"],
                                      "gold": q, "status": "ok", "answer": q["referenceAnswer"], "grounded": answerable,
                                      "retrieved": cited, "context": cited, "cited": cited, "reviewToken": token, "latencyMs": 2})
                    private.append({"token": token, "arm": arm, "split": split, "questionId": ident, "documentId": doc})
                    blind.append({"token": token, "question": q["question"], "sourceText": text, "citedTexts": [text[:end]] if answerable else []})
                    reviews.append({"token": token, "answerCorrect": True, "refusalCorrect": True,
                                    "citationSupported": [True] if answerable else [], "rationale": "Synthetic fixture only"})
        save_json(root / "manifest.json", {"schemaVersion": 1, "seed": SEED, "documents": documents, "questions": gold})
        save_json(root / "freeze.json", {"manifestSHA256": sha256(root / "manifest.json"), "files": {"manifest.json": sha256(root / "manifest.json")}})
        save_json(root / "run.json", {"runId": "synthetic", "mode": "live", "datasetVerified": True, "acquisitionComplete": True,
                                     "cleanupComplete": True, "regressionComplete": True, "settings": {"chunkSize": 800, "chunkOverlap": 100},
                                     "manifestSha256": sha256(root / "manifest.json"), "freezeSha256": sha256(root / "freeze.json")})
        save_json(root / "regression.json", {"passed": True, "tests": 1, "failures": 0, "errors": 0, "command": "mvn test",
                                            "suites": [{"tests": 1, "failures": 0, "errors": 0}]})
        save_json(root / "private-mapping.json", {"items": private})
        save_json(root / "blind-review.json", {"items": blind})
        save_json(root / "reviews.json", {"schemaVersion": 1, "independent": True, "reviewerKind": "independent_codex_agent", "reviewer": "synthetic-test",
                                         "blindPackageSha256": sha256(root / "blind-review.json"), "items": reviews})
        for name, rows in (("documents", imports), ("questions", questions)):
            (root / (name + ".jsonl")).write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")
        base = {"accountedRmb": 0, "runAccountedRmb": 0, "deepSeekCalls": 0, "qwenCalls": 0}
        ledger = [{**base, "event": "open", "ratesPerMillion": {"chatInput": 2, "chatOutput": 8, "embeddingInput": .514}}]
        reserved = {"accountedRmb": .000065792, "runAccountedRmb": .000065792, "deepSeekCalls": 0, "qwenCalls": 1}
        ledger.append({**reserved, "event": "reserve", "request": 1, "stage": "embedding", "inputUpperBound": 128, "maxOutputTokens": 0, "reservedRmb": .000065792})
        actual = {"accountedRmb": .00000514, "runAccountedRmb": .00000514, "deepSeekCalls": 0, "qwenCalls": 1}
        ledger.append({**actual, "event": "settle", "request": 1, "stage": "embedding", "usageComplete": True,
                       "inputTokens": 10, "outputTokens": 0, "requestAccountedRmb": .00000514})
        ledger.append({**actual, "event": "close"})
        (root / "cost-ledger.jsonl").write_text("".join(json.dumps(r) + "\n" for r in ledger), encoding="utf-8")
        return root

    def test_complete_artifact_pipeline_requires_independent_review(self):
        with tempfile.TemporaryDirectory() as directory:
            run = self.fixture(directory)
            missing = compute_report(run, iterations=30)
            self.assertEqual(missing["status"], "incomplete")
            self.assertIsNone(missing["metrics"]["validation"]["answerAccuracy"]["A"])
            complete = compute_report(run, run / "reviews.json", iterations=30)
            self.assertEqual(complete["incompleteReasons"], [])
            self.assertEqual(complete["status"], "pass")
            self.assertEqual(complete["budget"]["tokensByStage"]["embedding"]["actualInputTokensKnown"], 10)
            self.assertEqual(complete["chunkLengths"]["B.validation"]["codePoints"]["count"], 8)

    def test_one_validation_fallback_fails_90_percent_even_if_overall_exceeds_it(self):
        with tempfile.TemporaryDirectory() as directory:
            run = self.fixture(directory)
            rows = read_jsonl(run / "documents.jsonl")
            val = next(d for d in rows if d["split"] == "validation" and d["arm"] == "B")
            val["strategy"], val["fallbackReason"] = "RULE", "MODEL_ERROR"
            (run / "documents.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")
            report = compute_report(run, run / "reviews.json", iterations=30)
            self.assertEqual(report["llmDocumentSuccess"]["overall"]["successes"], 11)
            self.assertEqual(report["llmDocumentSuccess"]["validation"]["rate"], 7 / 8)
            self.assertFalse(report["gates"]["validationLlmDocumentSuccessAtLeast90Percent"])
            self.assertEqual(report["status"], "fail")

    def test_technical_error_or_forged_source_is_incomplete(self):
        with tempfile.TemporaryDirectory() as directory:
            run = self.fixture(directory)
            rows = read_jsonl(run / "questions.jsonl")
            rows[0]["status"] = "error"
            rows[1]["retrieved"][0]["interval"]["end"] = 999
            (run / "questions.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")
            report = compute_report(run, run / "reviews.json", iterations=30)
            self.assertEqual(report["status"], "incomplete")
            self.assertIn("question_execution_failed", report["incompleteReasons"])
            self.assertIn("source_original_interval_mismatch", report["incompleteReasons"])

    def test_union_and_cross_document_exclusion(self):
        spans = [{"documentId": "d", "start": 0, "end": 8}, {"documentId": "d", "start": 4, "end": 10},
                 {"documentId": "x", "start": 0, "end": 99}]
        self.assertEqual(covered("d", 0, 20, spans), 10)

    def test_paired_cluster_is_reproducible_and_keeps_documents_together(self):
        pairs = [{"documentId": "d1", "A": {"m": 0}, "B": {"m": 1}},
                 {"documentId": "d1", "A": {"m": 0}, "B": {"m": 1}},
                 {"documentId": "d2", "A": {"m": 1}, "B": {"m": 0}}]
        actual = paired_cluster(pairs, "m", 200)
        self.assertEqual(actual, paired_cluster(pairs, "m", 200))
        self.assertEqual(actual["deltaBminusA"], 0)
        self.assertEqual(actual["paired95CI"], [-1, 1])
        self.assertIsNone(paired_cluster([], "m")["deltaBminusA"])

    def test_missing_or_small_dataset_cannot_pass(self):
        reasons = []
        completeness({"documents": [], "questions": []}, [], [], reasons)
        self.assertIn("dataset_cardinality_invalid", reasons)

    def test_partial_evidence_and_duplicate_chunks_do_not_inflate_recall(self):
        gold = {"documentId": "d", "answerable": True, "evidence": [{"start": 0, "end": 10}]}
        item = {"interval": {"documentId": "d", "start": 0, "end": 5}}
        result = retrieval_metrics(gold, {"retrieved": [item, item], "context": [], "cited": []})
        self.assertEqual(result["evidenceRecall@5"], 0)
        self.assertEqual(result["evidenceRecall@5Utf16Coverage"], .5)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path)
    parser.add_argument("--reviews", type=Path)
    parser.add_argument("--review-template", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(ReportTests)
        return 0 if unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful() else 2
    if not args.run:
        parser.error("--run is required")
    if args.review_template:
        review_template(args.run, args.review_template)
        print("Independent review template created; all scores remain unset.")
        return 0
    try:
        result = compute_report(args.run, args.reviews)
    except (ValueError, KeyError, TypeError, OSError, ArithmeticError):
        result = {"schemaVersion": 1, "status": "incomplete", "complete": False,
                  "incompleteReasons": ["invalid_or_missing_artifacts"], "gates": {}}
    save_json(args.run / "metrics.json", result)
    print(json.dumps({k: result[k] for k in ("status", "complete", "incompleteReasons", "gates")}, ensure_ascii=False, indent=2))
    return 0 if result["status"] == "pass" else (1 if result["status"] == "fail" else 2)


if __name__ == "__main__":
    sys.exit(main())
