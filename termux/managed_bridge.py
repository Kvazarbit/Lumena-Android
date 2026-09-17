#!/usr/bin/env python3
"""Lumena bridge 0.8.1: original allow-listed tools plus authenticated job control.
Run from source with python managed_bridge.py; install_bridge.sh installs this as bridge.py.
"""
import json
import os
import secrets
import uuid
from http.server import ThreadingHTTPServer
try:
    import bridge_base as legacy
except ModuleNotFoundError as exc:
    if exc.name != "bridge_base": raise
    import bridge as legacy
from bridge_jobs import JobRegistry, failure

JOBS = JobRegistry()
legacy.run_process = JOBS.run_process

def dispatch(tool, args):
    if tool == "health":
        return {"ok": True, "tool": tool, "exitCode": 0, "stderr": "", "error": None,
            "stdout": f"Lumena bridge OK\nworkspace={legacy.WORKSPACE}\nversion=0.8.1\njob_control=1\n"}
    if tool == "ollama.pull":
        model = str(args.get("model", "")).strip()
        if not legacy.MODEL_RE.fullmatch(model): raise ValueError("Invalid Ollama model name")
        env = os.environ.copy(); env["OLLAMA_HOST"] = legacy.OLLAMA_HOST
        return JOBS.run_process([legacy.ollama_binary(), "pull", model], legacy.HOME, int(args.get("timeout", 600)), env=env)
    return legacy.execute_tool(tool, args)

class Handler(legacy.Handler):
    server_version = "LumenaBridge/0.8.1"
    def _authorized(self):
        return secrets.compare_digest(self.headers.get("Authorization", ""), f"Bearer {legacy.TOKEN}")
    def _json(self, code, payload):
        try: super()._json(code, payload)
        except (BrokenPipeError, ConnectionResetError): pass
    def do_GET(self):
        if self.path == "/":
            self._json(200, {"ok": True, "service": "lumena-termux-bridge", "version": "0.8.1", "job_control": True})
        else: self._json(404, failure("Not found"))
    def do_POST(self):
        if not self._authorized():
            self._json(401, failure("Unauthorized")); return
        if self.path not in {"/tool", "/jobs/status", "/jobs/cancel"}:
            self._json(404, failure("Not found")); return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= legacy.MAX_BODY: raise ValueError("Invalid request size")
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(payload, dict): raise ValueError("Expected JSON object")
            if self.path.startswith("/jobs/"):
                request_id = payload.get("requestId", "")
                result = JOBS.cancel(request_id) if self.path.endswith("cancel") else JOBS.status(request_id)
                self._json(200, result); return
            request_id = self.headers.get("X-Lumena-Request-Id") or str(uuid.uuid4())
            tool = str(payload.get("tool", "")).strip()
            args = payload.get("args") or {}
            if not isinstance(args, dict): raise ValueError("args must be an object")
            result = JOBS.execute(request_id, {"tool": tool, "args": args}, lambda: dispatch(tool, args))
            result["tool"] = tool
            self._json(200 if result.get("ok") else 422, result)
        except Exception as exc:
            self._json(400, failure(f"{type(exc).__name__}: {exc}"))

if __name__ == "__main__":
    server = ThreadingHTTPServer((legacy.HOST, legacy.PORT), Handler)
    print("Lumena Termux Bridge v0.8.1", flush=True)
    print(f"Listening: http://{legacy.HOST}:{legacy.PORT}\nWorkspace: {legacy.WORKSPACE}\nToken: {legacy.TOKEN}", flush=True)
    print("Loopback only. Job status/cancel require the same bearer token. Python has Termux permissions, not an OS sandbox.", flush=True)
    server.serve_forever()
