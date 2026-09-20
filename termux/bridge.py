#!/data/data/com.termux/files/usr/bin/python
"""
Lumena Termux Bridge v0.8

Local-only bridge between Lumena Companion and Termux.
It binds to 127.0.0.1 only, uses a bearer token, constrains file access
to one workspace, exposes an allow-listed tool surface, and supports
request-scoped cancellation for long-running subprocess tools.
"""
from __future__ import annotations

import json
import os
from datetime import datetime
import re
import secrets
import shlex
import shutil
import signal
import subprocess
import threading
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
REQUEST_ID_RE = re.compile(r"^[A-Za-z0-9._:-]{1,220}$")

ACTIVE_PROCESSES: dict[str, subprocess.Popen[str]] = {}
ACTIVE_LOCK = threading.Lock()


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


def _register_process(request_id: str | None, process: subprocess.Popen[str]) -> None:
    if not request_id:
        return
    with ACTIVE_LOCK:
        ACTIVE_PROCESSES[request_id] = process


def _unregister_process(request_id: str | None, process: subprocess.Popen[str]) -> None:
    if not request_id:
        return
    with ACTIVE_LOCK:
        if ACTIVE_PROCESSES.get(request_id) is process:
            ACTIVE_PROCESSES.pop(request_id, None)


def _terminate_process(process: subprocess.Popen[str], *, force: bool = False) -> None:
    if process.poll() is not None:
        return
    sig = signal.SIGKILL if force else signal.SIGTERM
    try:
        os.killpg(process.pid, sig)
    except (ProcessLookupError, PermissionError, OSError):
        try:
            process.kill() if force else process.terminate()
        except ProcessLookupError:
            pass


def cancel_request(request_id: str) -> dict[str, Any]:
    if not REQUEST_ID_RE.fullmatch(request_id):
        raise ValueError("Invalid requestId")
    with ACTIVE_LOCK:
        process = ACTIVE_PROCESSES.get(request_id)
    if process is None or process.poll() is not None:
        return {
            "ok": True,
            "exitCode": 0,
            "stdout": f"requestId={request_id}\nactive=false\n",
            "stderr": "",
            "error": None,
        }

    _terminate_process(process, force=False)

    def force_kill() -> None:
        if process.poll() is None:
            _terminate_process(process, force=True)

    threading.Timer(2.0, force_kill).start()
    return {
        "ok": True,
        "exitCode": 0,
        "stdout": f"requestId={request_id}\ncancel_requested=true\n",
        "stderr": "",
        "error": None,
    }


def run_process(
    argv: list[str],
    cwd: Path,
    timeout: int = DEFAULT_TIMEOUT,
    *,
    request_id: str | None = None,
    env: dict[str, str] | None = None,
    timeout_cap: int = 600,
) -> dict[str, Any]:
    process = subprocess.Popen(
        argv,
        cwd=str(cwd),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        stdin=subprocess.DEVNULL,
        text=True,
        shell=False,
        start_new_session=True,
    )
    _register_process(request_id, process)
    effective_timeout = max(1, min(timeout, timeout_cap))
    try:
        stdout, stderr = process.communicate(timeout=effective_timeout)
    except subprocess.TimeoutExpired:
        _terminate_process(process, force=False)
        try:
            stdout, stderr = process.communicate(timeout=2)
        except subprocess.TimeoutExpired:
            _terminate_process(process, force=True)
            stdout, stderr = process.communicate()
        raise subprocess.TimeoutExpired(argv, effective_timeout, output=stdout, stderr=stderr)
    finally:
        _unregister_process(request_id, process)

    cancelled = process.returncode in {-signal.SIGTERM, -signal.SIGKILL}
    return {
        "ok": process.returncode == 0,
        "exitCode": process.returncode,
        "stdout": clamp(stdout or ""),
        "stderr": clamp(stderr or ""),
        "error": "Cancelled" if cancelled else None,
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


def execute_tool(tool: str, args: dict[str, Any], request_id: str | None = None) -> dict[str, Any]:
    if tool == "health":
        return {
            "ok": True,
            "exitCode": 0,
            "stdout": f"Lumena bridge OK\nworkspace={WORKSPACE}\nversion=0.8\n",
            "stderr": "",
            "error": None,
        }

    if tool == "system.time":
        now = datetime.now().astimezone()
        return {
            "ok": True,
            "exitCode": 0,
            "stdout": (
                f"local_time={now.isoformat(timespec='seconds')}\n"
                f"date={now.date().isoformat()}\n"
                f"time={now.strftime('%H:%M:%S')}\n"
                f"timezone={now.tzname() or ''}\n"
                f"utc_offset={now.strftime('%z')}\n"
            ),
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
            result = run_process(["git", "init"], path, request_id=request_id)
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
        return run_process(
            argv,
            cwd,
            int(args.get("timeout", DEFAULT_TIMEOUT)),
            request_id=request_id,
        )

    if tool in {"python.run", "python.syntax_check"}:
        script = safe_path(str(args.get("script", "")), must_exist=True)
        if not script.is_file() or script.suffix.lower() != ".py":
            raise ValueError(f"{tool} requires an existing .py file inside the workspace")
        cwd_arg = str(args.get("cwd", "")).strip()
        cwd = safe_path(cwd_arg, must_exist=True) if cwd_arg else script.parent
        if tool == "python.syntax_check":
            return run_process(
                ["python", "-m", "py_compile", str(script)],
                cwd,
                int(args.get("timeout", 60)),
                request_id=request_id,
            )
        argv_text = str(args.get("argv", "")).strip()
        extra = shlex.split(argv_text) if argv_text else []
        return run_process(
            ["python", str(script), *extra],
            cwd,
            int(args.get("timeout", DEFAULT_TIMEOUT)),
            request_id=request_id,
        )

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
            request_id=request_id,
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
        return run_process(
            [binary, "pull", model],
            HOME,
            max(60, min(int(args.get("timeout", 600)), 1800)),
            request_id=request_id,
            env=env,
            timeout_cap=1800,
        )

    raise ValueError(f"Unknown or disabled tool: {tool}")


class Handler(BaseHTTPRequestHandler):
    server_version = "LumenaBridge/0.8"

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"[bridge] {self.address_string()} - {fmt % args}")

    def _json(self, status: int, payload: dict[str, Any]) -> None:
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _authorized(self) -> bool:
        return self.headers.get("Authorization", "") == f"Bearer {TOKEN}"

    def _read_payload(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_BODY:
            raise ValueError("Invalid request size")
        payload = json.loads(self.rfile.read(length).decode("utf-8"))
        if not isinstance(payload, dict):
            raise ValueError("JSON body must be an object")
        return payload

    def do_GET(self) -> None:
        if self.path == "/":
            self._json(200, {"ok": True, "service": "lumena-termux-bridge", "version": "0.8"})
            return
        self._json(404, {"ok": False, "error": "Not found"})

    def do_POST(self) -> None:
        if self.path not in {"/tool", "/cancel"}:
            self._json(404, {"ok": False, "error": "Not found"})
            return
        if not self._authorized():
            self._json(401, {"ok": False, "error": "Unauthorized"})
            return

        try:
            payload = self._read_payload()
            if self.path == "/cancel":
                request_id = str(payload.get("requestId", "")).strip()
                self._json(200, cancel_request(request_id))
                return

            tool = str(payload.get("tool", "")).strip()
            args = payload.get("args") or {}
            if not isinstance(args, dict):
                raise ValueError("args must be an object")
            request_id_raw = payload.get("requestId")
            request_id = str(request_id_raw).strip() if request_id_raw is not None else None
            if request_id and not REQUEST_ID_RE.fullmatch(request_id):
                raise ValueError("Invalid requestId")

            result = execute_tool(tool, args, request_id=request_id)
            result["tool"] = tool
            if request_id:
                result["requestId"] = request_id
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
    print("Lumena Termux Bridge v0.8")
    print(f"Listening: http://{HOST}:{PORT}")
    print(f"Workspace: {WORKSPACE}")
    print(f"Token: {TOKEN}")
    print("Only 127.0.0.1 is bound; the bridge is not exposed to Wi-Fi.")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
