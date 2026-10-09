"""Offline tests for the PC-only Lumena listing feedback benchmark."""
import importlib.util
import json
import unittest
from pathlib import Path


spec = importlib.util.spec_from_file_location(
    "listing_preference_benchmark",
    Path(__file__).with_name("listing_preference_benchmark.py"),
)
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)


def row(title, description, feedback, *, group_id=None):
    value = {"title": title, "description": description, "feedback": feedback}
    if group_id:
        value["group_id"] = group_id
    return value


class PreferenceBenchmarkTest(unittest.TestCase):
    def parse(self, records):
        return b.parse_lines("\n".join(json.dumps(x, ensure_ascii=False) for x in records))

    def test_24_owner_labels_are_never_synthesized(self):
        with self.assertRaisesRegex(ValueError, "empty"):
            b.parse_lines("")
        with self.assertRaisesRegex(ValueError, "feedback"):
            self.parse([{"title": "Driver", "description": "Drive a car"}])

    def test_known_driver_rejection_is_summarized_not_promoted(self):
        records = []
        for i in range(24):
            title = f"Kierowca kat B {i}" if i < 6 else f"Monter budynku {i}"
            records.append(row(title, f"Praca zadania nr {i}", -1 if i < 6 else (1 if i % 2 else -1)))
        report = b.evaluate(self.parse(records))
        self.assertEqual(24, report["n"])
        self.assertEqual(6, report["driver"]["n"])
        self.assertEqual(6, report["driver"]["thumbs_down"])
        self.assertEqual(0, report["driver"]["thumbs_up"])
        self.assertFalse(report["promotion_allowed"])
        self.assertEqual("EXPLORATORY_NOT_PROMOTION_PROOF", report["status"])

    def test_explicit_feedback_only(self):
        for wrong in ("", "neutral", "0", None):
            with self.assertRaises(ValueError):
                self.parse([row("Title", "Description", wrong)])
        self.assertEqual([1, 0, 1, 0], [
            x["label"] for x in self.parse([
                row("A", "a description", "👍"),
                row("B", "b description", "👎"),
                row("C", "c description", 1),
                row("D", "d description", -1),
            ])
        ])

    def test_same_repost_group_is_excluded_from_training_fold(self):
        records = self.parse([
            row("Kierowca", "Praca na budowie", 1, group_id="repost-1"),
            row("Kierowca", "Praca na budowie", 1, group_id="repost-1"),
            row("Monter", "Naprawa sieci", -1, group_id="other"),
        ])
        before = b.train([records[2]])
        actual = b.evaluate(records)
        self.assertEqual(2, actual["independent_groups"])
        self.assertEqual(3, actual["n"])
        self.assertAlmostEqual(b.predict(records[0], before), b.predict(records[1], before))
        self.assertFalse(actual["promotion_allowed"])

    def test_deterministic_aggregate_without_raw_text(self):
        records = self.parse([
            row("Kierowca prywatny", "Warszawa 12345 secret", -1),
            row("Monter instalacji", "Praca w Legionowo", 1),
        ])
        a, c = b.evaluate(records), b.evaluate(records)
        self.assertEqual(a, c)
        self.assertNotIn("secret", json.dumps(a))
        self.assertNotIn("12345", json.dumps(a))

    def test_no_same_kind_negative_becomes_positive(self):
        one = self.parse([row("Kierowca", "Praca", "👎")])[0]
        self.assertEqual(0, one["label"])
        self.assertTrue(one["features"]["driver"])
        self.assertFalse(b.evaluate([one])["comparable_class_support"])


if __name__ == "__main__":
    unittest.main()
