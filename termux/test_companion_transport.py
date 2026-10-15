"""Offline regressions for Companion numeric tool args and bounded HTML link mode."""

import importlib.util
import io
import json
import os
import socket
import tempfile
import unittest
from email.message import Message
from pathlib import Path
from unittest.mock import patch


class FakeResponse(io.BytesIO):
    status = 200

    def __init__(self, body):
        super().__init__(body.encode("utf-8"))
        self.headers = Message()
        self.headers["Content-Type"] = "text/html; charset=utf-8"


class CompanionTransportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lumena-companion-transport-")
        self.env = patch.dict(os.environ, {
            "LUMENA_WORKSPACE": self.temp.name,
            "LUMENA_BRIDGE_TOKEN": "offline-fixture-token"
        })
        self.env.start()
        spec = importlib.util.spec_from_file_location(
            "companion_transport_bridge", Path(__file__).with_name("bridge.py")
        )
        self.b = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.b)
        self.script = Path(self.temp.name) / "safe_fixture.py"
        self.script.write_text("print('fixture')\n", encoding="utf-8")
        self.addCleanup(self.env.stop)
        self.addCleanup(self.temp.cleanup)

    def test_bounded_numeric_args_preserve_integral_decimal(self):
        self.assertEqual(8, self.b._bounded_int("8.0", 5, 1, 8))
        self.assertEqual(5, self.b._bounded_int("8.5", 5, 1, 8))
        self.assertEqual(5, self.b._bounded_int("NaN", 5, 1, 8))
        self.assertEqual(5, self.b._bounded_int(True, 5, 1, 8))

    def test_file_read_line_numbers_accept_moshi_integral_decimal(self):
        sample = Path(self.temp.name) / "lines.txt"
        sample.write_text("first\\nsecond\\nthird\\n", encoding="utf-8")
        result = self.b.execute_tool("file.read", {
            "path": "lines.txt", "start_line": "2.0", "end_line": "2.0"
        })
        self.assertTrue(result["ok"])
        self.assertEqual("second\\n", result["stdout"])
        with self.assertRaisesRegex(ValueError, "must be integers"):
            self.b.execute_tool("file.read", {
                "path": "lines.txt", "start_line": "2.5"
            })

    def test_python_run_accepts_json_integer_transport_30_dot_zero(self):
        with patch.object(self.b, "run_process", return_value={"ok": True}) as runner:
            result = self.b.execute_tool("python.run", {
                "script": "safe_fixture.py",
                "timeout": "30.0"
            })
        self.assertTrue(result["ok"])
        self.assertEqual(30, runner.call_args.args[2])
        self.assertEqual(["python", str(self.script)], runner.call_args.args[0])

    def test_python_syntax_and_tests_accept_integral_decimal(self):
        with patch.object(self.b, "run_process", return_value={"ok": True}) as runner:
            self.b.execute_tool("python.syntax_check", {
                "script": "safe_fixture.py", "timeout": 60.0
            })
            self.assertEqual(60, runner.call_args.args[2])
            self.b.execute_tool("python.tests", {
                "cwd": ".", "timeout": "300.0"
            })
            self.assertEqual(300, runner.call_args.args[2])

    def test_invalid_execution_timeout_rejected_before_process(self):
        for value in ("30.5", "NaN", "Infinity", "text", "-3", "0",
                      "999999", True):
            with self.subTest(value=value):
                with patch.object(self.b, "run_process") as runner:
                    with self.assertRaisesRegex(ValueError, "timeout"):
                        self.b.execute_tool("python.run", {
                            "script": "safe_fixture.py", "timeout": value
                        })
                    runner.assert_not_called()

    def test_missing_timeout_uses_original_default(self):
        with patch.object(self.b, "run_process", return_value={"ok": True}) as runner:
            self.b.execute_tool("python.run", {"script": "safe_fixture.py"})
            self.assertEqual(self.b.DEFAULT_TIMEOUT, runner.call_args.args[2])

    def _http_get(self, html, args):
        with patch.object(
            self.b.socket, "getaddrinfo",
            return_value=[(socket.AF_INET, socket.SOCK_STREAM, 6,
                           "", ("93.184.216.34", 443))]
        ), patch.object(
            self.b.PUBLIC_HTTPS_OPENER, "open",
            return_value=FakeResponse(html)
        ):
            return self.b.http_get({
                "url": "https://example.org/pl/?q=demo", **args
            })

    def test_html_links_mode_returns_relevant_urls_without_full_html(self):
        html = """<html><body><nav><a href="/pl/categories/">Navigation</a></nav>
            <a title="First series" href="/pl/galleries/model-111/">
              <img alt="Art series" src="/thumb.jpg"></a>
            <a href="/pl/galleries/model-222/?utm_source=ref#image">Second set</a>
            <a href="/pl/galleries/model-222/">Duplicate</a>
            <a href="javascript:alert(1)">unsafe</a>
            <a href="http://example.org/other">insecure</a>
            </body></html>"""
        result = self._http_get(html, {
            "mode": "links", "path_contains": "/galleries/", "limit": "1"
        })
        self.assertTrue(result["ok"])
        payload = json.loads(result["stdout"])
        self.assertEqual(200, payload["status"])
        self.assertNotIn("body", payload)
        self.assertEqual(2, payload["matching_links"])
        self.assertTrue(payload["truncated"])
        self.assertEqual(
            "https://example.org/pl/galleries/model-111/",
            payload["links"][0]["url"]
        )
        self.assertIn("First series", payload["links"][0]["title"])

    def test_html_links_mode_rejects_human_verification(self):
        result = None
        with self.assertRaisesRegex(ValueError, "human verification"):
            result = self._http_get("<div class='cf-chl-verify'>test</div>",
                                    {"mode": "links"})
        self.assertIsNone(result)

    def test_http_get_default_body_unchanged(self):
        html = "<html><body>Plain text fixture</body></html>"
        result = self._http_get(html, {})
        self.assertTrue(result["ok"])
        payload = json.loads(result["stdout"])
        self.assertEqual(html, payload["body"])
        self.assertNotIn("links", payload)

    def test_link_mode_only_html_and_known_mode(self):
        with self.assertRaisesRegex(ValueError, "mode must be"):
            self._http_get("<html/>", {"mode": "execute"})
        with self.assertRaisesRegex(ValueError, "path_contains"):
            self._http_get("<html/>", {"mode": "links", "path_contains": "x" * 121})


if __name__ == "__main__":
    unittest.main()
