"""Offline contract regressions; mutation tests do not alter the frozen files."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("dataset_validator", ROOT.parent / "validate_dataset.py")
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)


class ValidationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.gold = validator.read_json(ROOT / "manifest.json")

    def mutated(self, change, expected):
        manifest = copy.deepcopy(self.gold)
        change(manifest)
        with self.assertRaisesRegex(validator.InvalidDataset, expected):
            validator.validate_structure(ROOT, manifest)

    def test_frozen_dataset_passes(self):
        self.assertEqual(validator.validate(ROOT)["questions"], 120)

    def test_utf16_non_bmp_slice(self):
        self.assertEqual(validator.utf16_slice("甲😀乙", 1, 3), "😀")
        self.assertEqual(validator.utf16_slice("甲😀乙", 3, 4), "乙")

    def test_utf16_surrogate_split_rejected(self):
        with self.assertRaisesRegex(validator.InvalidDataset, "surrogate"):
            validator.utf16_slice("甲😀乙", 1, 2)

    def test_boolean_is_not_an_offset(self):
        with self.assertRaisesRegex(validator.InvalidDataset, "integers"):
            validator.utf16_slice("abc", True, 2)

    def test_python_codepoint_offset_not_accepted_as_java_offset(self):
        def change(manifest):
            # dev-01 deliberately has non-BMP symbols before every business evidence span.
            q = next(q for q in manifest["questions"] if q["documentId"] == "dev-01" and q["answerable"])
            q["evidence"][0]["start"] -= 1
            q["evidence"][0]["end"] -= 1
        self.mutated(change, "exact UTF-16")

    def test_evidence_text_tampering(self):
        self.mutated(lambda m: m["questions"][0]["evidence"][0].update(text="伪造证据"), "exact UTF-16")

    def test_cross_split_question_rejected(self):
        self.mutated(lambda m: m["questions"][0].update(split="validation"), "split mismatch")

    def test_answerable_requires_evidence(self):
        self.mutated(lambda m: m["questions"][0].update(evidence=[]), "Evidence required")

    def test_missing_required_facts(self):
        self.mutated(lambda m: m["questions"][0].update(requiredFacts=[]), "Empty requiredFacts")

    def test_duplicate_questions(self):
        self.mutated(lambda m: m["questions"][1].update(question=m["questions"][0]["question"]), "Duplicate normalized question")

    def test_unknown_field_breaks_java_contract(self):
        self.mutated(lambda m: m["documents"][0].update(ruleFamily="extra"), "Unexpected document fields")

    def test_per_document_8_2_not_just_global_count(self):
        def change(manifest):
            question = next(q for q in manifest["questions"] if not q["answerable"])
            question.update(answerable=True, category="fact", evidence=copy.deepcopy(manifest["questions"][0]["evidence"]))
        self.mutated(change, "8 answerable")

    def test_frozen_source_tampering(self):
        original = Path.read_bytes
        target = ROOT / "corpus.py"
        def altered(path):
            content = original(path)
            return content + b"\n# changed" if path.resolve() == target.resolve() else content
        with mock.patch.object(Path, "read_bytes", altered):
            with self.assertRaisesRegex(validator.InvalidDataset, "Frozen file changed: corpus.py"):
                validator.validate_freeze(ROOT)

    def test_document_tampering(self):
        self.mutated(lambda m: m["documents"][0].update(sha256="0" * 64), "SHA-256 mismatch")

    def test_bad_pdf_pagination(self):
        original = validator.read_json
        def changed(path):
            content = original(path)
            if path.name == "pages.json":
                next(iter(content["documents"].values()))[0]["end"] = 0
            return content
        with mock.patch.object(validator, "read_json", changed):
            with self.assertRaisesRegex(validator.InvalidDataset, "UTF-16 evidence range"):
                validator.validate_structure(ROOT, copy.deepcopy(self.gold))

    def test_rule_family_reuse_rejected(self):
        original = validator.read_json
        def changed(path):
            content = original(path)
            if path.name == "coverage.json":
                content["documents"][4]["ruleFamily"] = content["documents"][0]["ruleFamily"]
            return content
        with mock.patch.object(validator, "read_json", changed):
            with self.assertRaisesRegex(validator.InvalidDataset, "Rule family reused"):
                validator.validate_structure(ROOT, copy.deepcopy(self.gold))

    def test_duplicate_json_key_rejected(self):
        with self.assertRaisesRegex(validator.InvalidDataset, "Duplicate JSON key"):
            json.loads('{"seed":1,"seed":2}', object_pairs_hook=validator.no_duplicates)

    def test_path_traversal_rejected(self):
        for value in ("../application.yml", "documents/../../application.yml", "C:/application.yml", "documents\\x.txt"):
            with self.subTest(path=value), self.assertRaises(validator.InvalidDataset):
                validator.safe_path(ROOT, value)


if __name__ == "__main__":
    unittest.main()
