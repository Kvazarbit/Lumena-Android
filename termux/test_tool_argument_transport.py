"""Regression for numeric argument transport between Companion and Termux Bridge."""
import importlib.util
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


class ToolArgumentTransportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lumena-tool-args-")
        self.env = patch.dict(os.environ, {
            "LUMENA_WORKSPACE": self.temp.name,
            "LUMENA_BRIDGE_TOKEN": "test-token",
        })
        self.env.start()
        spec = importlib.util.spec_from_file_location(
            "tool_args_bridge", Path(__file__).with_name("bridge.py")
        )
        self.bridge = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.bridge)
        self.script = Path(self.temp.name) / "sample.py"
        self.script.write_text("print('ok')\n", encoding="utf-8")
        self.addCleanup(self.env.stop)
        self.addCleanup(self.temp.cleanup)

    def test_exact_integer_transport_accepts_decimal_form(self):
        normalize = self.bridge._execution_timeout
        for value in (30, 30.0, "30", "30.0", "030.000", "3e1"):
            with self.subTest(value=value):
                self.assertEqual(30, normalize(value, 120))
        self.assertEqual(120, normalize(None, 120))
        self.assertEqual(1800, normalize("1800.0", 600, 1800))

    def test_reject_fractional_nan_boolean_negative_and_over_limit(self):
        normalize = self.bridge._execution_timeout
        for value in ("30.1", 30.5, "-1", "0", "NaN", "Infinity",
                      True, "", "99999999999999999999999", "x" * 33):
            with self.subTest(value=value):
                with self.assertRaisesRegex(ValueError, "timeout"):
                    normalize(value, 120)

    def test_python_run_reaches_runner_with_decimal_integral_seconds(self):
        expected = {"ok": True, "exitCode": 0, "stdout": "ran", "stderr": "", "error": None}
        with patch.object(self.bridge, "run_process", return_value=expected) as runner:
            result = self.bridge.execute_tool(
                "python.run", {"script": "sample.py", "timeout": "30.0"}
            )
        self.assertTrue(result["ok"])
        self.assertEqual(30, runner.call_args.args[2])
        self.assertEqual("python", runner.call_args.args[0][0])
        self.assertEqual(str(self.script), runner.call_args.args[0][1])

    def test_invalid_timeout_never_launches_code(self):
        with patch.object(self.bridge, "run_process") as runner:
            with self.assertRaisesRegex(ValueError, "timeout"):
                self.bridge.execute_tool(
                    "python.run", {"script": "sample.py", "timeout": "30.5"}
                )
        runner.assert_not_called()


if __name__ == "__main__":
    unittest.main()
