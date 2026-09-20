#!/data/data/com.termux/files/usr/bin/python
"""
Lumena Termux Bridge v0.14

Local-only bridge between Lumena Companion and Termux.
It binds to 127.0.0.1 only, uses a bearer token, constrains write access
to one workspace plus explicit read-only roots, exposes an allow-listed tool surface, and supports
request-scoped cancellation for long-running subprocess tools.
"""
from __future__ import annotations

import ipaddress
import json
import os
import platform
from datetime import datetime
import re
import socket
import secrets
import shlex
import shutil
import signal
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
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
CONTEXT_CACHE_FILE = STATE_DIR / "context_snapshot.json"
WORKSPACE = Path(os.environ.get("LUMENA_WORKSPACE", str(HOME / "lumena-workspace"))).expanduser().resolve()
READONLY_ROOTS_RAW = os.environ.get(
    "LUMENA_READONLY_ROOTS",
    str(HOME / "Lumena-Android"),
)
READONLY_ROOTS = tuple(
    Path(item).expanduser().resolve()
    for item in READONLY_ROOTS_RAW.split(os.pathsep)
    if item.strip()
)
READONLY_ALIASES = {root.name: root for root in READONLY_ROOTS}
BACKUP_ROOT = WORKSPACE / ".lumena-backups"
MAX_BODY = 512 * 1024
MAX_OUTPUT = 128 * 1024
MAX_HTTP_JSON = 2 * 1024 * 1024
MAX_SEARCH_FILE_BYTES = 1024 * 1024
MAX_SEARCH_RESULTS = 120
DEFAULT_TIMEOUT = 120
MODEL_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")
PROJECT_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,80}$")
REQUEST_ID_RE = re.compile(r"^[A-Za-z0-9._:-]{1,220}$")

ACTIVE_PROCESSES: dict[str, subprocess.Popen[str]] = {}
ACTIVE_STARTED: dict[str, float] = {}
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


def _inside(candidate: Path, root: Path) -> bool:
    try:
        resolved = candidate.resolve()
    except OSError:
        return False
    return resolved == root or root in resolved.parents


def _readonly_alias(raw: str) -> tuple[Path, str] | None:
    text = raw.strip()
    if not text:
        return None

    if text.startswith("@"):
        alias_path = Path(text[1:])
        if not alias_path.parts:
            return None
        alias = alias_path.parts[0]
        root = READONLY_ALIASES.get(alias)
        if root is None:
            raise ValueError(f"Unknown read-only root: @{alias}")
        rest = Path(*alias_path.parts[1:]) if len(alias_path.parts) > 1 else Path()
        return root, str(rest)

    parts = Path(text).parts
    if parts and parts[0] in READONLY_ALIASES:
        workspace_candidate = (WORKSPACE / Path(text)).resolve()
        if not workspace_candidate.exists():
            root = READONLY_ALIASES[parts[0]]
            rest = Path(*parts[1:]) if len(parts) > 1 else Path()
            return root, str(rest)

    return None


def safe_read_path(relative: str, *, must_exist: bool = False) -> Path:
    raw = str(relative or "").strip()
    if not raw:
        candidate = WORKSPACE
    else:
        supplied = Path(raw).expanduser()
        if supplied.is_absolute():
            candidate = supplied.resolve()
        else:
            alias = _readonly_alias(raw)
            if alias is not None:
                root, rest = alias
                candidate = (root / rest).resolve()
            else:
                candidate = (WORKSPACE / supplied).resolve()

    allowed = _inside(candidate, WORKSPACE) or any(
        _inside(candidate, root) for root in READONLY_ROOTS
    )
    if not allowed:
        raise ValueError("Path is outside Lumena's allowed read roots")
    if must_exist and not candidate.exists():
        raise FileNotFoundError(str(candidate))
    return candidate


def _read_root(path: Path) -> tuple[Path, str]:
    resolved = path.resolve()
    if _inside(resolved, WORKSPACE):
        return WORKSPACE, ""
    for root in READONLY_ROOTS:
        if _inside(resolved, root):
            return root, f"@{root.name}"
    raise ValueError("Path is outside Lumena's allowed read roots")


def display_read_path(path: Path) -> str:
    root, label = _read_root(path)
    rel = path.resolve().relative_to(root)
    if label:
        return label if not rel.parts else f"{label}/{rel}"
    return "." if not rel.parts else str(rel)


def _is_allowed_read_path(path: Path) -> bool:
    try:
        safe_read_path(str(path), must_exist=False)
        return True
    except (ValueError, OSError):
        return False


def clamp(text: str) -> str:
    if len(text) <= MAX_OUTPUT:
        return text
    return text[:MAX_OUTPUT] + "\n...[output truncated]..."


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


PUBLIC_HTTPS_OPENER = urllib.request.build_opener(
    urllib.request.ProxyHandler({}),
    NoRedirectHandler(),
)


def _bounded_int(value: Any, default: int, minimum: int, maximum: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = default
    return max(minimum, min(parsed, maximum))


def _is_workspace_path(path: Path) -> bool:
    try:
        resolved = path.resolve()
    except OSError:
        return False
    return resolved == WORKSPACE or WORKSPACE in resolved.parents


def _is_hidden_backup(path: Path) -> bool:
    if not _inside(path, WORKSPACE):
        return False
    try:
        rel = path.resolve().relative_to(WORKSPACE)
    except (ValueError, OSError):
        return False
    return bool(rel.parts and rel.parts[0] == BACKUP_ROOT.name)


def file_list(args: dict[str, Any]) -> dict[str, Any]:
    root = safe_read_path(str(args.get("path", "")).strip(), must_exist=True)
    if not root.is_dir():
        raise ValueError("file.list path must be a directory")

    depth = _bounded_int(args.get("depth"), 1, 1, 4)
    limit = _bounded_int(args.get("limit"), 300, 1, 500)
    rows: list[str] = []

    for path in sorted(root.rglob("*"), key=lambda p: str(p).lower()):
        if not _is_allowed_read_path(path) or _is_hidden_backup(path):
            continue
        try:
            rel_root = path.resolve().relative_to(root.resolve())
            shown = display_read_path(path)
        except (ValueError, OSError):
            continue
        if len(rel_root.parts) > depth:
            continue

        try:
            if path.is_dir():
                rows.append(f"{shown}/")
            elif path.is_file():
                rows.append(f"{shown}\t{path.stat().st_size} bytes")
            else:
                rows.append(f"{shown}\tother")
        except OSError:
            rows.append(f"{shown}\tunreadable")

        if len(rows) >= limit:
            rows.append("...[listing truncated]...")
            break

    return {
        "ok": True,
        "exitCode": 0,
        "stdout": "\n".join(rows) if rows else "(directory empty)",
        "stderr": "",
        "error": None,
    }


def file_search(args: dict[str, Any]) -> dict[str, Any]:
    query = str(args.get("query", "")).strip()
    if not query:
        raise ValueError("file.search requires query")
    if len(query) > 200:
        raise ValueError("file.search query exceeds 200 characters")

    root = safe_read_path(str(args.get("path", "")).strip(), must_exist=True)
    if not root.is_dir():
        raise ValueError("file.search path must be a directory")

    limit = _bounded_int(args.get("limit"), 60, 1, MAX_SEARCH_RESULTS)
    needle = query.casefold()
    matches: list[str] = []
    scanned = 0

    for path in sorted(root.rglob("*"), key=lambda p: str(p).lower()):
        if len(matches) >= limit:
            break
        if not _is_allowed_read_path(path) or _is_hidden_backup(path) or not path.is_file():
            continue

        scanned += 1
        if scanned > 3000:
            break

        try:
            rel_text = display_read_path(path)
            size = path.stat().st_size
        except (OSError, ValueError):
            continue
        if needle in rel_text.casefold():
            matches.append(f"PATH\t{rel_text}")
            if len(matches) >= limit:
                break

        if size > MAX_SEARCH_FILE_BYTES:
            continue

        try:
            raw = path.read_bytes()
        except OSError:
            continue
        if b"\x00" in raw[:8192]:
            continue

        text = raw.decode("utf-8", errors="replace")
        for line_no, line in enumerate(text.splitlines(), 1):
            if needle in line.casefold():
                compact = " ".join(line.strip().split())
                matches.append(f"{rel_text}:{line_no}\t{compact[:500]}")
                if len(matches) >= limit:
                    break

    suffix = ""
    if scanned > 3000:
        suffix = "\n...[search file limit reached]..."
    elif len(matches) >= limit:
        suffix = "\n...[result limit reached]..."

    return {
        "ok": True,
        "exitCode": 0,
        "stdout": ("\n".join(matches) if matches else "(no matches)") + suffix,
        "stderr": "",
        "error": None,
    }


def _read_key_value_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            if ":" in line:
                key, value = line.split(":", 1)
                values[key.strip()] = value.strip()
    except OSError:
        pass
    return values


def _read_optional_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace").strip()
    except OSError:
        return ""


def system_info() -> dict[str, Any]:
    mem = _read_key_value_file(Path("/proc/meminfo"))
    disk = shutil.disk_usage(WORKSPACE)

    battery_root = Path("/sys/class/power_supply/battery")
    battery_capacity = _read_optional_text(battery_root / "capacity")
    battery_status = _read_optional_text(battery_root / "status")

    thermal_values: list[float] = []
    for temp_path in Path("/sys/class/thermal").glob("thermal_zone*/temp"):
        raw = _read_optional_text(temp_path)
        try:
            value = float(raw)
            thermal_values.append(value / 1000.0 if value > 1000 else value)
        except ValueError:
            pass

    android_release = ""
    getprop = Path("/system/bin/getprop")
    if getprop.exists():
        try:
            android_release = subprocess.check_output(
                [str(getprop), "ro.build.version.release"],
                text=True,
                timeout=1,
                stderr=subprocess.DEVNULL,
            ).strip()
        except (OSError, subprocess.SubprocessError):
            pass

    info = {
        "platform": platform.platform(),
        "machine": platform.machine(),
        "cpu_count": os.cpu_count(),
        "android_release": android_release or None,
        "python": platform.python_version(),
        "termux_version": os.environ.get("TERMUX_VERSION") or None,
        "memory_total": mem.get("MemTotal"),
        "memory_available": mem.get("MemAvailable"),
        "workspace": str(WORKSPACE),
        "read_only_roots": {
            f"@{root.name}": str(root)
            for root in READONLY_ROOTS
            if root.exists()
        },
        "workspace_disk_total_bytes": disk.total,
        "workspace_disk_free_bytes": disk.free,
        "battery_capacity_percent": int(battery_capacity) if battery_capacity.isdigit() else None,
        "battery_status": battery_status or None,
        "max_thermal_c": round(max(thermal_values), 1) if thermal_values else None,
    }
    return {
        "ok": True,
        "exitCode": 0,
        "stdout": json.dumps(info, ensure_ascii=False, indent=2),
        "stderr": "",
        "error": None,
    }


def _validated_public_https_url(raw_url: str) -> str:
    if not raw_url or len(raw_url) > 4096:
        raise ValueError("http.json requires a URL up to 4096 characters")

    parsed = urllib.parse.urlsplit(raw_url)
    if parsed.scheme.lower() != "https":
        raise ValueError("http.json allows HTTPS only")
    if parsed.username or parsed.password:
        raise ValueError("Credentials in URL are not allowed")
    if not parsed.hostname:
        raise ValueError("URL hostname is required")
    if parsed.port not in {None, 443}:
        raise ValueError("http.json allows HTTPS port 443 only")

    host = parsed.hostname.rstrip(".").lower()
    if host == "localhost" or host.endswith(".localhost") or host.endswith(".local"):
        raise ValueError("Local hostnames are not allowed")

    try:
        addresses = {
            item[4][0]
            for item in socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
        }
    except socket.gaierror as exc:
        raise ValueError(f"DNS lookup failed: {exc}") from exc

    if not addresses:
        raise ValueError("DNS lookup returned no addresses")

    for address in addresses:
        ip = ipaddress.ip_address(address.split("%", 1)[0])
        if not ip.is_global:
            raise ValueError(f"Non-public destination is blocked: {ip}")

    return urllib.parse.urlunsplit(parsed)


def http_json(args: dict[str, Any]) -> dict[str, Any]:
    url = _validated_public_https_url(str(args.get("url", "")).strip())
    timeout = _bounded_int(args.get("timeout"), 10, 1, 20)
    request = urllib.request.Request(
        url,
        method="GET",
        headers={
            "Accept": "application/json",
            "User-Agent": "LumenaBridge/0.14",
            "Cache-Control": "no-cache",
        },
    )

    try:
        response = PUBLIC_HTTPS_OPENER.open(request, timeout=timeout)
    except urllib.error.HTTPError as exc:
        if 300 <= exc.code < 400:
            location = exc.headers.get("Location", "")
            raise ValueError(f"Redirects are blocked; target={location[:300]}") from exc
        body = exc.read(4096).decode("utf-8", errors="replace")
        raise ValueError(f"HTTP {exc.code}: {body[:1000]}") from exc
    except urllib.error.URLError as exc:
        raise ValueError(f"HTTPS request failed: {exc.reason}") from exc

    with response:
        content_length = response.headers.get("Content-Length")
        if content_length and int(content_length) > MAX_HTTP_JSON:
            raise ValueError("JSON response exceeds 2 MiB limit")

        raw = response.read(MAX_HTTP_JSON + 1)
        if len(raw) > MAX_HTTP_JSON:
            raise ValueError("JSON response exceeds 2 MiB limit")

        charset = response.headers.get_content_charset() or "utf-8"
        text = raw.decode(charset, errors="replace")
        try:
            payload = json.loads(text)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Response is not valid JSON: {exc}") from exc

        result = {
            "url": url,
            "status": getattr(response, "status", 200),
            "data": payload,
        }
        return {
            "ok": True,
            "exitCode": 0,
            "stdout": clamp(json.dumps(result, ensure_ascii=False, indent=2)),
            "stderr": "",
            "error": None,
        }


def http_get(args: dict[str, Any]) -> dict[str, Any]:
    url = _validated_public_https_url(str(args.get("url", "")).strip())
    timeout = _bounded_int(args.get("timeout"), 10, 1, 20)
    request = urllib.request.Request(
        url,
        method="GET",
        headers={
            "Accept": "text/html,text/plain,application/json,application/xml,text/xml,application/xhtml+xml;q=0.9,*/*;q=0.1",
            "User-Agent": "LumenaBridge/0.14",
            "Cache-Control": "no-cache",
        },
    )

    try:
        response = PUBLIC_HTTPS_OPENER.open(request, timeout=timeout)
    except urllib.error.HTTPError as exc:
        if 300 <= exc.code < 400:
            location = exc.headers.get("Location", "")
            raise ValueError(f"Redirects are blocked; target={location[:300]}") from exc
        body = exc.read(4096).decode("utf-8", errors="replace")
        raise ValueError(f"HTTP {exc.code}: {body[:1000]}") from exc
    except urllib.error.URLError as exc:
        raise ValueError(f"HTTPS request failed: {exc.reason}") from exc

    with response:
        content_length = response.headers.get("Content-Length")
        if content_length and int(content_length) > MAX_HTTP_JSON:
            raise ValueError("HTTP response exceeds 2 MiB limit")

        content_type = response.headers.get_content_type().lower()
        textual = (
            content_type.startswith("text/")
            or content_type in {
                "application/json",
                "application/xml",
                "application/xhtml+xml",
                "application/javascript",
                "application/x-javascript",
            }
        )
        if not textual:
            raise ValueError(f"http.get only accepts textual responses, got {content_type}")

        raw = response.read(MAX_HTTP_JSON + 1)
        if len(raw) > MAX_HTTP_JSON:
            raise ValueError("HTTP response exceeds 2 MiB limit")

        charset = response.headers.get_content_charset() or "utf-8"
        body = raw.decode(charset, errors="replace")
        out = {
            "url": url,
            "status": getattr(response, "status", 200),
            "content_type": content_type,
            "body": body,
        }
        return {
            "ok": True,
            "exitCode": 0,
            "stdout": clamp(json.dumps(out, ensure_ascii=False, indent=2)),
            "stderr": "",
            "error": None,
        }


def process_status(args: dict[str, Any]) -> dict[str, Any]:
    requested = str(args.get("requestId", "")).strip()
    if requested and not REQUEST_ID_RE.fullmatch(requested):
        raise ValueError("Invalid requestId")

    with ACTIVE_LOCK:
        items = list(ACTIVE_PROCESSES.items())
        started = dict(ACTIVE_STARTED)

    rows: list[dict[str, Any]] = []
    for request_id, process in items:
        if requested and request_id != requested:
            continue
        running = process.poll() is None
        rss_kb = None
        status_path = Path(f"/proc/{process.pid}/status")
        try:
            for line in status_path.read_text(encoding="utf-8", errors="replace").splitlines():
                if line.startswith("VmRSS:"):
                    parts = line.split()
                    rss_kb = int(parts[1]) if len(parts) >= 2 and parts[1].isdigit() else None
                    break
        except OSError:
            pass

        argv = process.args
        if isinstance(argv, (list, tuple)):
            command = " ".join(str(x) for x in argv)
        else:
            command = str(argv)

        rows.append({
            "requestId": request_id,
            "pid": process.pid,
            "running": running,
            "returncode": process.returncode,
            "elapsed_seconds": round(max(0.0, time.monotonic() - started.get(request_id, time.monotonic())), 2),
            "rss_kb": rss_kb,
            "command": command[:1200],
        })

    payload = {
        "active_count": sum(1 for row in rows if row["running"]),
        "processes": rows,
    }
    return {
        "ok": True,
        "exitCode": 0,
        "stdout": json.dumps(payload, ensure_ascii=False, indent=2),
        "stderr": "",
        "error": None,
    }


READ_ONLY_BATCH_TOOLS = {
    "health",
    "system.time",
    "system.info",
    "http.json",
    "http.get",
    "context.snapshot",
    "process.status",
    "workspace.list",
    "file.list",
    "file.search",
    "file.read",
    "git.status",
    "git.diff",
    "git.log",
    "ollama.status",
}


def inspect_batch(args: dict[str, Any], request_id: str | None = None) -> dict[str, Any]:
    raw = args.get("requests")
    if isinstance(raw, str):
        try:
            requests = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise ValueError(f"inspect.batch requests must be valid JSON: {exc}") from exc
    else:
        requests = raw

    if not isinstance(requests, list) or not requests:
        raise ValueError("inspect.batch requires a non-empty requests array")
    if len(requests) > 8:
        raise ValueError("inspect.batch supports at most 8 requests")

    prepared: list[tuple[int, str, dict[str, Any]]] = []
    for index, item in enumerate(requests):
        if not isinstance(item, dict):
            raise ValueError(f"inspect.batch item {index} must be an object")
        tool = str(item.get("tool", "")).strip()
        sub_args = item.get("args") or {}
        if tool not in READ_ONLY_BATCH_TOOLS:
            raise ValueError(f"inspect.batch tool is not read-only or not allowed: {tool}")
        if not isinstance(sub_args, dict):
            raise ValueError(f"inspect.batch args for {tool} must be an object")
        prepared.append((index, tool, sub_args))

    def run_one(item: tuple[int, str, dict[str, Any]]) -> dict[str, Any]:
        index, tool, sub_args = item
        sub_id = f"{request_id}:{index}" if request_id else None
        try:
            result = execute_tool(tool, sub_args, request_id=sub_id)
        except Exception as exc:
            result = {
                "ok": False,
                "exitCode": None,
                "stdout": "",
                "stderr": "",
                "error": f"{type(exc).__name__}: {exc}",
            }
        return {
            "index": index,
            "tool": tool,
            "ok": bool(result.get("ok")),
            "exitCode": result.get("exitCode"),
            "stdout": str(result.get("stdout", ""))[:20000],
            "stderr": str(result.get("stderr", ""))[:6000],
            "error": result.get("error"),
        }

    results: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=min(4, len(prepared))) as pool:
        futures = [pool.submit(run_one, item) for item in prepared]
        for future in as_completed(futures):
            results.append(future.result())

    results.sort(key=lambda row: row["index"])
    return {
        "ok": all(row["ok"] for row in results),
        "exitCode": 0 if all(row["ok"] for row in results) else 1,
        "stdout": clamp(json.dumps({"results": results}, ensure_ascii=False, indent=2)),
        "stderr": "",
        "error": None if all(row["ok"] for row in results) else "One or more read-only inspections failed",
    }


def _register_process(request_id: str | None, process: subprocess.Popen[str]) -> None:
    if not request_id:
        return
    with ACTIVE_LOCK:
        ACTIVE_PROCESSES[request_id] = process
        ACTIVE_STARTED[request_id] = time.monotonic()


def _unregister_process(request_id: str | None, process: subprocess.Popen[str]) -> None:
    if not request_id:
        return
    with ACTIVE_LOCK:
        if ACTIVE_PROCESSES.get(request_id) is process:
            ACTIVE_PROCESSES.pop(request_id, None)
            ACTIVE_STARTED.pop(request_id, None)


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
    binary = shutil.which("ollama")
    installed = binary is not None
    tags: list[str] = []
    api_ps_models: list[dict[str, Any]] = []
    cli_ps_models: list[str] = []
    cli_ps_raw = ""
    api_version = None
    server_ok = False

    try:
        with urllib.request.urlopen(f"{OLLAMA_API}/api/tags", timeout=2) as response:
            payload = json.loads(response.read().decode("utf-8"))
        tags = [m.get("name", "") for m in payload.get("models", []) if m.get("name")]
        server_ok = True
    except Exception:
        pass

    if server_ok:
        try:
            with urllib.request.urlopen(f"{OLLAMA_API}/api/version", timeout=2) as response:
                payload = json.loads(response.read().decode("utf-8"))
            api_version = payload.get("version")
        except Exception:
            pass

        try:
            with urllib.request.urlopen(f"{OLLAMA_API}/api/ps", timeout=2) as response:
                payload = json.loads(response.read().decode("utf-8"))
            for model in payload.get("models", []):
                if not isinstance(model, dict):
                    continue
                details = model.get("details") if isinstance(model.get("details"), dict) else {}
                api_ps_models.append({
                    "name": model.get("name") or model.get("model"),
                    "size": model.get("size"),
                    "size_vram": model.get("size_vram"),
                    "context_length": model.get("context_length"),
                    "expires_at": model.get("expires_at"),
                    "family": details.get("family"),
                    "parameter_size": details.get("parameter_size"),
                    "quantization_level": details.get("quantization_level"),
                })
        except Exception:
            pass

    if binary:
        try:
            cli_env = os.environ.copy()
            cli_ps_raw = subprocess.check_output(
                [binary, "ps"],
                text=True,
                timeout=3,
                stderr=subprocess.STDOUT,
                env=cli_env,
            ).strip()
            lines = [line for line in cli_ps_raw.splitlines() if line.strip()]
            for line in lines[1:]:
                name = line.split()[0] if line.split() else ""
                if name:
                    cli_ps_models.append(name)
        except (OSError, subprocess.SubprocessError):
            pass

    process_lines: list[str] = []
    try:
        ps_text = subprocess.check_output(
            ["ps", "-A"],
            text=True,
            timeout=2,
            stderr=subprocess.DEVNULL,
        )
        process_lines = [
            line.strip()
            for line in ps_text.splitlines()
            if "ollama" in line.lower()
        ][:20]
    except (OSError, subprocess.SubprocessError):
        pass

    api_names = {
        str(item.get("name") or "").strip()
        for item in api_ps_models
        if str(item.get("name") or "").strip()
    }
    cli_names = {name.strip() for name in cli_ps_models if name.strip()}
    if api_names == cli_names:
        consistency = "match"
    elif api_names or cli_names:
        consistency = "mismatch"
    else:
        consistency = "both-empty"

    summary = {
        "installed": installed,
        "running": server_ok,
        "models": tags,
        "loaded_models": api_ps_models,
        "binary": binary,
        "bridge_endpoint": OLLAMA_API,
        "env_OLLAMA_HOST": os.environ.get("OLLAMA_HOST"),
        "api_running": server_ok,
        "api_version": api_version,
        "installed_models": tags,
        "api_ps_models": api_ps_models,
        "cli_ps_models": cli_ps_models,
        "cli_ps_raw": cli_ps_raw,
        "ollama_processes": process_lines,
        "runtime_visibility": consistency,
        "note": (
            "A mismatch means the CLI and bridge/API do not see the same loaded-model state; "
            "do not conclude that a model is unloaded from only one source."
        ),
    }
    return {
        "ok": True,
        "exitCode": 0,
        "stdout": json.dumps(summary, ensure_ascii=False, indent=2),
        "stderr": "",
        "error": None,
    }

def ollama_generate(args: dict[str, Any]) -> dict[str, Any]:
    model = str(args.get("model", "")).strip()
    prompt = str(args.get("prompt", ""))
    if not MODEL_RE.fullmatch(model):
        raise ValueError("Invalid Ollama model name")
    if not prompt.strip():
        raise ValueError("ollama.generate requires a non-empty prompt")
    if len(prompt) > 16000:
        raise ValueError("ollama.generate prompt exceeds 16000 characters")

    timeout = _bounded_int(args.get("timeout"), 60, 5, 180)
    body = json.dumps({
        "model": model,
        "prompt": prompt,
        "stream": False,
        "keep_alive": str(args.get("keep_alive", "5m"))[:32],
    }).encode("utf-8")

    request = urllib.request.Request(
        f"{OLLAMA_API}/api/generate",
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
    )

    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read(MAX_HTTP_JSON + 1)
    except urllib.error.HTTPError as exc:
        body_text = exc.read(4096).decode("utf-8", errors="replace")
        raise ValueError(f"Ollama HTTP {exc.code}: {body_text[:1000]}") from exc
    except urllib.error.URLError as exc:
        raise ValueError(f"Ollama request failed: {exc.reason}") from exc

    if len(raw) > MAX_HTTP_JSON:
        raise ValueError("Ollama response exceeds 2 MiB limit")

    try:
        payload = json.loads(raw.decode("utf-8"))
    except json.JSONDecodeError as exc:
        raise ValueError(f"Ollama returned invalid JSON: {exc}") from exc

    result = {
        "model": payload.get("model") or model,
        "response": payload.get("response", ""),
        "done": payload.get("done"),
        "done_reason": payload.get("done_reason"),
        "total_duration": payload.get("total_duration"),
        "load_duration": payload.get("load_duration"),
        "prompt_eval_count": payload.get("prompt_eval_count"),
        "eval_count": payload.get("eval_count"),
        "eval_duration": payload.get("eval_duration"),
    }
    return {
        "ok": True,
        "exitCode": 0,
        "stdout": clamp(json.dumps(result, ensure_ascii=False, indent=2)),
        "stderr": "",
        "error": None,
    }


def ollama_start() -> dict[str, Any]:
    binary = ollama_binary()
    current = ollama_status()
    try:
        current_payload = json.loads(current.get("stdout", "{}"))
    except json.JSONDecodeError:
        current_payload = {}
    if current_payload.get("api_running") is True or current_payload.get("running") is True:
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

    for root in READONLY_ROOTS:
        if root.exists() and root.is_dir():
            lines.append(f"@{root.name}/\t[read-only root]")

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


def context_snapshot(args: dict[str, Any]) -> dict[str, Any]:
    refresh = str(args.get("refresh", "false")).lower() in {"1", "true", "yes"}
    max_age = _bounded_int(args.get("max_age"), 300, 30, 3600)

    if CONTEXT_CACHE_FILE.exists() and not refresh:
        try:
            age = time.time() - CONTEXT_CACHE_FILE.stat().st_mtime
            if age <= max_age:
                cached = CONTEXT_CACHE_FILE.read_text(encoding="utf-8")
                json.loads(cached)
                return {
                    "ok": True,
                    "exitCode": 0,
                    "stdout": clamp(cached),
                    "stderr": "",
                    "error": None,
                }
        except (OSError, json.JSONDecodeError):
            pass

    system_payload = {}
    try:
        system_payload = json.loads(system_info()["stdout"])
    except (KeyError, TypeError, json.JSONDecodeError):
        pass

    roots: list[dict[str, Any]] = [{
        "name": "workspace",
        "path": str(WORKSPACE),
        "alias": ".",
        "mode": "read-write",
        "exists": WORKSPACE.exists(),
    }]
    roots.extend(
        {
            "name": root.name,
            "path": str(root),
            "alias": f"@{root.name}",
            "mode": "read-only",
            "exists": root.exists(),
        }
        for root in READONLY_ROOTS
    )

    git_state: dict[str, Any] = {}
    candidates: list[tuple[str, Path]] = [(".", WORKSPACE)]
    candidates.extend((f"@{root.name}", root) for root in READONLY_ROOTS)

    for label, root in candidates:
        if not root.exists() or not root.is_dir():
            continue
        git_dir = root / ".git"
        if not git_dir.exists():
            continue
        try:
            result = execute_tool("git.status", {"cwd": label})
            git_state[label] = {
                "ok": result.get("ok"),
                "status": str(result.get("stdout", ""))[:8000],
                "error": result.get("error"),
            }
        except Exception as exc:
            git_state[label] = {
                "ok": False,
                "status": "",
                "error": f"{type(exc).__name__}: {exc}",
            }

    snapshot = {
        "generated_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "system": system_payload,
        "roots": roots,
        "workspace_listing": workspace_listing().splitlines()[:250],
        "git": git_state,
    }
    encoded = json.dumps(snapshot, ensure_ascii=False, indent=2)
    try:
        CONTEXT_CACHE_FILE.write_text(encoded, encoding="utf-8")
        CONTEXT_CACHE_FILE.chmod(0o600)
    except OSError:
        pass

    return {
        "ok": True,
        "exitCode": 0,
        "stdout": clamp(encoded),
        "stderr": "",
        "error": None,
    }


def execute_tool(tool: str, args: dict[str, Any], request_id: str | None = None) -> dict[str, Any]:
    if tool == "health":
        return {
            "ok": True,
            "exitCode": 0,
            "stdout": (
                f"Lumena bridge OK\n"
                f"workspace={WORKSPACE}\n"
                f"read_only_roots={','.join('@' + root.name for root in READONLY_ROOTS if root.exists()) or '(none)'}\n"
                f"version=0.14\n"
            ),
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

    if tool == "system.info":
        return system_info()

    if tool == "http.json":
        return http_json(args)

    if tool == "http.get":
        return http_get(args)

    if tool == "context.snapshot":
        return context_snapshot(args)

    if tool == "process.status":
        return process_status(args)

    if tool == "inspect.batch":
        return inspect_batch(args, request_id=request_id)

    if tool == "file.list":
        return file_list(args)

    if tool == "file.search":
        return file_search(args)

    if tool == "workspace.list":
        return {"ok": True, "exitCode": 0, "stdout": workspace_listing(), "stderr": "", "error": None}

    if tool == "file.read":
        path = safe_read_path(str(args.get("path", "")), must_exist=True)
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

    if tool in {"git.status", "git.diff", "git.log"}:
        cwd = safe_read_path(str(args.get("cwd", "")), must_exist=True)
        if not cwd.is_dir():
            raise ValueError("cwd is not a directory")

        env = os.environ.copy()
        env["GIT_OPTIONAL_LOCKS"] = "0"
        env["GIT_PAGER"] = "cat"
        env["PAGER"] = "cat"

        if tool == "git.status":
            argv = [
                "git",
                "-c",
                "core.fsmonitor=false",
                "--no-optional-locks",
                "status",
                "--short",
                "--branch",
            ]
        elif tool == "git.diff":
            argv = [
                "git",
                "--no-pager",
                "diff",
                "--no-ext-diff",
                "--no-textconv",
                "--",
            ]
        else:
            argv = [
                "git",
                "--no-pager",
                "log",
                "-n",
                "12",
                "--oneline",
                "--decorate",
            ]

        return run_process(
            argv,
            cwd,
            int(args.get("timeout", DEFAULT_TIMEOUT)),
            request_id=request_id,
            env=env,
        )

    if tool in {"git.add", "git.commit"}:
        cwd = safe_path(str(args.get("cwd", "")), must_exist=True)
        if not cwd.is_dir():
            raise ValueError("cwd is not a directory")

        if tool == "git.add":
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

    if tool == "ollama.generate":
        return ollama_generate(args)

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
    server_version = "LumenaBridge/0.14"

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
            self._json(200, {"ok": True, "service": "lumena-termux-bridge", "version": "0.14"})
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
    print("Lumena Termux Bridge v0.14")
    print(f"Listening: http://{HOST}:{PORT}")
    print(f"Workspace: {WORKSPACE}")
    print(f"Token: {TOKEN}")
    print("Only 127.0.0.1 is bound; the bridge is not exposed to Wi-Fi.")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
