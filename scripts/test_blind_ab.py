"""Offline protocol tests only; NOT a live cognitive efficacy measurement."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location(
    "blind_ab", Path(__file__).with_name("blind_ab.py")
)
ab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ab)


class BlindABProtocolTest(unittest.TestCase):
    def setUp(self):
        self.public, self.private = ab.generate(bytes(range(32)), repeats=2)

    def test_stratified_10_new_cases_and_unmodified_oracle(self):
        self.assertEqual(len(self.public["cases"]), 10)
        self.assertEqual(len({x["id"] for x in self.public["cases"]}), 10)
        self.assertEqual({x["family"] for x in self.public["cases"]}, set(ab.FAMILIES))
        ab.oracle_validate(self.public, self.private)

    def test_public_contains_no_gold(self):
        self.assertNotIn('"answers"', ab.stable_json(self.public))
        self.assertNotIn('"seed"', ab.stable_json(self.public))
        for case in self.public["cases"]:
            self.assertNotIn("answer", case)
            gold = next(x["answer"] for x in self.private["answers"] if x["id"] == case["id"])
            self.assertNotIn("ANSWER=" + gold, ab.prompt_only(case))

    def test_rejects_tampered_oracle_and_challenge(self):
        k = json.loads(json.dumps(self.private))
        k["answers"][0]["answer"] = "forged"
        with self.assertRaises(AssertionError):
            ab.oracle_validate(self.public, k)
        c = json.loads(json.dumps(self.public))
        c["cases"][0]["prompt"] += "\\nforged"
        with self.assertRaises(AssertionError):
            ab.oracle_validate(c, self.private)

    def test_exact_paired_scoring_only_after_both_arms(self):
        gold = {x["id"]: x["answer"] for x in self.private["answers"]}
        control = {cid: {"answer": "ANSWER=" + value} for cid, value in gold.items()}
        intervention = json.loads(json.dumps(control))
        result = ab.score(self.public, self.private, control, intervention)
        self.assertEqual((result["direct_correct"], result["lumena_correct"]), (10, 10))
        self.assertEqual(result["delta_percentage_points"], 0.0)
        intervention.pop(next(iter(intervention)))
        with self.assertRaises(ValueError):
            ab.score(self.public, self.private, control, intervention)

    def test_mcnemar_and_strict_answer_parser(self):
        self.assertEqual(ab.exact_mcnemar(0, 0), 1.0)
        self.assertEqual(ab.exact_mcnemar(0, 6), 0.03125)
        self.assertEqual(ab.extract_answer("ANSWER=42"), "42")
        self.assertIsNone(ab.extract_answer("ANSWER=9\\nANSWER=10"))
        self.assertIsNone(ab.extract_answer("I would guess 99"))

    def test_direct_uses_only_loopback_and_will_not_overwrite(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / "out.jsonl"
            with self.assertRaises(ValueError):
                ab.direct_model(self.public, out, "http://example.org", 5)
            out.write_text("existing")
            with self.assertRaises(FileExistsError):
                ab.direct_model(self.public, out, "http://127.0.0.1:11434", 5)


if __name__ == "__main__":
    unittest.main()
