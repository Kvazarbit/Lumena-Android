import importlib.util
import json
import sys
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).with_name("remote_relay.py")
SPEC = importlib.util.spec_from_file_location("lumena_remote_relay", MODULE_PATH)
relay = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = relay
SPEC.loader.exec_module(relay)


class FakeGitHub:
    def __init__(self, comments=None, private=True):
        self._comments = list(comments or [])
        self.private = private
        self.posted = []

    def repo(self, _repo):
        return {"private": self.private}

    def issue(self, _repo, _issue):
        return {"state": "open"}

    def comments(self, _repo, _issue, _since=None):
        return list(self._comments)

    def post_comment(self, repo, issue, body):
        self.posted.append((repo, issue, body))
        return {"id": 999}


class FakeBridge:
    def __init__(self):
        self.requests = []

    def execute(self, request):
        self.requests.append(request)
        return {
            "ok": True,
            "exitCode": 0,
            "stdout": json.dumps({"echo": request.args}, ensure_ascii=False),
            "stderr": "",
            "error": None,
        }


def request_body(request_id="r1", tool="web.search", args=None):
    payload = {
        "requestId": request_id,
        "tool": tool,
        "args": args or {"query": "EPYC Rome DDR4 ECC"},
    }
    return relay.REQUEST_MARKER + "\n" + json.dumps(payload, ensure_ascii=False)


class RemoteRelayProtocolTest(unittest.TestCase):
    def test_valid_public_web_request(self):
        parsed = relay.parse_request_comment(
            request_body(
                request_id="search-1",
                tool="web.search",
                args={"query": "EPYC 7402P", "limit": 5},
            )
        )
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed.request_id, "search-1")
        self.assertEqual(parsed.tool, "web.search")
        self.assertEqual(parsed.args["limit"], 5)

    def test_optional_tilde_fence_is_accepted(self):
        payload = json.dumps({
            "requestId": "read-1",
            "tool": "web.read",
            "args": {"url": "https://example.com"},
        })
        parsed = relay.parse_request_comment(
            relay.REQUEST_MARKER + "\n~~~json\n" + payload + "\n~~~"
        )
        self.assertEqual(parsed.tool, "web.read")

    def test_mutating_and_local_tools_are_blocked(self):
        for tool in (
            "file.read",
            "git.status",
            "system.info",
            "inspect.batch",
            "python.run",
            "ollama.generate",
            "file.write",
        ):
            with self.subTest(tool=tool):
                with self.assertRaises(relay.ProtocolError):
                    relay.parse_request_comment(request_body(tool=tool))

    def test_nested_args_are_rejected(self):
        with self.assertRaises(relay.ProtocolError):
            relay.parse_request_comment(
                request_body(args={"query": {"nested": "bad"}})
            )

    def test_unexpected_top_level_fields_are_rejected(self):
        raw = {
            "requestId": "r1",
            "tool": "web.search",
            "args": {"query": "x"},
            "approval": True,
        }
        with self.assertRaises(relay.ProtocolError):
            relay.parse_request_comment(
                relay.REQUEST_MARKER + "\n" + json.dumps(raw)
            )

    def test_public_mailbox_is_rejected(self):
        config = relay.RelayConfig(
            repo="owner/private-mailbox",
            issue_number=1,
            allowed_author="owner",
        )
        with self.assertRaises(relay.RelayError):
            relay.verify_mailbox(config, FakeGitHub(private=False))

    def test_authorized_request_executes_once_and_posts_result(self):
        comments = [
            {
                "id": 10,
                "body": request_body("r10"),
                "user": {"login": "Kvazarbit"},
                "created_at": "2026-09-23T20:00:00Z",
                "updated_at": "2026-09-23T20:00:00Z",
            },
            {
                "id": 11,
                "body": request_body("r11"),
                "user": {"login": "attacker"},
                "created_at": "2026-09-23T20:00:01Z",
                "updated_at": "2026-09-23T20:00:01Z",
            },
        ]
        github = FakeGitHub(comments)
        bridge = FakeBridge()
        config = relay.RelayConfig(
            repo="Kvazarbit/Lumena-Relay",
            issue_number=1,
            allowed_author="Kvazarbit",
        )
        state = relay.RelayState()

        with mock.patch.object(relay, "save_state", lambda _state: None):
            handled = relay.run_once(config, github, bridge, state)

        self.assertEqual(handled, 1)
        self.assertEqual(len(bridge.requests), 1)
        self.assertEqual(bridge.requests[0].request_id, "r10")
        self.assertEqual(len(github.posted), 1)
        self.assertTrue(github.posted[0][2].startswith(relay.RESULT_MARKER))
        self.assertIn("r10", state.processed_request_ids)
        self.assertEqual(state.last_comment_id, 11)

    def test_duplicate_request_id_never_reexecutes(self):
        comments = [
            {
                "id": 20,
                "body": request_body("same-id"),
                "user": {"login": "Kvazarbit"},
                "created_at": "2026-09-23T20:01:00Z",
                "updated_at": "2026-09-23T20:01:00Z",
            }
        ]
        github = FakeGitHub(comments)
        bridge = FakeBridge()
        config = relay.RelayConfig(
            repo="Kvazarbit/Lumena-Relay",
            issue_number=1,
            allowed_author="Kvazarbit",
        )
        state = relay.RelayState(processed_request_ids=["same-id"])

        with mock.patch.object(relay, "save_state", lambda _state: None):
            handled = relay.run_once(config, github, bridge, state)

        self.assertEqual(handled, 1)
        self.assertEqual(bridge.requests, [])
        self.assertEqual(len(github.posted), 1)
        self.assertIn("duplicate_ignored", github.posted[0][2])

    def test_result_output_is_bounded(self):
        req = relay.RemoteRequest("r", "web.search", {"query": "x"})
        text = relay.result_comment(
            req,
            status="tool_result",
            source_comment_id=1,
            result={"ok": True, "stdout": "x" * 100_000, "stderr": "", "error": None},
        )
        self.assertLess(len(text), 30_000)
        self.assertIn("remote relay truncated", text)


if __name__ == "__main__":
    unittest.main()
