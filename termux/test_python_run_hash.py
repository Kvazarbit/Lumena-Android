"""python.run runs a script only while it still has the bytes the owner saw.

python.preview reports sha256 over the raw file bytes; python.run refuses
when approved_sha256 is missing or no longer matches the file. The hash
binds the entry script only: modules it imports are not hashed, and Termux
gives no process isolation, so this is not a sandbox.
"""
import hashlib
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = "hello.py"
SOURCE = (
    b"from pathlib import Path\n"
    b"Path('ran.txt').write_text('ran', encoding='utf-8')\n"
    b"print('hello from approved script')\n"
)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class PythonRunHashTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lumena-run-hash-")
        root = Path(self.temp.name)
        self.workspace = root / "workspace"
        self.workspace.mkdir()
        self.env = patch.dict(
            os.environ,
            {
                "LUMENA_WORKSPACE": str(self.workspace),
                "LUMENA_READONLY_ROOTS": "",
                "LUMENA_READ_ROOTS_FILE": str(root / "read_roots.conf"),
                "LUMENA_BRIDGE_TOKEN": "offline-fixture-token",
                "PYTHONIOENCODING": "utf-8",
            },
        )
        self.env.start()
        spec = importlib.util.spec_from_file_location(
            "run_hash_bridge",
            Path(__file__).with_name("bridge.py"),
        )
        self.b = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.b)
        self.addCleanup(self.env.stop)
        self.addCleanup(self.temp.cleanup)
        if not hasattr(self.b.signal, "SIGKILL"):
            # The bridge targets Termux/POSIX; this lets the test also run
            # on a Windows dev host, where run_process reads SIGKILL.
            shim = patch.object(self.b.signal, "SIGKILL", 9, create=True)
            shim.start()
            self.addCleanup(shim.stop)
        self.script = self.workspace / SCRIPT
        self.script.write_bytes(SOURCE)
        self.marker = self.workspace / "ran.txt"

    def _preview(self, script: str = SCRIPT) -> dict:
        result = self.b.execute_tool("python.preview", {"script": script})
        self.assertTrue(result["ok"], result.get("error"))
        return json.loads(result["stdout"])

    def _run(self, **extra) -> dict:
        return self.b.execute_tool("python.run", {"script": SCRIPT, **extra})

    def test_preview_reports_sha256_size_and_content_of_raw_bytes(self):
        payload = self._preview()
        self.assertEqual(sha256(SOURCE), payload["sha256"])
        self.assertEqual(len(SOURCE), payload["size"])
        self.assertEqual(SOURCE.decode("utf-8"), payload["content"])
        self.assertFalse(payload["truncated"])
        self.assertEqual(SCRIPT, payload["script"])
        self.assertFalse(self.marker.exists(), "python.preview must not run the script")

    def test_preview_hashes_bytes_not_decoded_text(self):
        raw = b"# \xff is not utf-8\r\nprint(1)\r\n"
        self.script.write_bytes(raw)
        payload = self._preview()
        self.assertEqual(sha256(raw), payload["sha256"])
        self.assertEqual(len(raw), payload["size"])

    def test_preview_truncates_large_scripts_but_hashes_all_bytes(self):
        big = b"x = 1\n" * 40_000
        self.script.write_bytes(big)
        payload = self._preview()
        self.assertTrue(payload["truncated"])
        self.assertEqual(len(big), payload["size"])
        self.assertEqual(sha256(big), payload["sha256"])
        self.assertLessEqual(
            len(payload["content"].encode("utf-8")),
            self.b.PYTHON_PREVIEW_MAX_BYTES,
        )
        self.assertTrue(big.decode("utf-8").startswith(payload["content"]))

    def test_preview_uses_the_same_path_rules_as_python_run(self):
        (self.workspace / "notes.txt").write_text("x", encoding="utf-8")
        for script in ("../outside.py", "notes.txt", "missing.py"):
            with self.subTest(script=script):
                with self.assertRaises((ValueError, FileNotFoundError)):
                    self.b.execute_tool("python.preview", {"script": script})

    def test_matching_hash_runs(self):
        result = self._run(approved_sha256=self._preview()["sha256"])
        self.assertTrue(result["ok"], (result.get("error") or result.get("stderr") or "")[:800])
        self.assertIn("hello from approved script", result["stdout"])
        self.assertTrue(self.marker.exists())

    def test_mismatched_hash_is_refused_and_the_script_does_not_run(self):
        approved = self._preview()["sha256"]
        self.script.write_bytes(SOURCE + b"print('added after approval')\n")
        with self.assertRaises(PermissionError) as refused:
            self._run(approved_sha256=approved)
        self.assertIn("approved_sha256", str(refused.exception))
        self.assertFalse(self.marker.exists(), "a changed script ran after approval")

    def test_missing_hash_is_refused_and_the_script_does_not_run(self):
        for extra in ({}, {"approved_sha256": ""}, {"approved_sha256": "not-a-hash"}):
            with self.subTest(args=extra):
                with self.assertRaises(PermissionError) as refused:
                    self._run(**extra)
                self.assertIn("approved_sha256", str(refused.exception))
        self.assertFalse(self.marker.exists(), "python.run ran without an approved hash")

    def test_health_advertises_hash_bound_python_run(self):
        health = self.b.execute_tool("health", {})["stdout"]
        features = next(
            line.partition("=")[2].split(",")
            for line in health.splitlines()
            if line.startswith("features=")
        )
        self.assertIn("python_run_sha256_v1", features)


if __name__ == "__main__":
    unittest.main()
