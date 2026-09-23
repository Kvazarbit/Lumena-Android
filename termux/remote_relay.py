#!/data/data/com.termux/files/usr/bin/python
"""
Lumena Remote Relay v1.

A narrow, auditable transport that lets an authenticated ChatGPT/GitHub client
submit PUBLIC_WEB read-only tool calls to the local Lumena Termux bridge without
exposing the phone on Wi-Fi or the public Internet.

Transport:
  private GitHub issue comments -> relay poller -> 127.0.0.1 Lumena bridge
  -> result comment in the same private issue.

Security properties:
- mailbox repository MUST be private;
- only one configured GitHub login may author requests;
- only a tiny PUBLIC_WEB read-only tool allow-list is remotely executable;
- no file/system/git/python/ollama/mutating tool can be reached by this relay;
- the existing bridge remains loopback-only and bearer-token protected;
- each requestId is replay-protected;
- old comments are never executed when the relay is first configured.
"""
from __future__ import annotations

import argparse
import getpass
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

VERSION = "1.0"
API_ROOT = "https://api.github.com"
HOME = Path.home()
STATE_DIR = HOME / ".lumena"
CONFIG_FILE = STATE_DIR / "remote_relay.json"
STATE_FILE = STATE_DIR / "remote_relay_state.json"
GITHUB_TOKEN_FILE = STATE_DIR / "github_token"
BRIDGE_TOKEN_FILE = STATE_DIR / "bridge_token"
BRIDGE_SCRIPT = STATE_DIR / "bridge.py"
BRIDGE_LOG = STATE_DIR / "remote_bridge.log"

REQUEST_MARKER = "LUMENA_REMOTE_REQUEST_V1"
RESULT_MARKER = "LUMENA_REMOTE_RESULT_V1"
REQUEST_ID_RE = re.compile(r"^[A-Za-z0-9._:-]{1,120}$")
REPO_RE = re.compile(r"^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$")

REMOTE_PUBLIC_WEB_TOOLS = frozenset({
    "web.search",
    "web.read",
    "http.get",
    "http.json",
    "image.search",
})

MAX_ARGS = 16
MAX_ARG_KEY = 80
MAX_ARG_TEXT = 16_000
MAX_REQUEST_BYTES = 32 * 1024
MAX_RESULT_FIELD = 22_000
MAX_PROCESSED_IDS = 512


class RelayError(RuntimeError):
    pass


class ProtocolError(RelayError):
    pass


@dataclass(frozen=True)
class RelayConfig:
    repo: str
    issue_number: int
    allowed_author: str
    poll_seconds: int = 8
    bridge_url: str = "http://127.0.0.1:8765"
    require_private_repo: bool = True

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> "RelayConfig":
        repo = str(raw.get("repo") or "").strip()
        allowed_author = str(raw.get("allowedAuthor") or "").strip()
        try:
            issue_number = int(raw.get("issueNumber"))
        except (TypeError, ValueError) as exc:
            raise RelayError("remote_relay.json issueNumber must be an integer") from exc
        try:
            poll_seconds = int(raw.get("pollSeconds", 8))
        except (TypeError, ValueError):
            poll_seconds = 8
        bridge_url = str(raw.get("bridgeUrl") or "http://127.0.0.1:8765").strip().rstrip("/")

        if not REPO_RE.fullmatch(repo):
            raise RelayError("repo must be owner/name")
        if issue_number < 1:
            raise RelayError("issueNumber must be >= 1")
        if not allowed_author or len(allowed_author) > 100:
            raise RelayError("allowedAuthor is required")
        if bridge_url not in {
            "http://127.0.0.1:8765",
            "http://localhost:8765",
        }:
            raise RelayError("remote relay may call only the loopback Lumena bridge")
        return cls(
            repo=repo,
            issue_number=issue_number,
            allowed_author=allowed_author,
            poll_seconds=max(3, min(poll_seconds, 300)),
            bridge_url=bridge_url,
            require_private_repo=bool(raw.get("requirePrivateRepo", True)),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "repo": self.repo,
            "issueNumber": self.issue_number,
            "allowedAuthor": self.allowed_author,
            "pollSeconds": self.poll_seconds,
            "bridgeUrl": self.bridge_url,
            "requirePrivateRepo": True,
        }


@dataclass(frozen=True)
class RemoteRequest:
    request_id: str
    tool: str
    args: dict[str, Any]


@dataclass
class RelayState:
    last_comment_id: int = 0
    last_seen_at: str = "1970-01-01T00:00:00Z"
    processed_request_ids: list[str] | None = None

    def __post_init__(self) -> None:
        if self.processed_request_ids is None:
            self.processed_request_ids = []

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> "RelayState":
        try:
            comment_id = int(raw.get("lastCommentId", 0))
        except (TypeError, ValueError):
            comment_id = 0
        seen = str(raw.get("lastSeenAt") or "1970-01-01T00:00:00Z")
        ids = raw.get("processedRequestIds")
        if not isinstance(ids, list):
            ids = []
        clean = [
            str(value)
            for value in ids
            if isinstance(value, str) and REQUEST_ID_RE.fullmatch(value)
        ][-MAX_PROCESSED_IDS:]
        return cls(max(0, comment_id), seen, clean)

    def to_dict(self) -> dict[str, Any]:
        return {
            "lastCommentId": self.last_comment_id,
            "lastSeenAt": self.last_seen_at,
            "processedRequestIds": list(self.processed_request_ids or [])[-MAX_PROCESSED_IDS:],
        }

    def remember(self, request_id: str) -> None:
        ids = list(self.processed_request_ids or [])
        if request_id not in ids:
            ids.append(request_id)
        self.processed_request_ids = ids[-MAX_PROCESSED_IDS:]


def _atomic_write_json(path: Path, payload: dict[str, Any], mode: int = 0o600) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_name = tempfile.mkstemp(prefix=path.name + ".", dir=str(path.parent))
    temp = Path(temp_name)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(payload, stream, ensure_ascii=False, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temp, mode)
        os.replace(temp, path)
    finally:
        try:
            temp.unlink(missing_ok=True)
        except OSError:
            pass


def _read_json(path: Path) -> dict[str, Any]:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise RelayError(f"Missing {path}. Run --configure first.") from exc
    except (OSError, json.JSONDecodeError) as exc:
        raise RelayError(f"Could not read {path}: {exc}") from exc
    if not isinstance(raw, dict):
        raise RelayError(f"{path} must contain a JSON object")
    return raw


def load_config() -> RelayConfig:
    return RelayConfig.from_dict(_read_json(CONFIG_FILE))


def load_state() -> RelayState:
    if not STATE_FILE.exists():
        return RelayState()
    return RelayState.from_dict(_read_json(STATE_FILE))


def save_state(state: RelayState) -> None:
    _atomic_write_json(STATE_FILE, state.to_dict())


def _load_secret(path: Path, env_name: str) -> str:
    from_env = os.environ.get(env_name, "").strip()
    if from_env:
        return from_env
    try:
        value = path.read_text(encoding="utf-8").strip()
    except FileNotFoundError as exc:
        raise RelayError(f"Missing {path}") from exc
    if not value:
        raise RelayError(f"{path} is empty")
    return value


def _store_secret(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value.strip() + "\n", encoding="utf-8")
    path.chmod(stat.S_IRUSR | stat.S_IWUSR)


def _strip_optional_fence(text: str) -> str:
    stripped = text.strip()
    if not stripped.startswith("~~~"):
        return stripped
    lines = stripped.splitlines()
    if len(lines) < 3 or not lines[-1].strip().startswith("~~~"):
        raise ProtocolError("unterminated JSON fence")
    return "\n".join(lines[1:-1]).strip()


def parse_request_comment(body: str) -> RemoteRequest | None:
    text = str(body or "")
    if not text.startswith(REQUEST_MARKER):
        return None
    payload_text = text[len(REQUEST_MARKER):].lstrip("\r\n \t")
    payload_text = _strip_optional_fence(payload_text)
    if not payload_text:
        raise ProtocolError("request marker has no JSON payload")
    if len(payload_text.encode("utf-8")) > MAX_REQUEST_BYTES:
        raise ProtocolError("request payload is too large")

    try:
        raw = json.loads(payload_text)
    except json.JSONDecodeError as exc:
        raise ProtocolError(f"invalid request JSON: {exc}") from exc
    if not isinstance(raw, dict):
        raise ProtocolError("request JSON must be an object")

    allowed_keys = {"requestId", "tool", "args"}
    extras = sorted(set(raw) - allowed_keys)
    if extras:
        raise ProtocolError("unexpected request fields: " + ", ".join(extras))

    request_id = str(raw.get("requestId") or "").strip()
    tool = str(raw.get("tool") or "").strip().lower()
    args = raw.get("args", {})

    if not REQUEST_ID_RE.fullmatch(request_id):
        raise ProtocolError("invalid requestId")
    if tool not in REMOTE_PUBLIC_WEB_TOOLS:
        raise ProtocolError(
            f"remote tool blocked: {tool or '(empty)'}; "
            f"allowed={','.join(sorted(REMOTE_PUBLIC_WEB_TOOLS))}"
        )
    if not isinstance(args, dict):
        raise ProtocolError("args must be an object")
    if len(args) > MAX_ARGS:
        raise ProtocolError(f"args supports at most {MAX_ARGS} fields")

    normalized: dict[str, Any] = {}
    for key, value in args.items():
        if not isinstance(key, str) or not key or len(key) > MAX_ARG_KEY:
            raise ProtocolError("argument keys must be short non-empty strings")
        if isinstance(value, (dict, list, tuple)):
            raise ProtocolError(f"nested argument is not allowed: {key}")
        if value is None:
            raise ProtocolError(f"null argument is not allowed: {key}")
        if isinstance(value, str) and len(value) > MAX_ARG_TEXT:
            raise ProtocolError(f"argument too large: {key}")
        if not isinstance(value, (str, int, float, bool)):
            raise ProtocolError(f"unsupported argument type: {key}")
        normalized[key] = value

    return RemoteRequest(request_id=request_id, tool=tool, args=normalized)


def _bounded_text(value: Any, limit: int = MAX_RESULT_FIELD) -> str:
    text = str(value or "")
    if len(text) <= limit:
        return text
    return text[:limit] + "\n...[remote relay truncated]..."


def result_comment(
    request: RemoteRequest | None,
    *,
    status: str,
    source_comment_id: int,
    result: dict[str, Any] | None = None,
    error: str | None = None,
) -> str:
    payload: dict[str, Any] = {
        "version": 1,
        "status": status,
        "sourceCommentId": source_comment_id,
        "requestId": request.request_id if request else None,
        "tool": request.tool if request else None,
        "ok": bool(result.get("ok")) if isinstance(result, dict) else False,
    }
    if isinstance(result, dict):
        payload.update({
            "exitCode": result.get("exitCode"),
            "stdout": _bounded_text(result.get("stdout")),
            "stderr": _bounded_text(result.get("stderr"), 8_000),
            "error": _bounded_text(result.get("error"), 4_000) or None,
        })
    elif error:
        payload["error"] = _bounded_text(error, 4_000)

    encoded = json.dumps(payload, ensure_ascii=False, indent=2)
    return f"{RESULT_MARKER}\n{encoded}"


class GitHubClient:
    def __init__(self, token: str):
        self.token = token

    def _request(self, method: str, path: str, payload: dict[str, Any] | None = None) -> Any:
        url = API_ROOT + path
        body = None
        headers = {
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {self.token}",
            "User-Agent": f"LumenaRemoteRelay/{VERSION}",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"

        request = urllib.request.Request(url, data=body, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=25) as response:
                raw = response.read(2 * 1024 * 1024)
        except urllib.error.HTTPError as exc:
            details = exc.read(8_000).decode("utf-8", errors="replace")
            raise RelayError(f"GitHub HTTP {exc.code}: {details[:1200]}") from exc
        except urllib.error.URLError as exc:
            raise RelayError(f"GitHub transport failed: {exc.reason}") from exc
        if not raw:
            return None
        try:
            return json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError as exc:
            raise RelayError("GitHub returned invalid JSON") from exc

    def repo(self, repo: str) -> dict[str, Any]:
        value = self._request("GET", f"/repos/{repo}")
        if not isinstance(value, dict):
            raise RelayError("GitHub repository response is not an object")
        return value

    def issue(self, repo: str, issue_number: int) -> dict[str, Any]:
        value = self._request("GET", f"/repos/{repo}/issues/{issue_number}")
        if not isinstance(value, dict):
            raise RelayError("GitHub issue response is not an object")
        return value

    def comments(self, repo: str, issue_number: int, since: str | None = None) -> list[dict[str, Any]]:
        rows: list[dict[str, Any]] = []
        page = 1
        while page <= 10:
            query = {"per_page": "100", "page": str(page)}
            if since:
                query["since"] = since
            path = (
                f"/repos/{repo}/issues/{issue_number}/comments?"
                + urllib.parse.urlencode(query)
            )
            value = self._request("GET", path)
            if not isinstance(value, list):
                raise RelayError("GitHub comments response is not a list")
            rows.extend(item for item in value if isinstance(item, dict))
            if len(value) < 100:
                break
            page += 1
        return rows

    def post_comment(self, repo: str, issue_number: int, body: str) -> dict[str, Any]:
        value = self._request(
            "POST",
            f"/repos/{repo}/issues/{issue_number}/comments",
            {"body": body},
        )
        if not isinstance(value, dict):
            raise RelayError("GitHub comment creation returned no object")
        return value


class BridgeClient:
    def __init__(self, base_url: str, token: str):
        self.endpoint = base_url.rstrip("/") + "/tool"
        self.root = base_url.rstrip("/")
        self.token = token

    def alive(self) -> bool:
        request = urllib.request.Request(self.root, method="GET")
        try:
            with urllib.request.urlopen(request, timeout=1.5) as response:
                return 200 <= response.status < 300
        except Exception:
            return False

    def ensure_running(self) -> None:
        if self.alive():
            return
        if not BRIDGE_SCRIPT.exists():
            raise RelayError(f"Local bridge not installed at {BRIDGE_SCRIPT}")
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        log = open(BRIDGE_LOG, "ab", buffering=0)
        subprocess.Popen(
            [sys.executable, str(BRIDGE_SCRIPT)],
            cwd=str(HOME),
            stdin=subprocess.DEVNULL,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
            close_fds=True,
        )
        for _ in range(30):
            time.sleep(0.25)
            if self.alive():
                return
        raise RelayError("Local Lumena bridge did not become ready")

    def execute(self, request_data: RemoteRequest) -> dict[str, Any]:
        self.ensure_running()
        body = json.dumps({
            "tool": request_data.tool,
            "args": request_data.args,
            "requestId": request_data.request_id,
        }, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            self.endpoint,
            data=body,
            method="POST",
            headers={
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "application/json; charset=utf-8",
                "Connection": "close",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                raw = response.read(256 * 1024)
        except urllib.error.HTTPError as exc:
            raw = exc.read(64 * 1024)
        except urllib.error.URLError as exc:
            return {
                "ok": False,
                "exitCode": None,
                "stdout": "",
                "stderr": "",
                "error": f"Bridge transport failed: {exc.reason}",
            }
        try:
            parsed = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return {
                "ok": False,
                "exitCode": None,
                "stdout": "",
                "stderr": "",
                "error": "Bridge returned unreadable JSON",
            }
        if not isinstance(parsed, dict):
            return {
                "ok": False,
                "exitCode": None,
                "stdout": "",
                "stderr": "",
                "error": "Bridge returned a non-object result",
            }
        return parsed


def verify_mailbox(config: RelayConfig, github: GitHubClient) -> None:
    repo = github.repo(config.repo)
    if config.require_private_repo and repo.get("private") is not True:
        raise RelayError(
            "Remote relay refuses a public repository. "
            "Use a dedicated private mailbox repository."
        )
    issue = github.issue(config.repo, config.issue_number)
    if "pull_request" in issue:
        raise RelayError("mailbox issueNumber points to a pull request, not an issue")
    if str(issue.get("state") or "").lower() != "open":
        raise RelayError("mailbox issue must remain open")


def _comment_id(comment: dict[str, Any]) -> int:
    try:
        return int(comment.get("id", 0))
    except (TypeError, ValueError):
        return 0


def _comment_time(comment: dict[str, Any]) -> str:
    value = str(comment.get("updated_at") or comment.get("created_at") or "").strip()
    return value or "1970-01-01T00:00:00Z"


def bootstrap_state(config: RelayConfig, github: GitHubClient) -> RelayState:
    comments = github.comments(config.repo, config.issue_number, None)
    if not comments:
        state = RelayState()
    else:
        newest = max(comments, key=_comment_id)
        state = RelayState(
            last_comment_id=_comment_id(newest),
            last_seen_at=_comment_time(newest),
            processed_request_ids=[],
        )
    save_state(state)
    return state


def _advance_state(state: RelayState, comment: dict[str, Any]) -> None:
    cid = _comment_id(comment)
    if cid >= state.last_comment_id:
        state.last_comment_id = cid
        state.last_seen_at = _comment_time(comment)


def run_once(
    config: RelayConfig,
    github: GitHubClient,
    bridge: BridgeClient,
    state: RelayState,
) -> int:
    comments = github.comments(config.repo, config.issue_number, state.last_seen_at)
    pending = [
        item for item in comments
        if _comment_id(item) > state.last_comment_id
    ]
    pending.sort(key=_comment_id)
    handled = 0

    for comment in pending:
        cid = _comment_id(comment)
        body = str(comment.get("body") or "")
        author = str((comment.get("user") or {}).get("login") or "")

        if not body.startswith(REQUEST_MARKER):
            _advance_state(state, comment)
            save_state(state)
            continue

        if author.casefold() != config.allowed_author.casefold():
            _advance_state(state, comment)
            save_state(state)
            continue

        request_data: RemoteRequest | None = None
        try:
            request_data = parse_request_comment(body)
            if request_data is None:
                _advance_state(state, comment)
                save_state(state)
                continue
        except ProtocolError as exc:
            github.post_comment(
                config.repo,
                config.issue_number,
                result_comment(
                    None,
                    status="protocol_error",
                    source_comment_id=cid,
                    error=str(exc),
                ),
            )
            _advance_state(state, comment)
            save_state(state)
            handled += 1
            continue

        if request_data.request_id in set(state.processed_request_ids or []):
            github.post_comment(
                config.repo,
                config.issue_number,
                result_comment(
                    request_data,
                    status="duplicate_ignored",
                    source_comment_id=cid,
                    error="requestId was already completed",
                ),
            )
            _advance_state(state, comment)
            save_state(state)
            handled += 1
            continue

        result = bridge.execute(request_data)
        github.post_comment(
            config.repo,
            config.issue_number,
            result_comment(
                request_data,
                status="tool_result",
                source_comment_id=cid,
                result=result,
            ),
        )
        state.remember(request_data.request_id)
        _advance_state(state, comment)
        save_state(state)
        handled += 1

    return handled


def configure(args: argparse.Namespace) -> None:
    repo = str(args.repo or "").strip()
    author = str(args.author or "").strip()
    if not REPO_RE.fullmatch(repo):
        raise RelayError("--repo must be owner/name")
    if int(args.issue) < 1:
        raise RelayError("--issue must be >= 1")
    if not author:
        raise RelayError("--author is required")

    token = os.environ.get("LUMENA_GITHUB_TOKEN", "").strip()
    if not token:
        token = getpass.getpass(
            "Fine-grained GitHub token (private mailbox repo: Metadata read + Issues read/write): "
        ).strip()
    if not token:
        raise RelayError("GitHub token is required")

    config = RelayConfig(
        repo=repo,
        issue_number=int(args.issue),
        allowed_author=author,
        poll_seconds=int(args.poll_seconds),
    )
    github = GitHubClient(token)
    verify_mailbox(config, github)

    _store_secret(GITHUB_TOKEN_FILE, token)
    _atomic_write_json(CONFIG_FILE, config.to_dict())
    state = bootstrap_state(config, github)

    print("LUMENA_REMOTE_CONFIGURED")
    print(f"repo={config.repo}")
    print(f"issue={config.issue_number}")
    print(f"allowed_author={config.allowed_author}")
    print(f"last_comment_id={state.last_comment_id}")
    print("Only PUBLIC_WEB read-only tools are remotely executable.")
    print("Start: python ~/.lumena/remote_relay.py --daemon")


def status(config: RelayConfig, github: GitHubClient) -> None:
    verify_mailbox(config, github)
    state = load_state()
    print("LUMENA_REMOTE_STATUS")
    print(f"version={VERSION}")
    print(f"repo={config.repo}")
    print(f"issue={config.issue_number}")
    print(f"allowed_author={config.allowed_author}")
    print(f"last_comment_id={state.last_comment_id}")
    print(f"last_seen_at={state.last_seen_at}")
    print(f"processed_request_ids={len(state.processed_request_ids or [])}")
    print("allowed_tools=" + ",".join(sorted(REMOTE_PUBLIC_WEB_TOOLS)))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Lumena private GitHub remote relay")
    parser.add_argument("--configure", action="store_true", help="write/verify relay configuration")
    parser.add_argument("--repo", help="private mailbox repository owner/name")
    parser.add_argument("--issue", type=int, help="open mailbox issue number")
    parser.add_argument("--author", help="only GitHub login allowed to submit requests")
    parser.add_argument("--poll-seconds", type=int, default=8)
    parser.add_argument("--once", action="store_true", help="poll and execute one batch")
    parser.add_argument("--daemon", action="store_true", help="poll continuously")
    parser.add_argument("--status", action="store_true", help="verify and show relay status")
    return parser


def main(argv: Iterable[str] | None = None) -> int:
    args = build_parser().parse_args(list(argv) if argv is not None else None)
    try:
        if args.configure:
            if not (args.repo and args.issue and args.author):
                raise RelayError("--configure requires --repo, --issue and --author")
            configure(args)
            return 0

        config = load_config()
        github_token = _load_secret(GITHUB_TOKEN_FILE, "LUMENA_GITHUB_TOKEN")
        bridge_token = _load_secret(BRIDGE_TOKEN_FILE, "LUMENA_BRIDGE_TOKEN")
        github = GitHubClient(github_token)
        verify_mailbox(config, github)

        if args.status:
            status(config, github)
            return 0

        if not STATE_FILE.exists():
            state = bootstrap_state(config, github)
            print(
                "Relay state initialized at the newest existing comment; "
                "no old request was executed."
            )
        else:
            state = load_state()

        bridge = BridgeClient(config.bridge_url, bridge_token)

        if args.once:
            count = run_once(config, github, bridge, state)
            print(f"LUMENA_REMOTE_ONCE handled={count}")
            return 0

        if not args.daemon:
            raise RelayError("choose --once, --daemon, --status or --configure")

        print(
            f"Lumena Remote Relay v{VERSION} · private mailbox "
            f"{config.repo}#{config.issue_number} · poll={config.poll_seconds}s"
        )
        print("Remote authority: PUBLIC_WEB read-only only.")
        while True:
            try:
                count = run_once(config, github, bridge, state)
                if count:
                    print(f"handled={count} at {datetime.now(timezone.utc).isoformat(timespec='seconds')}")
            except KeyboardInterrupt:
                return 0
            except Exception as exc:
                print(f"relay_error={type(exc).__name__}: {exc}", file=sys.stderr)
            time.sleep(config.poll_seconds)
    except RelayError as exc:
        print(f"LUMENA_REMOTE_ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
