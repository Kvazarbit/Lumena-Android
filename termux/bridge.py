#!/data/data/com.termux/files/usr/bin/python
"""
Lumena Termux Bridge v0.3

A tiny local-only HTTP bridge between Lumena Android and Termux.
It binds to 127.0.0.1 only, uses a bearer token, constrains file access
to one workspace, and exposes a small allow-listed tool surface.
"""
from __future__ import annotations

import json
import os
import secrets
import shlex
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

HOST = "127.0.0.1"
PORT = int(os.environ.get("LUMENA_BRIDGE_PORT", "8765"))
HOME = Path.home()
STATE_DIR = HOME / ".lumena"
TOKEN_FILE = STATE_DIR / "bridge_token"
WORKSPACE = Path(os.environ.get("LUMENA_WORKSPACE", str(HOME / "lumena-workspace"))).expanduser().resolve()
MAX_BODY = 64 * 1024
MAX_OUTPUT = 128 * 1024
DEFAULT_TIMEOUT = 120


def ensure_token() -> str:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    if TOKEN_FILE.exists():
        token = TOKEN_FILE.read_text(encoding="utf-8").strip()
        if token:
            return token
    token = secrets.token_urlsafe(32)
    TOKEN_FILE.write_text(token + "\n", encoding="utf-8")
    try:
        TOKEN_FILE.chmod(0o600)
    except OSError:
        pass
    return token


TOKEN = os.environ.get("LUMENA_BRIDGE_TOKEN", "").strip() or ensure_token()
WORKSPACE.mkdir(parents=True, exist_ok=True)


def safe_path(relative: str, *, must_exist: bool = False) -> Path:
    if not relative:
        candidate = WORKSPACE
    else:
        supplied = Path(relative).expanduser()
        candidate = supplied.resolve() if supplied.is_absolute() else (WORKSPACE / supplied).resolve()
    if candidate != WORKSPACE and WORKSPACE not in candidate.parents:
        raise ValueError("Path escapes LUMENA_WORKSPACE")
    if must_exist and not candidate.exists():
        raise FileNotFoundError(str(candidate))
    return candidate


def clamp(text: str) -> str:
    if len(text) <= MAX_OUTPUT:
        return text
    return text[:MAX_OUTPUT] + "\n...[output truncated]..."


def run_process(argv: list[str], cwd: Path, timeout: int = DEFAULT_TIMEOUT) -> dict[str, Any]:
    completed = subprocess.run(
        argv,
        cwd=str(cwd),
        capture_output=True,
        text=True,
        timeout=max(1, min(timeout, 600)),
        shell=False,
        check=False,
    )
    return {
        "ok": completed.returncode == 0,
        "exitCode": completed.returncode,
        "stdout": clamp(completed.stdout or ""),
        "stderr": clamp(completed.stderr or ""),
        "error": None,
    }


def execute_tool(tool: str, args: dict[str, Any]) -> dict[str, Any]:
    if tool == "health":
        return {
            "ok": True,
            "exitCode": 0,
            "stdout": f"Lumena bridge OK\nworkspace={WORKSPACE}\n",
            "stderr": "",
            "error": None,
        }

    if tool == "file.read":
        path = safe_path(str(args.get("path", "")), must_exist=True)
        if not path.is_file():
            raise ValueError("Requested path is not a file")
        data = path.read_text(encoding="utf-8", errors="replace")
        return {"ok": True, "exitCode": 0, "stdout": clamp(data), "stderr": "", "error": None}

    if tool in {"git.status", "git.diff", "git.log"}:
        cwd = safe_path(str(args.get("cwd", "")), must_exist=True)
        if not cwd.is_dir():
            raise ValueError("cwd is not a directory")
        if tool == "git.status":
            argv = ["git", "status", "--short", "--branch"]
        elif tool == "git.diff":
            argv = ["git", "diff", "--"]
        else:
            argv = ["git", "log", "-n", "12", "--oneline", "--decorate"]
        return run_process(argv, cwd, int(args.get("timeout", DEFAULT_TIMEOUT)))

    if tool == "python.run":
        script = safe_path(str(args.get("script", "")), must_exist=True)
        if not script.is_file() or script.suffix.lower() != ".py":
            raise ValueError("python.run requires an existing .py file inside the workspace")
        cwd_arg = str(args.get("cwd", "")).strip()
        cwd = safe_path(cwd_arg, must_exist=True) if cwd_arg else script.parent
        argv_text = str(args.get("argv", "")).strip()
        extra = shlex.split(argv_text) if argv_text else []
        return run_process(
            ["python", str(script), *extra],
            cwd,
            int(args.get("timeout", DEFAULT_TIMEOUT)),
        )

    raise ValueError(f"Unknown or disabled tool: {tool}")


class Handler(BaseHTTPRequestHandler):
    server_version = "LumenaBridge/0.3"

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"[bridge] {self.address_string()} - {fmt % args}")

    def _json(self, status: int, payload: dict[str, Any]) -> None:
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def _authorized(self) -> bool:
        return self.headers.get("Authorization", "") == f"Bearer {TOKEN}"

    def do_GET(self) -> None:
        if self.path == "/":
            self._json(200, {"ok": True, "service": "lumena-termux-bridge", "version": "0.3"})
            return
        self._json(404, {"ok": False, "error": "Not found"})

    def do_POST(self) -> None:
        if self.path != "/tool":
            self._json(404, {"ok": False, "error": "Not found"})
            return
        if not self._authorized():
            self._json(401, {"ok": False, "error": "Unauthorized"})
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAX_BODY:
                raise ValueError("Invalid request size")
            raw = self.rfile.read(length)
            payload = json.loads(raw.decode("utf-8"))
            tool = str(payload.get("tool", "")).strip()
            args = payload.get("args") or {}
            if not isinstance(args, dict):
                raise ValueError("args must be an object")
            result = execute_tool(tool, args)
            result["tool"] = tool
            self._json(200 if result.get("ok") else 422, result)
        except subprocess.TimeoutExpired as exc:
            self._json(408, {
                "ok": False,
                "exitCode": None,
                "stdout": clamp(exc.stdout or "") if isinstance(exc.stdout, str) else "",
                "stderr": clamp(exc.stderr or "") if isinstance(exc.stderr, str) else "",
                "error": "Command timed out",
            })
        except Exception as exc:
            self._json(400, {
                "ok": False,
                "exitCode": None,
                "stdout": "",
                "stderr": "",
                "error": f"{type(exc).__name__}: {exc}",
            })


def main() -> None:
    print("Lumena Termux Bridge v0.3")
    print(f"Listening: http://{HOST}:{PORT}")
    print(f"Workspace: {WORKSPACE}")
    print(f"Token: {TOKEN}")
    print("Only 127.0.0.1 is bound; the bridge is not exposed to Wi-Fi.")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
