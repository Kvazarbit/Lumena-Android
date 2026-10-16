"""Python tools must not inherit the bridge's secrets through os.environ.

This is not isolation: on Termux the child still runs as the same uid, has
network access and can read ~/.lumena/bridge_token. A clean environment only
removes the secrets the bridge itself holds in its environment.
"""
import hashlib
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SECRETS = {
    "LUMENA_BRIDGE_TOKEN": "fixture-bridge-token-0123456789",
    "LUMENA_BRAVE_API_KEY": "fixture-brave-key-0123456789",
    # Name referenced by an MCP provider's auth_env.
    "EXA_API_KEY": "fixture-mcp-key-0123456789",
    "GITHUB_TOKEN": "ghp_fixture0123456789abcdefghij",
    "PYTHON_KEYRING_TOKEN": "fixture-python-named-secret-0123",
}


class PythonChildEnvTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lumena-child-env-")
        root = Path(self.temp.name)
        self.workspace = root / "workspace"
        self.workspace.mkdir()
        mcp_config = root / "mcp_search_providers.json"
        mcp_config.write_text(
            json.dumps({
                "providers": [{
                    "id": "exa",
                    "url": "https://mcp.exa.example/mcp",
                    "tool": "web_search_exa",
                    "auth_env": "EXA_API_KEY",
                }]
            }),
            encoding="utf-8",
        )
        self.env = patch.dict(
            os.environ,
            {
                "LUMENA_WORKSPACE": str(self.workspace),
                "LUMENA_READONLY_ROOTS": "",
                "LUMENA_READ_ROOTS_FILE": str(root / "read_roots.conf"),
                "LUMENA_MCP_SEARCH_CONFIG": str(mcp_config),
                "PYTHONIOENCODING": "utf-8",
                **SECRETS,
            },
        )
        self.env.start()
        spec = importlib.util.spec_from_file_location(
            "child_env_bridge",
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
        (self.workspace / "dump_env.py").write_text(
            "import json, os\n"
            "print(json.dumps(dict(os.environ), sort_keys=True))\n",
            encoding="utf-8",
        )

    def _python_run_env(self) -> dict:
        approved = hashlib.sha256((self.workspace / "dump_env.py").read_bytes()).hexdigest()
        result = self.b.execute_tool("python.run", {"script": "dump_env.py", "approved_sha256": approved})
        self.assertTrue(result["ok"], (result.get("error") or result.get("stderr") or "")[:800])
        return json.loads(result["stdout"])

    # Failure messages name the variable only: printing the child
    # environment would copy the host's own secrets into test logs.
    def test_python_run_child_does_not_inherit_bridge_secrets(self):
        env = self._python_run_env()
        dumped = json.dumps(env)
        for name, value in SECRETS.items():
            self.assertFalse(name in env, f"python.run child inherited {name}")
            self.assertFalse(value in dumped, f"python.run child can see the value of {name}")

    def test_python_run_child_keeps_minimal_working_environment(self):
        env = self._python_run_env()
        self.assertTrue("PATH" in env, "python.run child lost PATH")
        self.assertEqual("utf-8", env.get("PYTHONIOENCODING"))
        home = Path(env["HOME"]).resolve()
        workspace = self.workspace.resolve()
        self.assertTrue(workspace in home.parents, f"child HOME is outside the workspace: {home}")
        self.assertFalse("LUMENA_WORKSPACE" in env, "python.run child inherited LUMENA_WORKSPACE")
        self.assertFalse("LUMENA_MCP_SEARCH_CONFIG" in env, "python.run child inherited LUMENA_MCP_SEARCH_CONFIG")

    def test_syntax_check_and_tests_use_the_same_clean_environment(self):
        captured: dict[str, dict | None] = {}

        def fake_run_process(argv, cwd, timeout=0, *, request_id=None, env=None, timeout_cap=600):
            captured[" ".join(argv[:3])] = env
            return {"ok": True, "exitCode": 0, "stdout": "", "stderr": "", "error": None}

        with patch.object(self.b, "run_process", side_effect=fake_run_process):
            self.b.execute_tool("python.syntax_check", {"script": "dump_env.py"})
            self.b.execute_tool("python.tests", {"cwd": "."})

        self.assertEqual(2, len(captured))
        for argv, env in captured.items():
            self.assertIsNotNone(env, f"{argv} inherits the full bridge environment")
            for name in SECRETS:
                self.assertFalse(name in env, f"{argv} child inherited {name}")


if __name__ == "__main__":
    unittest.main()
