import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


class LayaBridgeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(
            prefix="lumena-laya-bridge-"
        )
        root = Path(self.temp.name)
        workspace = root / "workspace"
        workspace.mkdir()
        self.missing_bin = root / "missing-laya"
        self.model_dir = root / "model"

        self.env = patch.dict(
            os.environ,
            {
                "LUMENA_WORKSPACE": str(workspace),
                "LUMENA_BRIDGE_TOKEN": "fixture-token",
                "LUMENA_READONLY_ROOTS": "",
                "LUMENA_LAYA_BIN": str(self.missing_bin),
                "LUMENA_LAYA_MODEL_DIR": str(self.model_dir),
            },
        )
        self.env.start()

        spec = importlib.util.spec_from_file_location(
            "laya_bridge_fixture",
            Path(__file__).with_name("bridge.py"),
        )
        self.b = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.b)

        self.addCleanup(self.env.stop)
        self.addCleanup(self.temp.cleanup)

    def request(self):
        return json.dumps(
            {
                "state": {
                    "failure_class": "STATE_DRIFT",
                    "retryable": "false",
                    "constitutional_anchor": "TRY_ALTERNATIVE",
                },
                "questions": {
                    "recovery": {
                        "type": "choice",
                        "instructions": "Choose recovery.",
                        "criteria": {
                            "TRY_ALTERNATIVE": "Use another verified path.",
                            "STOP": "Stop safely.",
                        },
                    }
                },
            }
        )

    def test_laya_request_accepts_bounded_choice(self):
        parsed = self.b._validate_laya_request(self.request())
        self.assertIn("state", parsed)
        self.assertEqual(
            set(
                parsed["questions"]["recovery"]["criteria"]
            ),
            {"TRY_ALTERNATIVE", "STOP"},
        )

    def test_laya_request_rejects_non_choice_question(self):
        payload = json.loads(self.request())
        payload["questions"]["recovery"]["type"] = "score"

        with self.assertRaises(ValueError):
            self.b._validate_laya_request(
                json.dumps(payload)
            )

    def test_laya_start_fails_closed_when_runtime_missing(self):
        result = self.b.execute_tool("laya.start", {})
        self.assertFalse(result["ok"])
        self.assertEqual(
            "LAYA_NOT_INSTALLED",
            result["errorCode"],
        )

    def test_laya_predict_proxies_typed_decision_only(self):
        original_status = self.b._laya_status_payload
        original_urlopen = self.b.urllib.request.urlopen

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, tb):
                return False

            def read(self, _limit):
                return json.dumps(
                    {
                        "model": "laya-rl-agent",
                        "answers": {
                            "recovery": {
                                "type": "choice",
                                "choice": "TRY_ALTERNATIVE",
                                "probabilities": {
                                    "TRY_ALTERNATIVE": 0.91,
                                    "STOP": 0.09,
                                },
                                "confidence": 0.56,
                            }
                        },
                    }
                ).encode("utf-8")

        try:
            self.b._laya_status_payload = lambda: {
                "running": True
            }
            self.b.urllib.request.urlopen = (
                lambda *args, **kwargs: Response()
            )
            result = self.b.execute_tool(
                "laya.predict",
                {
                    "request": self.request(),
                    "timeout": "5",
                },
            )
        finally:
            self.b._laya_status_payload = original_status
            self.b.urllib.request.urlopen = original_urlopen

        self.assertTrue(result["ok"])
        payload = json.loads(result["stdout"])
        self.assertEqual(
            "TRY_ALTERNATIVE",
            payload["answers"]["recovery"]["choice"],
        )

    def test_laya_is_not_exposed_to_inspect_batch(self):
        with self.assertRaises(ValueError):
            self.b.inspect_batch(
                {
                    "requests": [
                        {
                            "tool": "laya.predict",
                            "args": {
                                "request": self.request()
                            },
                        }
                    ]
                }
            )


if __name__ == "__main__":
    unittest.main()
