#!/data/data/com.termux/files/usr/bin/python
"""
Lumena Termux Bridge v0.7

Local-only bridge between Lumena Companion and Termux.
It binds to 127.0.0.1 only, uses a bearer token, constrains file access
to one workspace, and exposes an allow-listed tool surface.
"""
from __future__ import annotations

import json
import os
import re
import secrets
import shlex
import shutil
import subprocess
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

HOST = "127.0.0.1"
PORT = int(os.environ.get("LUMENA_BRIDGE_PORT", "8765"))
OLLAMA_HOST = "127.0.0.1:11434"
OLLAMA_API = f"http://{OLLAMA_HOST}"
HOME = Path.home()
STATE_DIR = HOME / ".lumena"
TOKEN_FILE = STATE_DIR / "bridge_token"
OLLAMA_LOG = STATE_DIR / "ollama.log"
WORKSPACE = Path(os.environ.get("LUMENA_WORKSPACE", str(HOME / "lumena-workspace"))).expanduser().resolve()
BACKUP_ROOT = WORKSPACE / ".lumena-backups"
MAX_BODY = 512 * 1024
MAX_OUTPUT = 128 * 1024
DEFAULT_TIMEOUT = 120
MODEL_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")
PROJECT_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,80}$")


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
STATE_DIR.mkdir(parents=True, exist_ok=True)
BACKUP_ROOT.mkdir(parents=True, exist_ok=True)


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


def backup_file(path: Path) -> Path:
    rel = path.relative_to(WORKSPACE)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    ns = time.time_ns() % 1_000_000_000
    safe_name = "__".join(rel.parts)
    backup = BACKUP_ROOT / f"{stamp}-{ns:09d}-{safe_name}"
    shutil.copy2(path, backup)
    return backup


def ollama_binary() -> str:
    path = shutil.which("ollama")
    if not path:
        raise FileNotFoundError("ollama executable not found in Termux PATH")
    return path


def ollama_status() -> dict[str, Any]:
    installed = shutil.which("ollama") is not None
    try:
        with urllib.request.urlopen(f"{OLLAMA_API}/api/tags", timeout=2) as response:
            payload = json.loads(response.read().decode("utf-8"))
        models = [m.get("name", "") for m in payload.get("models", []) if m.get("name")]
        return {
            "ok": True,
            "exitCode": 0,
            "stdout": "installed=%s\nrunning=true\nmodels=%s\n" % (
                str(installed).lower(),
                ", ".join(models) if models else "(none)",
            ),
            "stderr": "",
            "error": None,
        }
    except Exception:
        return {
            "ok": True,
            "exitCode": 0,
            "stdout": "installed=%s\nrunning=false\n" % str(installed).lower(),
            "stderr": "",
            "error": None,
        }


def ollama_start() -> dict[str, Any]:
    binary = ollama_binary()
    current = ollama_status()
    if "running=true" in current.get("stdout", ""):
        return current
    env = os.environ.copy()
    env["OLLAMA_HOST"] = OLLAMA_HOST
    log = open(OLLAMA_LOG, "ab", buffering=0)
    subprocess.Popen(
        [binary, "serve"],
        cwd=str(HOME),
        env=env,
        stdout=log,
        stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL,
        start_new_session=True,
        close_fds=True,
    )
    return {
        "ok": True,
        "exitCode": 0,
        "stdout": f"Ollama start requested on {OLLAMA_HOST}\nlog={OLLAMA_LOG}\n",
        "stderr": "",
        "error": None,
    }


def workspace_listing() -> str:
    lines: list[str] = []
    for path in sorted(WORKSPACE.rglob("*")):
        try:
            rel = path.relative_to(WORKSPACE)
        except ValueError:
            continue
        if rel.parts and rel.parts[0] == BACKUP_ROOT.name:
            continue
        if len(rel.parts) > 3:
            continue
        suffix = "/" if path.is_dir() else ""
        lines.append(str(rel) + suffix)
        if len(lines) >= 400:
            lines.append("...[listing truncated]...")
            break
    return "\n".join(lines) if lines else "(workspace empty)"


def execute_tool(tool: str, args: dict[str, Any]) -> dict[str, Any]:
    if tool == "health":
        return {
            "ok": True,
            "exitCode": 0,
            "stdout": f"Lumena bridge OK\nworkspace={WORKSPACE}\nversion=0.7\n",
            "stderr": "",
            "error": None,
        }

    if tool == "workspace.list":
        return {"ok": True, "exitCode": 0, "stdout": workspace_listing(), "stderr": "", "error": None}

    if tool == "file.read":
        path = safe_path(str(args.get("path", "")), must_exist=True)
        if not path.is_file():
            raise ValueError("Requested path is not a file")
        data = path.read_text(encoding="utf-8", errors="replace")
        return {"ok": True, "exitCode": 0, "stdout": clamp(data), "stderr": "", "error": None}

    if tool == "project.create":
        name = str(args.get("name", "")).strip()
        if not PROJECT_RE.fullmatch(name):
            raise ValueError("Invalid project name")
        path = safe_path(name)
        path.mkdir(parents=True, exist_ok=False)
        if str(args.get("git", "true")).lower() in {"1", "true", "yes"}:
            result = run_process(["git", "init"], path)
            if not result["ok"]:
                return result
        return {"ok": True, "exitCode": 0, "stdout": f"created={name}\n", "stderr": "", "error": None}

    if tool == "dir.create":
        path = safe_path(str(args.get("path", "")).strip())
        path.mkdir(parents=True, exist_ok=True)
        return {"ok": True, "exitCode": 0, "stdout": f"created={path.relative_to(WORKSPACE)}\n", "stderr": "", "error": None}

    if tool == "file.write":
        path = safe_path(str(args.get("path", "")).strip())
        content = str(args.get("content", ""))
        if len(content.encode("utf-8")) > 256 * 1024:
            raise ValueError("file.write content exceeds 256 KiB")
        overwrite = str(args.get("overwrite", "true")).lower() in {"1", "true", "yes"}
        if path.exists() and not overwrite:
            raise FileExistsError(str(path))
        backup = backup_file(path) if path.exists() and path.is_file() else None
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        backup_line = f"backup={backup.relative_to(WORKSPACE)}\n" if backup else ""
        return {
            "ok": True,
            "exitCode": 0,
            "stdout": f"wrote={path.relative_to(WORKSPACE)}\n{backup_line}bytes={len(content.encode('utf-8'))}\n",
            "stderr": "",
            "error": None,
        }

    if tool == "file.patch":
        path = safe_path(str(args.get("path", "")).strip(), must_exist=True)
        if not path.is_file():
            raise ValueError("file.patch requires a file")
        old = str(args.get("old", ""))
        new = str(args.get("new", ""))
        if not old:
            raise ValueError("file.patch requires non-empty old text")
        text = path.read_text(encoding="utf-8", errors="strict")
        count = text.count(old)
        if count != 1:
            raise ValueError(f"file.patch expected exactly one match, found {count}")
        updated = text.replace(old, new, 1)
        if len(updated.encode("utf-8")) > 2 * 1024 * 1024:
            raise ValueError("patched text file exceeds 2 MiB safety limit")
        backup = backup_file(path)
        path.write_text(updated, encoding="utf-8")
        return {
            "ok": True,
            "exitCode": 0,
            "stdout": (
                f"patched={path.relative_to(WORKSPACE)}\n"
                f"backup={backup.relative_to(WORKSPACE)}\n"
                "matches=1\n"
            ),
            "stderr": "",
            "error": None,
        }

    if tool in {"git.status", "git.diff", "git.log", "git.add", "git.commit"}:
        cwd = safe_path(str(args.get("cwd", "")), must_exist=True)
        if not cwd.is_dir():
            raise ValueError("cwd is not a directory")
        if tool == "git.status":
            argv = ["git", "status", "--short", "--branch"]
        elif tool == "git.diff":
            argv = ["git", "diff", "--"]
        elif tool == "git.log":
            argv = ["git", "log", "-n", "12", "--oneline", "--decorate"]
        elif tool == "git.add":
            raw_paths = str(args.get("paths", ".")).strip() or "."
            parts = shlex.split(raw_paths)
            argv = ["git", "add", "--", *parts]
        else:
            message = str(args.get("message", "")).strip()
            if not message or len(message) > 200:
                raise ValueError("git.commit requires a message up to 200 chars")
            argv = ["git", "commit", "-m", message]
        return run_process(argv, cwd, int(args.get("timeout", DEFAULT_TIMEOUT)))

    if tool in {"python.run", "python.syntax_check"}:
        script = safe_path(str(args.get("script", "")), must_exist=True)
        if not script.is_file() or script.suffix.lower() != ".py":
            raise ValueError(f"{tool} requires an existing .py file inside the workspace")
        cwd_arg = str(args.get("cwd", "")).strip()
        cwd = safe_path(cwd_arg, must_exist=True) if cwd_arg else script.parent
        if tool == "python.syntax_check":
            return run_process(["python", "-m", "py_compile", str(script)], cwd, int(args.get("timeout", 60)))
        argv_text = str(args.get("argv", "")).strip()
        extra = shlex.split(argv_text) if argv_text else []
        return run_process(["python", str(script), *extra], cwd, int(args.get("timeout", DEFAULT_TIMEOUT)))

    if tool == "python.tests":
        cwd = safe_path(str(args.get("cwd", "")).strip(), must_exist=True)
        if not cwd.is_dir():
            raise ValueError("python.tests cwd is not a directory")
        argv_text = str(args.get("argv", "-q")).strip()
        extra = shlex.split(argv_text) if argv_text else ["-q"]
        return run_process(
            ["python", "-m", "pytest", *extra],
            cwd,
            int(args.get("timeout", 300)),
        )

    if tool == "ollama.status":
        return ollama_status()

    if tool == "ollama.start":
        return ollama_start()

    if tool == "ollama.pull":
        model = str(args.get("model", "")).strip()
        if not MODEL_RE.fullmatch(model):
            raise ValueError("Invalid Ollama model name")
        binary = ollama_binary()
        env = os.environ.copy()
        env["OLLAMA_HOST"] = OLLAMA_HOST
        completed = subprocess.run(
            [binary, "pull", model],
            cwd=str(HOME),
            env=env,
            capture_output=True,
            text=True,
            timeout=max(60, min(int(args.get("timeout", 600)), 1800)),
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

    raise ValueError(f"Unknown or disabled tool: {tool}")


class Handler(BaseHTTPRequestHandler):
    server_version = "LumenaBridge/0.7"

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
            self._json(200, {"ok": True, "service": "lumena-termux-bridge", "version": "0.7"})
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
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
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
    print("Lumena Termux Bridge v0.7")
    print(f"Listening: http://{HOST}:{PORT}")
    print(f"Workspace: {WORKSPACE}")
    print(f"Token: {TOKEN}")
    print("Only 127.0.0.1 is bound; the bridge is not exposed to Wi-Fi.")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
