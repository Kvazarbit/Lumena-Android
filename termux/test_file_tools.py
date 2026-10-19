"""Regression tests for file tools and persistent read-only roots."""
import importlib.util
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


class FileReadRangeTest(unittest.TestCase):
    def test_observed_hash_cas_rejects_external_change_and_allows_fresh_retry(self):
        import hashlib
        target = self.workspace / 'a.py'
        target.write_text('x = 1\n', encoding='utf-8')
        read = self.b.execute_tool('file.read', {'path': 'a.py'})
        self.assertTrue(read['sourceComplete'])
        self.assertEqual(hashlib.sha256(target.read_bytes()).hexdigest(), read['sourceSha256'])
        target.write_text('x = 2\n', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'FILE_PRECONDITION_CHANGED'):
            self.b.execute_tool('file.write', {'path': 'a.py', 'content': 'x = 3\n', 'expected_sha256': read['sourceSha256']})
        self.assertEqual('x = 2\n', target.read_text(encoding='utf-8'))
        current = self.b.execute_tool('file.read', {'path': 'a.py'})
        self.b.execute_tool('file.patch', {'path': 'a.py', 'old': '2', 'new': '3', 'expected_sha256': current['sourceSha256']})
        self.assertEqual('x = 3\n', target.read_text(encoding='utf-8'))

    def test_creation_requires_absence_and_partial_read_is_not_a_full_observation(self):
        self.b.execute_tool('file.write', {'path': 'new.py', 'content': 'a\nb\n', 'expected_sha256': 'ABSENT'})
        with self.assertRaisesRegex(ValueError, 'FILE_PRECONDITION_CHANGED'):
            self.b.execute_tool('file.write', {'path': 'new.py', 'content': 'lost', 'expected_sha256': 'ABSENT'})
        read = self.b.execute_tool('file.read', {'path': 'new.py', 'start_line': '1'})
        self.assertFalse(read['sourceComplete'])
        self.assertEqual('a\nb\n', (self.workspace/'new.py').read_text(encoding='utf-8'))

    def test_failed_atomic_replace_preserves_live_file_and_cleans_staging_file(self):
        target = self.workspace / 'a.py'
        target.write_text('old', encoding='utf-8')
        with patch.object(self.b.os, 'replace', side_effect=OSError('disk failure')):
            with self.assertRaisesRegex(OSError, 'disk failure'):
                self.b.execute_tool('file.write', {'path': 'a.py', 'content': 'new'})
        self.assertEqual('old', target.read_text(encoding='utf-8'))
        self.assertFalse(list(self.workspace.glob('.lumena-write-*')))

    def test_oversized_and_invalid_utf8_reads_cannot_claim_complete_source(self):
        target = self.workspace / 'large.txt'
        target.write_bytes(b'x' * (2 * 1024 * 1024 + 1))
        result = self.b.execute_tool('file.read', {'path': 'large.txt'})
        self.assertFalse(result['sourceComplete'])
        self.assertIsNone(result['sourceSha256'])
        self.assertLess(len(result['stdout']), 200_000)
        target.write_bytes(b'\xff\xfe')
        self.assertFalse(self.b.execute_tool('file.read', {'path': 'large.txt'})['sourceComplete'])

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lumena-file-read-")
        self.root = Path(self.temp.name)
        self.workspace = self.root / "workspace"
        self.external = self.root / "external"
        self.workspace.mkdir()
        self.external.mkdir()
        self.read_roots = self.root / "read_roots.conf"
        self.read_roots.write_text(
            f"phone={self.external}\n",
            encoding="utf-8",
        )
        self.env = patch.dict(
            os.environ,
            {
                "LUMENA_WORKSPACE": str(self.workspace),
                "LUMENA_BRIDGE_TOKEN": "fixture-token",
                "LUMENA_READONLY_ROOTS": "",
                "LUMENA_READ_ROOTS_FILE": str(self.read_roots),
            },
        )
        self.env.start()
        spec = importlib.util.spec_from_file_location(
            "file_read_bridge",
            Path(__file__).with_name("bridge.py"),
        )
        self.b = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.b)
        self.addCleanup(self.env.stop)
        self.addCleanup(self.temp.cleanup)

    def test_read_range_is_one_based_and_inclusive(self):
        target = self.workspace / "sample.txt"
        target.write_text("one\ntwo\nthree\nfour\n", encoding="utf-8")

        result = self.b.execute_tool(
            "file.read",
            {"path": "sample.txt", "start_line": "2", "end_line": "3"},
        )

        self.assertTrue(result["ok"])
        self.assertEqual("two\nthree\n", result["stdout"])

    def test_open_ended_range_and_out_of_range_start(self):
        target = self.workspace / "sample.txt"
        target.write_text("one\ntwo\nthree\n", encoding="utf-8")

        tail = self.b.execute_tool(
            "file.read",
            {"path": "sample.txt", "start_line": "2"},
        )
        empty = self.b.execute_tool(
            "file.read",
            {"path": "sample.txt", "start_line": "99"},
        )

        self.assertEqual("two\nthree\n", tail["stdout"])
        self.assertEqual("", empty["stdout"])

    def test_invalid_ranges_are_rejected(self):
        target = self.workspace / "sample.txt"
        target.write_text("one\ntwo\n", encoding="utf-8")

        for args in (
            {"path": "sample.txt", "start_line": "0"},
            {"path": "sample.txt", "start_line": "2", "end_line": "1"},
            {"path": "sample.txt", "start_line": "x"},
        ):
            with self.assertRaises(ValueError):
                self.b.execute_tool("file.read", args)


    def test_persistent_read_only_root_is_discoverable_searchable_and_readable(self):
        target = self.external / "btc_history.csv"
        target.write_text(
            "timestamp,close\n2026-01-01,93000\n# bitcoin historical data\n",
            encoding="utf-8",
        )

        listing = self.b.execute_tool("workspace.list", {})
        self.assertTrue(listing["ok"])
        self.assertIn("@phone/", listing["stdout"])

        search = self.b.execute_tool(
            "file.search",
            {"path": "@phone", "query": "bitcoin"},
        )
        self.assertTrue(search["ok"])
        self.assertIn("@phone/btc_history.csv", search["stdout"])

        read = self.b.execute_tool(
            "file.read",
            {"path": "@phone/btc_history.csv"},
        )
        self.assertTrue(read["ok"])
        self.assertIn("93000", read["stdout"])

        health = self.b.execute_tool("health", {})
        self.assertIn("read_only_roots=@phone", health["stdout"])
        self.assertIn("version=0.32", health["stdout"])
        self.assertIn("file_cas_v1", health["stdout"])

    def test_read_only_root_never_expands_write_scope(self):
        with self.assertRaises(ValueError):
            self.b.safe_path(str(self.external / "must_not_write.txt"))


if __name__ == "__main__":
    unittest.main()
