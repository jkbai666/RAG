"""Reproducible, network-free corpus build. Never reads application configuration.

First build: python evaluation/dataset/generate.py
Reproduce without writes: python evaluation/dataset/generate.py --check
A frozen corpus can only be replaced with the explicit --rebuild-frozen switch.
Do not use that switch during live evaluation; create a separately versioned set instead.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import textwrap

sys.dont_write_bytecode = True
from corpus import DOCUMENTS
from appendices import enrich

enrich(DOCUMENTS)

ROOT = Path(__file__).resolve().parent
SEED = 20261118
DATE = "2026-11-18"
PDFBOX_VERSION = "3.0.7"


def utf16_len(text):
    return len(text.encode("utf-16-le")) // 2


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encoded_json(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def pdf_layout(blocks):
    pages, page, anchors = [], [], {}
    for key, value in blocks:
        if value == "\f":
            if not page:
                raise ValueError("Empty PDF page")
            pages.append(page)
            page = []
            continue
        value.encode("ascii")  # Standard Helvetica is deliberate, no platform font substitution.
        lines = []
        for line in value.splitlines():
            lines.extend(textwrap.wrap(line, width=91, break_long_words=False,
                                       break_on_hyphens=False) or [""])
        anchors[key] = "\n".join(lines)
        if page and len(page) + len(lines) + 1 > 55:
            pages.append(page)
            page = []
        page.extend(lines + [""])
    if page:
        pages.append(page)
    if any(len(p) > 55 for p in pages):
        raise ValueError("PDF page overflows the visible text area")
    return pages, anchors


def make_pdf(pages):
    """Deterministic PDF 1.4 with real selectable ASCII text, no raster/OCR dependency."""
    objects = []
    def add(data):
        objects.append(data.encode("ascii") if isinstance(data, str) else data)
        return len(objects)
    add("<< /Type /Catalog /Pages 2 0 R >>")
    add("")
    add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
    page_ids = []
    for lines in pages:
        commands = ["BT /F1 10 Tf 48 788 Td 13 TL"]
        for index, line in enumerate(lines):
            if index:
                commands.append("T*")
            escaped = line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
            commands.append(f"({escaped}) Tj")
        commands.append("ET")
        stream = "\n".join(commands).encode("ascii")
        content_id = add(f"<< /Length {len(stream)} >>\nstream\n".encode("ascii") + stream + b"\nendstream")
        page_id = add(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
                      f"/Resources << /Font << /F1 3 0 R >> >> /Contents {content_id} 0 R >>")
        page_ids.append(page_id)
    objects[1] = ("<< /Type /Pages /Kids [" + " ".join(f"{p} 0 R" for p in page_ids)
                  + f"] /Count {len(page_ids)} >>").encode("ascii")
    out, offsets = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n"), [0]
    for index, data in enumerate(objects, 1):
        offsets.append(len(out))
        out.extend(f"{index} 0 obj\n".encode("ascii") + data + b"\nendobj\n")
    xref = len(out)
    out.extend(f"xref\n0 {len(objects)+1}\n0000000000 65535 f \n".encode("ascii"))
    for offset in offsets[1:]:
        out.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
    out.extend(f"trailer\n<< /Size {len(objects)+1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode("ascii"))
    return bytes(out)


def pdfbox_classpath(production=False):
    override = os.environ.get("PDFBOX_CLASSPATH")
    if override:
        entries = override.split(os.pathsep)
    else:
        cache = Path(os.environ.get("M2_REPO", str(Path.home() / ".m2" / "repository")))
        entries = [str(cache / "org/apache/pdfbox" / name / PDFBOX_VERSION / f"{name}-{PDFBOX_VERSION}.jar")
                   for name in ("pdfbox", "fontbox", "pdfbox-io")]
        logging = sorted((cache / "commons-logging/commons-logging").glob("*/*.jar"))
        logging = [p for p in logging if not p.name.endswith(("-sources.jar", "-javadoc.jar"))]
        if not logging:
            raise ValueError("Missing cached commons-logging jar; set PDFBOX_CLASSPATH (no download is attempted)")
        entries.append(str(logging[-1]))
    if production:
        classes = ROOT.parents[1] / "backend/target/classes"
        if not (classes / "local/rag/DocumentParser.class").is_file():
            raise ValueError("Compile backend production parser before --verify-parser; no service startup is needed")
        entries.append(str(classes))
    if not entries or any(not Path(p).exists() for p in entries):
        raise ValueError("Missing local PDFBox 3.0.7 jars; set PDFBOX_CLASSPATH to cached dependencies")
    return os.pathsep.join(entries)


def extract(paths, production=False):
    java = shutil.which("java")
    if not java:
        raise ValueError("Java 17+ is required for offline PDFBox extraction")
    command = [java, "-Dfile.encoding=UTF-8", "-cp", pdfbox_classpath(production), str(ROOT / "PdfText.java")]
    if production:
        command.append("--verify-parser")
    # Source launch compiles only this isolated helper; it never loads Spring configuration.
    result = subprocess.run(command + [str(p) for p in paths], capture_output=True, check=False)
    if result.returncode:
        raise ValueError("Offline Java extraction failed: " + result.stderr.decode("utf-8", errors="replace")[-3000:])
    return json.loads(result.stdout.decode("utf-8"))


def documents_bytes():
    payloads, layouts = {}, {}
    for document in sorted(DOCUMENTS, key=lambda d: d["id"]):
        doc_id, fmt = document["id"], document["format"]
        path = f"documents/{doc_id}.{fmt}"
        if fmt == "pdf":
            pages, anchors = pdf_layout(document["blocks"])
            payloads[path] = make_pdf(pages)
        else:
            anchors = dict(document["blocks"])
            payloads[path] = ("\n\n".join(anchors.values()) + "\n").encode("utf-8")
        layouts[doc_id] = anchors
    return payloads, layouts


def build_payloads(extracted):
    payloads, layouts = documents_bytes()
    documents, questions, page_index, coverage = [], [], {}, []
    by_name = {p["file"]: p for p in extracted}
    for document in sorted(DOCUMENTS, key=lambda d: d["id"]):
        doc_id, fmt = document["id"], document["format"]
        path, text_path = f"documents/{doc_id}.{fmt}", f"extracted/{doc_id}.txt"
        text = by_name[f"{doc_id}.{fmt}"]["text"]
        payloads[text_path] = text.encode("utf-8")
        pages = by_name[f"{doc_id}.{fmt}"]["pages"]
        if pages:
            page_index[doc_id] = pages
        documents.append(dict(id=doc_id, split=document["split"], path=path, format=fmt,
                              sha256=sha(payloads[path]), textPath=text_path, textSha256=sha(payloads[text_path])))
        for i, source in enumerate(document["questions"], 1):
            row = {k: v for k, v in source.items() if k != "keys"}
            evidence = []
            for key in source["keys"]:
                snippet = layouts[doc_id][key]
                if fmt == "pdf":
                    # Preserve actual PDFBox line separators (CRLF on Windows); do not normalize text.
                    candidates = [snippet.replace("\n", separator) for separator in ("\n", "\r\n", "\r")]
                    matched = [candidate for candidate in dict.fromkeys(candidates) if text.count(candidate) == 1]
                    if len(matched) != 1:
                        raise ValueError(f"{doc_id} evidence anchor {key} does not match extracted PDF lines")
                    snippet = matched[0]
                if text.count(snippet) != 1:
                    raise ValueError(f"{doc_id} evidence anchor {key} is not uniquely present in PDFBox text")
                start = text.index(snippet)
                evidence.append(dict(start=utf16_len(text[:start]), end=utf16_len(text[:start + len(snippet)]), text=snippet))
            evidence.sort(key=lambda e: e["start"])
            row.update(id=f"{doc_id}-q{i:02d}", documentId=doc_id, split=document["split"], evidence=evidence)
            questions.append(row)
        coverage.append(dict(documentId=doc_id, split=document["split"], title=document["title"],
                             ruleFamily=document["family"], codePoints=len(text), utf16Units=utf16_len(text),
                             utf8Bytes=len(payloads[text_path]), pages=len(pages),
                             parserUnits800_100=by_name[f"{doc_id}.{fmt}"]["unitCount"],
                             answerable=8, unanswerable=2))
    payloads["manifest.json"] = encoded_json(dict(schemaVersion=1, seed=SEED, frozenAt=DATE,
                                                 documents=documents, questions=questions))
    payloads["pages.json"] = encoded_json(dict(schemaVersion=1, offsetEncoding="UTF-16", documents=page_index))
    payloads["coverage.json"] = encoded_json(dict(schemaVersion=1, documents=coverage,
                                                 totalCodePoints=sum(x["codePoints"] for x in coverage),
                                                 totalUtf16Units=sum(x["utf16Units"] for x in coverage)))
    return payloads


def frozen_files():
    return sorted(p for p in ROOT.rglob("*") if p.is_file() and p.name != "freeze.json"
                  and "__pycache__" not in p.parts and ".work" not in p.parts)


def freeze():
    files = {p.relative_to(ROOT).as_posix(): sha(p.read_bytes()) for p in frozen_files()}
    return encoded_json(dict(schemaVersion=1, frozenAt=DATE, seed=SEED,
                             manifestSHA256=files["manifest.json"],
                             validatorSHA256=sha((ROOT.parent / "validate_dataset.py").read_bytes()), files=files))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="reproduce in memory and verify all frozen bytes; no writes")
    parser.add_argument("--rebuild-frozen", action="store_true", help="explicitly replace frozen data BEFORE any live evaluation")
    parser.add_argument("--verify-parser", action="store_true", help="also compare compiled production DocumentParser text and page spans")
    args = parser.parse_args()
    if args.check and args.rebuild_frozen:
        parser.error("--check and --rebuild-frozen are mutually exclusive")
    if (ROOT / "freeze.json").exists() and not args.check and not args.rebuild_frozen:
        parser.error("Dataset is frozen; use --check. Do not rebuild during live evaluation.")
    source_bytes, _ = documents_bytes()
    if args.check:
        for name, data in source_bytes.items():
            if not (ROOT / name).is_file() or (ROOT / name).read_bytes() != data:
                raise ValueError(f"Non-reproducible source document: {name}")
    else:
        for name, data in source_bytes.items():
            path = ROOT / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    # Unit counts in coverage come from the actual production parser at 800/100.
    # Every build/check therefore validates the production extraction, not only PDFBox.
    extracted = extract([ROOT / name for name in source_bytes], production=True)
    payloads = build_payloads(extracted)
    if args.check:
        for name, data in payloads.items():
            if not (ROOT / name).is_file() or (ROOT / name).read_bytes() != data:
                raise ValueError(f"Non-reproducible generated file: {name}")
        if freeze() != (ROOT / "freeze.json").read_bytes():
            raise ValueError("Frozen source or file hash differs")
        print("PASS reproducibility: 12 documents, 120 questions; all bytes and frozen hashes match")
    else:
        for name, data in payloads.items():
            destination = ROOT / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
        (ROOT / "freeze.json").write_bytes(freeze())
        print("FROZEN 12 documents / 120 questions; run evaluation/validate_dataset.py")


if __name__ == "__main__":
    main()
