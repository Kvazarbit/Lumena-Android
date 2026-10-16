"""A paired bridge runs MUTATING/EXECUTABLE tools only with a signed approval.

Threat model: this stops a bearer-token holder WITHOUT code execution in
Termux (another Android app calling 127.0.0.1:8765, a leaked token). Code that
already runs as the Termux uid can rewrite ~/.lumena/approval_pubkey or
bridge.py and is out of scope. This is not a sandbox.
"""
import base64
import hashlib
import importlib.util
import json
import os
import secrets
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest.mock import patch


TOKEN = "fixture-approval-token-0123456789"

# Test-only P-256 signer. Plain affine arithmetic, written separately from the
# bridge's Jacobian verifier so that one shared bug cannot make both agree.
P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
A = P - 3
N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
G = (
    0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296,
    0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5,
)
SPKI_PREFIX = bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d030107034200")


def _add(p1, p2):
    if p1 is None:
        return p2
    if p2 is None:
        return p1
    if p1[0] == p2[0] and (p1[1] + p2[1]) % P == 0:
        return None
    if p1 == p2:
        slope = (3 * p1[0] * p1[0] + A) * pow(2 * p1[1], -1, P) % P
    else:
        slope = (p2[1] - p1[1]) * pow(p2[0] - p1[0], -1, P) % P
    x = (slope * slope - p1[0] - p2[0]) % P
    return x, (slope * (p1[0] - x) - p1[1]) % P


def _mul(k, point):
    result = None
    while k:
        if k & 1:
            result = _add(result, point)
        point = _add(point, point)
        k >>= 1
    return result


def _der_int(value):
    body = value.to_bytes((value.bit_length() + 8) // 8, "big")
    return b"\x02" + bytes([len(body)]) + body


def sign_for_test(d, message):
    e = int.from_bytes(hashlib.sha256(message).digest(), "big")
    while True:
        k = secrets.randbelow(N - 1) + 1
        r = _mul(k, G)[0] % N
        s = pow(k, -1, N) * (e + r * d) % N
        if r and s:
            body = _der_int(r) + _der_int(s)
            return b"\x30" + bytes([len(body)]) + body


def spki_for_test(d):
    x, y = _mul(d, G)
    return SPKI_PREFIX + b"\x04" + x.to_bytes(32, "big") + y.to_bytes(32, "big")


def _scalar(label):
    return int.from_bytes(hashlib.sha256(label).digest(), "big") % N


KEY = _scalar(b"lumena-test-approval-key")
OTHER_KEY = _scalar(b"lumena-test-other-key")

# Cross-language vector. SignedApprovalTest.kt asserts the same bytes, and
# Java's SHA256withECDSA must accept the same signature.
VECTOR_TOOL = "file.write"
VECTOR_ARGS = {
    "path": "notes/привіт.txt",
    "content": "line1\nline2 ✓",
    "mode": "",
    # UTF-16 order puts the emoji first; UTF-8 byte order puts U+FF5E first.
    "\U0001F600": "utf16-first",
    "～": "utf8-first",
}
VECTOR_NONCE = "AAECAwQFBgcICQoLDA0ODw"
VECTOR_ISSUED_AT = 1760000000000
VECTOR_EXPIRES_AT = 1760000060000
VECTOR_ARGS_SHA256 = "7804638486a26d9b7d6d239b41090a1e6b13b5345eb28da4d3293bb80ba0d739"
VECTOR_MESSAGE_HEX = (
    "000000126c756d656e612d617070726f76616c2d76310000000a66696c652e777269746500000040"
    "37383034363338343836613236643962376436643233396234313039306131653662313362353334"
    "3565623238646134643332393362623830626130643733390000001641414543417751464267634943"
    "516f4c4441304f44770000000d313736303030303030303030300000000d31373630303030303630303030"
)
VECTOR_MESSAGE_SHA256 = "ef44b4f1b6ac9a09280734db8d0e8189ab1ec5c422a2c21fcd8d896fc62e643e"
# Signed once by an independent implementation (pyca/cryptography).
VECTOR_SPKI_B64 = (
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEhMc4xWrLNDiH5Q8h64PLKpfaTn8mrYpVslignErsQDz6"
    "3O0sql4YisCBvIRiJAefiXlMDocO1XaqWKsYbvDpMg=="
)
VECTOR_SIG_B64URL = (
    "MEYCIQDW1Fg_z6UvpWr0UICeUIavZ5D5B3U1bkkMC4H91MXN8gIhANMujUPX6rtAv67dOAUi0cqd7_Te"
    "exwOrqHponhaNUYS"
)


def _b64url(data):
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


class SignedApprovalsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lumena-approvals-")
        root = Path(self.temp.name)
        self.workspace = root / "workspace"
        self.workspace.mkdir()
        self.pubkey = root / "approval_pubkey"
        self.env = patch.dict(
            os.environ,
            {
                "LUMENA_WORKSPACE": str(self.workspace),
                "LUMENA_READONLY_ROOTS": "",
                "LUMENA_READ_ROOTS_FILE": str(root / "read_roots.conf"),
                "LUMENA_MCP_SEARCH_CONFIG": str(root / "mcp_search_providers.json"),
                "LUMENA_BRIDGE_TOKEN": TOKEN,
                "LUMENA_APPROVAL_PUBKEY": str(self.pubkey),
                "PYTHONIOENCODING": "utf-8",
            },
        )
        self.env.start()
        self.addCleanup(self.env.stop)
        self.addCleanup(self.temp.cleanup)
        spec = importlib.util.spec_from_file_location(
            "signed_approvals_bridge",
            Path(__file__).with_name("bridge.py"),
        )
        self.b = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.b)
        if not hasattr(self.b.signal, "SIGKILL"):
            # The bridge targets Termux/POSIX; this lets the test also run
            # on a Windows dev host, where run_process reads SIGKILL.
            shim = patch.object(self.b.signal, "SIGKILL", 9, create=True)
            shim.start()
            self.addCleanup(shim.stop)
        self.server = self.b.ThreadingHTTPServer(("127.0.0.1", 0), self.b.Handler)
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join, 5)
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)

    def pair(self, d=KEY):
        spki = spki_for_test(d)
        self.pubkey.write_text("ecdsa-p256 " + base64.b64encode(spki).decode("ascii") + "\n", encoding="ascii")
        return spki

    def approval(self, tool, args, d=KEY, *, issued=None, ttl=60_000, nonce=None):
        issued = int(time.time() * 1000) if issued is None else issued
        nonce = nonce or _b64url(secrets.token_bytes(16))
        message = self.b.approval_message(tool, self.b.approval_args_sha256(args), nonce, issued, issued + ttl)
        return {"nonce": nonce, "issuedAt": issued, "expiresAt": issued + ttl, "sig": _b64url(sign_for_test(d, message))}

    def post(self, tool, args, approval=None, token=TOKEN):
        body = {"tool": tool, "args": args}
        if approval is not None:
            body["approval"] = approval
        request = urllib.request.Request(
            f"http://127.0.0.1:{self.server.server_address[1]}/tool",
            data=json.dumps(body).encode("utf-8"),
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            try:
                return exc.code, json.loads(exc.read().decode("utf-8"))
            finally:
                exc.close()

    def health(self):
        status, body = self.post("health", {})
        self.assertEqual(200, status, body)
        return dict(line.split("=", 1) for line in body["stdout"].splitlines() if "=" in line)

    # --- cross-language vector -------------------------------------------

    def test_cross_language_vector_matches_kotlin(self):
        args_sha = self.b.approval_args_sha256(VECTOR_ARGS)
        self.assertEqual(VECTOR_ARGS_SHA256, args_sha)
        message = self.b.approval_message(VECTOR_TOOL, args_sha, VECTOR_NONCE, VECTOR_ISSUED_AT, VECTOR_EXPIRES_AT)
        self.assertEqual(VECTOR_MESSAGE_HEX, message.hex())
        self.assertEqual(VECTOR_MESSAGE_SHA256, hashlib.sha256(message).hexdigest())

    def test_independently_signed_vector_verifies_and_tampering_does_not(self):
        point = self.b.p256_point_from_spki(base64.b64decode(VECTOR_SPKI_B64))
        signature = base64.urlsafe_b64decode(VECTOR_SIG_B64URL + "=" * (-len(VECTOR_SIG_B64URL) % 4))
        message = bytes.fromhex(VECTOR_MESSAGE_HEX)
        self.assertTrue(self.b.p256_verify(point, message, signature))
        self.assertFalse(self.b.p256_verify(point, message[:-1] + b"1", signature))
        other = self.b.p256_point_from_spki(spki_for_test(OTHER_KEY))
        self.assertFalse(self.b.p256_verify(other, message, signature))

    def test_public_key_must_be_a_point_on_p256(self):
        spki = bytearray(spki_for_test(KEY))
        spki[-1] ^= 1
        with self.assertRaises(ValueError):
            self.b.p256_point_from_spki(bytes(spki))

    # --- bridge states -----------------------------------------------------

    def test_unpaired_bridge_keeps_working_token_only(self):
        health = self.health()
        self.assertEqual("unpaired", health.get("approval_signing"))
        self.assertIn("signed_approvals_v1", health.get("features", "").split(","))
        status, body = self.post("dir.create", {"path": "made-unpaired"})
        self.assertEqual(200, status, body)
        self.assertTrue((self.workspace / "made-unpaired").is_dir())

    def test_paired_valid_approval_passes_once_and_replay_is_refused(self):
        spki = self.pair()
        health = self.health()
        self.assertEqual("paired", health.get("approval_signing"))
        self.assertEqual(hashlib.sha256(spki).hexdigest()[:16], health.get("approval_key_fingerprint"))

        args = {"path": "made-once"}
        approval = self.approval("dir.create", args)
        status, body = self.post("dir.create", args, approval)
        self.assertEqual(200, status, body)
        self.assertTrue((self.workspace / "made-once").is_dir())

        (self.workspace / "made-once").rmdir()
        status, body = self.post("dir.create", args, approval)
        self.assertEqual(403, status, body)
        self.assertEqual("APPROVAL_INVALID", body.get("errorCode"))
        self.assertIn("already used", body.get("error", ""))
        self.assertFalse((self.workspace / "made-once").exists())

    def test_paired_missing_approval_is_refused(self):
        self.pair()
        status, body = self.post("file.write", {"path": "x.txt", "content": "unsigned"})
        self.assertEqual(403, status, body)
        self.assertEqual("APPROVAL_REQUIRED", body.get("errorCode"))
        self.assertFalse(body.get("ok"))
        self.assertFalse((self.workspace / "x.txt").exists())

    def test_expired_approval_is_refused(self):
        self.pair()
        self.b.BRIDGE_STARTED_AT_MS -= 600_000
        args = {"path": "late"}
        approval = self.approval("dir.create", args, issued=int(time.time() * 1000) - 61_000, ttl=60_000)
        status, body = self.post("dir.create", args, approval)
        self.assertEqual(403, status, body)
        self.assertIn("expired", body.get("error", ""))
        self.assertFalse((self.workspace / "late").exists())

    def test_long_lived_approval_is_refused(self):
        self.pair()
        args = {"path": "long"}
        status, body = self.post("dir.create", args, self.approval("dir.create", args, ttl=121_000))
        self.assertEqual(403, status, body)
        self.assertIn("lifetime", body.get("error", ""))
        self.assertFalse((self.workspace / "long").exists())

    def test_approval_issued_before_bridge_start_is_refused(self):
        self.pair()
        args = {"path": "old"}
        approval = self.approval("dir.create", args, issued=self.b.BRIDGE_STARTED_AT_MS - 1_000)
        status, body = self.post("dir.create", args, approval)
        self.assertEqual(403, status, body)
        self.assertIn("before this bridge started", body.get("error", ""))
        self.assertFalse((self.workspace / "old").exists())

    def test_wrong_key_is_refused(self):
        self.pair(KEY)
        args = {"path": "foreign"}
        status, body = self.post("dir.create", args, self.approval("dir.create", args, d=OTHER_KEY))
        self.assertEqual(403, status, body)
        self.assertIn("signature", body.get("error", ""))
        self.assertFalse((self.workspace / "foreign").exists())

    def test_tampered_args_and_tool_are_refused(self):
        self.pair()
        approval = self.approval("file.write", {"path": "a.txt", "content": "safe"})
        status, body = self.post("file.write", {"path": "a.txt", "content": "evil"}, approval)
        self.assertEqual(403, status, body)
        self.assertFalse((self.workspace / "a.txt").exists())

        approval = self.approval("dir.create", {"path": "b"})
        status, body = self.post("project.create", {"path": "b"}, approval)
        self.assertEqual(403, status, body)
        self.assertIn("signature", body.get("error", ""))

    def test_non_string_args_are_refused_when_paired(self):
        self.pair()
        approval = self.approval("dir.create", {"path": "n"})
        status, body = self.post("dir.create", {"path": "n", "depth": 3}, approval)
        self.assertEqual(403, status, body)
        self.assertFalse((self.workspace / "n").exists())

    def test_executable_tool_needs_approval_when_paired(self):
        self.pair()
        (self.workspace / "hello.py").write_text("print('signed run')\n", encoding="utf-8")
        args = {"script": "hello.py"}
        status, body = self.post("python.run", args)
        self.assertEqual(403, status, body)
        status, body = self.post("python.run", args, self.approval("python.run", args))
        self.assertEqual(200, status, body)
        self.assertIn("signed run", body.get("stdout", ""))

    def test_read_only_tools_stay_token_only_when_paired(self):
        self.pair()
        for tool, args in (
            ("health", {}),
            ("system.time", {}),
            ("workspace.list", {}),
            ("file.list", {}),
            ("inspect.batch", {"requests": json.dumps([{"tool": "system.time", "args": {}}])}),
        ):
            status, body = self.post(tool, args)
            self.assertEqual(200, status, f"{tool}: {body}")

    def test_invalid_key_file_fails_closed(self):
        self.pubkey.write_text("ecdsa-p256 not-base64!!\n", encoding="ascii")
        self.assertEqual("invalid_key", self.health().get("approval_signing"))
        status, body = self.post("dir.create", {"path": "closed"})
        self.assertEqual(403, status, body)
        self.assertEqual("APPROVAL_KEY_INVALID", body.get("errorCode"))
        self.assertFalse((self.workspace / "closed").exists())
        status, _ = self.post("system.time", {})
        self.assertEqual(200, status)

    def test_approval_does_not_replace_the_token(self):
        self.pair()
        args = {"path": "no-token"}
        status, _ = self.post("dir.create", args, self.approval("dir.create", args), token="wrong")
        self.assertEqual(401, status)
        self.assertFalse((self.workspace / "no-token").exists())


if __name__ == "__main__":
    unittest.main()
