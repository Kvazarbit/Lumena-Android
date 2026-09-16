#!/data/data/com.termux/files/usr/bin/python
"""
Lumena Termux Bridge v0.5

Local-only bridge between Lumena Android and Termux.
It binds to 127.0.0.1 only, uses a bearer token, constrains file access
to one workspace, and exposes an allow-listed project/tool surface.
"""
from __future__ import annotations

import json
import os
import re
import secrets
import shlex
import shutil
import subprocess
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
MAX_BODY = 2 * 1024 * 1024
MAX_WRITE = 1024 * 1024
MAX_OUTPUT = 128 * 1024
DEFAULT_TIMEOUT = 120
MODEL_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")
PROJECT_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")


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


def safe_child(base: Path, relative: str, *, must_exist: bool = False) -> Path:
    supplied = Path(relative or ".")
    if supplied.is_absolute():
        raise ValueError("Child path must be relative")
    candidate = (base / supplied).resolve()
    if candidate != WORKSPACE and WORKSPACE not in candidate.parents:
        raise ValueError("Path escapes LUMENA_WORKSPACE")
    if must_exist and not candidate.exists():
        raise FileNotFoundError(str(candidate))
    return candidate


def clamp(text: str) -> str:
    if len(text) <= MAX_OUTPUT:
        return text
    return text[:MAX_OUTPUT] + "\n...[output truncated]..."


def ok(stdout: str = "", *, exit_code: int = 0) -> dict[str, Any]:
    return {"ok": True, "exitCode": exit_code, "stdout": clamp(stdout), "stderr": "", "error": None}


def run_process(argv: list[str], cwd: Path, timeout: int = DEFAULT_TIMEOUT, env: dict[str, str] | None = None) -> dict[str, Any]:
    completed = subprocess.run(
        argv,
        cwd=str(cwd),
        capture_output=True,
        text=True,
        timeout=max(1, min(timeout, 1800)),
        shell=False,
        check=False,
        env=env,
    )
    return {
        "ok": completed.returncode == 0,
        "exitCode": completed.returncode,
        "stdout": clamp(completed.stdout or ""),
        "stderr": clamp(completed.stderr or ""),
        "error": None,
    }


def workspace_list(path_text: str) -> dict[str, Any]:
    directory = safe_path(path_text, must_exist=True)
    if not directory.is_dir():
        raise ValueError("workspace.list path is not a directory")
    rows: list[str] = []
    for entry in sorted(directory.iterdir(), key=lambda p: (not p.is_dir(), p.name.lower()))[:250]:
        rel = entry.relative_to(WORKSPACE)
        if entry.is_dir():
            rows.append(f"dir\t{rel}/")
        else:
            rows.append(f"file\t{entry.stat().st_size}\t{rel}")
    return ok("\n".join(rows) + ("\n" if rows else ""))


def write_file(path_text: str, content: str) -> dict[str, Any]:
    encoded = content.encode("utf-8")
    if len(encoded) > MAX_WRITE:
        raise ValueError(f"file.write exceeds {MAX_WRITE} bytes")
    path = safe_path(path_text)
    if path == WORKSPACE or path.exists() and path.is_dir():
        raise ValueError("file.write requires a file path")
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + ".lumena.tmp")
    temp.write_bytes(encoded)
    temp.replace(path)
    return ok(f"wrote={path.relative_to(WORKSPACE)}\nbytes={len(encoded)}\n")


def create_project(name: str, template: str) -> dict[str, Any]:
    if not PROJECT_RE.fullmatch(name):
        raise ValueError("Project name may contain letters, numbers, dot, underscore and dash")
    template = (template or "generic").lower()
    if template not in {"generic", "python"}:
        raise ValueError("Supported templates: generic, python")
    root = safe_path(name)
    if root.exists() and any(root.iterdir()):
        raise ValueError("Project directory already exists and is not empty")
    root.mkdir(parents=True, exist_ok=True)
    (root / "README.md").write_text(f"# {name}\n\nCreated with Lumena.\n", encoding="utf-8")
    (root / ".gitignore").write_text(".idea/\n.vscode/\n__pycache__/\n*.pyc\n.env\n", encoding="utf-8")
    if template == "python":
        (root / "main.py").write_text('def main():\n    print("Hello from Lumena")\n\n\nif __name__ == "__main__":\n    main()\n', encoding="utf-8")
        (root / "requirements.txt").write_text("", encoding="utf-8")
    result = run_process(["git", "init"], root, 30)
    if not result["ok"]:
        return result
    return ok(f"project={name}\ntemplate={template}\npath={root}\n")


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
        return ok("installed=%s\nrunning=true\nmodels=%s\n" % (
            str(installed).lower(),
            ", ".join(models) if models else "(none)",
        ))
    except Exception:
        return ok("installed=%s\nrunning=false\n" % str(installed).lower())


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
    return ok(f"Ollama start requested on {OLLAMA_HOST}\nlog={OLLAMA_LOG}\n")


def execute_tool(tool: str, args: dict[str, Any]) -> dict[str, Any]:
    if tool == "health":
        return ok(f"Lumena bridge OK\nworkspace={WORKSPACE}\n")

    if tool == "workspace.list":
        return workspace_list(str(args.get("path", "")))

    if tool == "file.read":
        path = safe_path(str(args.get("path", "")), must_exist=True)
        if not path.is_file():
            raise ValueError("Requested path is not a file")
        return ok(path.read_text(encoding="utf-8", errors="replace"))

    if tool == "file.write":
        return write_file(str(args.get("path", "")), str(args.get("content", "")))

    if tool == "dir.create":
        path = safe_path(str(args.get("path", "")))
        if path == WORKSPACE:
            raise ValueError("Choose a directory under the workspace")
        path.mkdir(parents=True, exist_ok=True)
        return ok(f"created={path.relative_to(WORKSPACE)}/\n")

    if tool == "project.create":
        return create_project(str(args.get("name", "")).strip(), str(args.get("template", "generic")))

    if tool in {"git.status", "git.diff", "git.log", "git.add", "git.commit"}:
        cwd = safe_path(str(args.get("cwd", "")), must_exist=True)
        if not cwd.is_dir():
            raise ValueError("cwd is not a directory")
        if tool == "git.status":
            argv = ["git", "status", "--short", "--branch"]
        elif tool == "git.diff":
            argv = ["git", "diff", "--"]
        elif tool == "git.log":
            argv = ["git", "log", "-n", "20", "--oneline", "--decorate"]
        elif tool == "git.add":
            path_arg = str(args.get("path", ".")).strip() or "."
            if path_arg == ".":
                argv = ["git", "add", "-A"]
            else:
                target = safe_child(cwd, path_arg)
                argv = ["git", "add", "--", os.path.relpath(target, cwd)]
        else:
            message = str(args.get("message", "")).strip()
            if not message or len(message) > 240:
                raise ValueError("git.commit requires a message up to 240 characters")
            argv = ["git", "commit", "-m", message]
        return run_process(argv, cwd, int(args.get("timeout", DEFAULT_TIMEOUT)))

    if tool == "python.run":
        script = safe_path(str(args.get("script", "")), must_exist=True)
        if not script.is_file() or script.suffix.lower() != ".py":
            raise ValueError("python.run requires an existing .py file inside the workspace")
        cwd_arg = str(args.get("cwd", "")).strip()
        cwd = safe_path(cwd_arg, must_exist=True) if cwd_arg else script.parent
        argv_text = str(args.get("argv", "")).strip()
        extra = shlex.split(argv_text) if argv_text else []
        return run_process(["python", str(script), *extra], cwd, int(args.get("timeout", DEFAULT_TIMEOUT)))

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
            env=env,
        )

    raise ValueError(f"Unknown or disabled tool: {tool}")


class Handler(BaseHTTPRequestHandler):
    server_version = "LumenaBridge/0.5"

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
            self._json(200, {"ok": True, "service": "lumena-termux-bridge", "version": "0.5"})
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
    print("Lumena Termux Bridge v0.5")
    print(f"Listening: http://{HOST}:{PORT}")
    print(f"Workspace: {WORKSPACE}")
    print(f"Token: {TOKEN}")
    print("Only 127.0.0.1 is bound; the bridge is not exposed to Wi-Fi.")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
