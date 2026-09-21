"""Bridge regressions for side-effect-free syntax verification."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


class SyntaxEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lumena-kernel-test-")
        self.root = Path(self.temp.name)
        with patch.dict(os.environ, {"LUMENA_WORKSPACE": str(self.root), "LUMENA_BRIDGE_TOKEN": "test-only-token"}):
            spec = importlib.util.spec_from_file_location("kernel_test_bridge", Path(__file__).with_name("bridge.py"))
            self.bridge = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(self.bridge)

    def tearDown(self):
        self.temp.cleanup()

    def check_source(self, source):
        script = self.root / "sample.py"
        script.write_bytes(source)
        return self.bridge.execute_tool("python.syntax_check", {"script": "sample.py"})

    def test_valid_syntax_creates_no_cache_and_does_not_execute(self):
        result = self.check_source(b"raise RuntimeError('must not execute')\n")
        self.assertTrue(result["ok"])
        self.assertEqual(result["exitCode"], 0)
        self.assertFalse((self.root / "__pycache__").exists())

    def test_invalid_syntax_is_real_failure_without_cache(self):
        result = self.check_source(b"def broken(\n")
        self.assertFalse(result["ok"])
        self.assertNotEqual(result["exitCode"], 0)
        self.assertFalse((self.root / "__pycache__").exists())

    def test_encoding_cookie_is_respected(self):
        result = self.check_source(b"# coding: latin-1\nx = '\xe9'\n")
        self.assertTrue(result["ok"])


if __name__ == "__main__":
    unittest.main()
