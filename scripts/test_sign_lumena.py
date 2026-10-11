"""Offline security regression tests for sign_lumena.py."""
import base64
import hashlib
import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import sign_lumena as signer


def make_zip(members):
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in members:
            z.writestr(name, data)
    return b.getvalue()


class SignLumenaTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.run_id = 38171667500
        self.sha = "09f2321188a73d616a36cdd898af78276575898b"
        self.number = 2136
        self.run = {
            "id": self.run_id,
            "repository": {"id": signer.REPO_ID, "full_name": signer.REPO},
            "head_repository": {"id": signer.REPO_ID, "full_name": signer.REPO},
            "workflow_id": signer.WORKFLOW_ID,
            "path": signer.WORKFLOW_PATH,
            "status": "completed", "conclusion": "success", "event": "pull_request",
            "head_sha": self.sha, "head_branch": "fix/ci2131-file-evidence-recovery-v1",
            "run_number": self.number,
            "pull_requests": [{"number": 113}],
        }
        self.pr = {"head": {"sha": self.sha, "repo": {"id": signer.REPO_ID}},
                   "base": {"repo": {"id": signer.REPO_ID}}}
        self.jar = b"test-only-signer-jar"
        self.apk = make_zip([("AndroidManifest.xml", b"manifest"),
                             ("lib/arm64-v8a/liblumena_llama.so", b"native")])
        self.archive_bytes = make_zip([("app-release-unsigned.apk", self.apk),
                                       ("apksigner.jar", self.jar)])
        self.archive = self.root / "signing-input.zip"
        self.archive.write_bytes(self.archive_bytes)
        self.artifact = {
            "name": f"UNSIGNED-Lumena-ci{self.number}-signing-input",
            "expired": False,
            "digest": "sha256:" + hashlib.sha256(self.archive_bytes).hexdigest(),
            "workflow_run": {"head_sha": self.sha, "head_branch": self.run["head_branch"]},
        }

    def fetch(self, resource):
        if resource == "/actions/runs/" + str(self.run_id):
            return self.run
        if resource == "/pulls/113":
            return self.pr
        if resource == "/actions/runs/" + str(self.run_id) + "/artifacts?per_page=100":
            return {"artifacts": [self.artifact]}
        raise AssertionError(resource)

    def test_real_api_route_allows_bounded_artifact_pagination(self):
        """Regression: the legitimate ?per_page=100 query must not be rejected."""
        payloads = {
            "/actions/runs/" + str(self.run_id): self.run,
            "/pulls/113": self.pr,
            "/actions/runs/" + str(self.run_id) + "/artifacts?per_page=100":
                {"artifacts": [self.artifact]},
        }
        base = "https://api.github.com/repos/" + signer.REPO

        class Response:
            def __init__(self, url, payload):
                self.url = url
                self.payload = json.dumps(payload).encode("utf-8")

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def geturl(self):
                return self.url

            def read(self, limit):
                return self.payload[:limit]

        def fake_urlopen(request, timeout):
            self.assertEqual(timeout, 20)
            url = request.full_url
            self.assertTrue(url.startswith(base))
            resource = url[len(base):]
            self.assertIn(resource, payloads)
            return Response(url, payloads[resource])

        with patch.object(signer.urllib.request, "urlopen", side_effect=fake_urlopen) as hit:
            run, artifact = signer.trusted_run(self.run_id, allow_pr=True)
            self.assertEqual(self.sha, run["head_sha"])
            self.assertEqual(self.artifact, artifact)
            self.assertEqual(3, hit.call_count)
            for malicious in (
                "/actions/runs/1/artifacts?per_page=101",
                "/actions/runs/1/artifacts?redirect=https://example.net",
                "/actions/runs/../artifacts?per_page=100",
                "/actions/runs/1/artifacts?per_page=100&evil=1",
                "//other-host/path",
            ):
                with self.assertRaisesRegex(signer.SigningError, "Invalid GitHub API resource"):
                    signer.github_json(malicious)
            self.assertEqual(3, hit.call_count)

    def test_trusted_same_repo_pr_requires_explicit_consent(self):
        with self.assertRaisesRegex(signer.SigningError, "allow-pr"):
            signer.trusted_run(self.run_id, allow_pr=False, fetch=self.fetch)
        run, artifact = signer.trusted_run(self.run_id, allow_pr=True, fetch=self.fetch)
        self.assertEqual(self.sha, run["head_sha"])
        self.assertEqual(self.artifact, artifact)

    def test_untrusted_repository_workflow_or_pr_refusal(self):
        for bad_field in ("repository", "head_repository", "workflow_id", "path", "status", "conclusion"):
            original = self.run[bad_field]
            try:
                self.run[bad_field] = {"id": 5} if isinstance(original, dict) else "wrong"
                with self.assertRaises(signer.SigningError, msg=bad_field):
                    signer.trusted_run(self.run_id, allow_pr=True, fetch=self.fetch)
            finally:
                self.run[bad_field] = original
        old = self.pr["head"]
        try:
            self.pr["head"] = {"sha": "0" * 40, "repo": {"id": signer.REPO_ID}}
            with self.assertRaisesRegex(signer.SigningError, "PR head"):
                signer.trusted_run(self.run_id, allow_pr=True, fetch=self.fetch)
        finally:
            self.pr["head"] = old

    def test_digest_omission_or_artifact_fork_blocks_signing(self):
        original = self.artifact["digest"]
        try:
            self.artifact["digest"] = "sha256:"
            with self.assertRaises(signer.SigningError):
                signer.trusted_run(self.run_id, allow_pr=True, fetch=self.fetch)
        finally:
            self.artifact["digest"] = original
        original = self.artifact["workflow_run"]
        try:
            self.artifact["workflow_run"] = {"head_sha": "b" * 40}
            with self.assertRaises(signer.SigningError):
                signer.trusted_run(self.run_id, allow_pr=True, fetch=self.fetch)
        finally:
            self.artifact["workflow_run"] = original

    def test_archive_hash_and_signer_pin(self):
        pin = hashlib.sha256(self.jar).hexdigest()
        with patch.object(signer, "APKSIGNER_SHA256", pin):
            apk, jar = signer.verified_archive(self.archive, self.artifact["digest"][7:])
            self.assertEqual(self.apk, apk)
            self.assertEqual(self.jar, jar)
            with self.assertRaisesRegex(signer.SigningError, "SHA-256 disagrees"):
                signer.verified_archive(self.archive, "f" * 64)
        with self.assertRaisesRegex(signer.SigningError, "apksigner.jar SHA-256"):
            signer.verified_archive(self.archive, self.artifact["digest"][7:])

    def test_missing_arm64_or_duplicate_apk_rejected(self):
        for apk in (make_zip([("AndroidManifest.xml", b"bad")]),
                    make_zip([("lib/arm64-v8a/liblumena_llama.so", b"bad")])):
            blob = make_zip([("app-release-unsigned.apk", apk), ("apksigner.jar", self.jar)])
            self.archive.write_bytes(blob)
            with patch.object(signer, "APKSIGNER_SHA256", hashlib.sha256(self.jar).hexdigest()):
                with self.assertRaisesRegex(signer.SigningError, "Not an expected"):
                    signer.verified_archive(self.archive, hashlib.sha256(blob).hexdigest())
        blob = make_zip([("app-release-unsigned.apk", self.apk),
                         ("sub/app-release-unsigned.apk", self.apk),
                         ("apksigner.jar", self.jar)])
        self.archive.write_bytes(blob)
        with patch.object(signer, "APKSIGNER_SHA256", hashlib.sha256(self.jar).hexdigest()):
            with self.assertRaisesRegex(signer.SigningError, "exactly one"):
                signer.verified_archive(self.archive, hashlib.sha256(blob).hexdigest())

    def test_aapt_is_required_and_checks_version_and_package(self):
        apk = self.root / "unsigned.apk"
        apk.write_bytes(self.apk)
        with patch.object(signer.shutil, "which", return_value=None):
            with self.assertRaisesRegex(signer.SigningError, "aapt"):
                signer.apk_metadata(apk, self.number)
        def metadata_reply(line):
            with patch.object(signer.shutil, "which", side_effect=lambda x: "/bin/aapt" if x == "aapt" else None), \
                 patch.object(signer, "run_checked", return_value=line.encode()):
                return signer.apk_metadata(apk, self.number)
        ok = "package: name='com.lumena.android' versionCode='56' versionName='0.12.30-ci2136'"
        self.assertEqual(("0.12.30-ci2136", 56), metadata_reply(ok))
        for invalid in (ok.replace("com.lumena.android", "com.untrusted.app"),
                        ok.replace("ci2136", "ci2131"), ok.replace("versionCode='56'", "versionCode='0'")):
            with self.assertRaisesRegex(signer.SigningError, "identity/version"):
                metadata_reply(invalid)

    def test_private_key_never_used_in_verify_only(self):
        with patch.object(signer, "trusted_run", return_value=(self.run, self.artifact)), \
             patch.object(signer, "verified_archive", return_value=(self.apk, self.jar)), \
             patch.object(signer, "apk_metadata", return_value=("0.12.30-ci2136", 56)), \
             patch.object(signer, "load_owner_backup", side_effect=AssertionError("private backup opened")):
            self.assertEqual(0, signer.main(["--run-id", str(self.run_id), "--allow-pr", "--verify-only"]))

    def test_owner_signs_without_overwriting_or_leaking_github_token(self):
        private = self.root / "backup.zip"
        password = "SHOULD_NOT_BE_PRINTED"
        bundle = {"alias": "lumena", "password": password,
                  "keystore_base64": base64.b64encode(b"fake-pkcs12").decode()}
        with zipfile.ZipFile(private, "w") as z:
            z.writestr("LUMENA_SIGNING_BUNDLE.json", json.dumps(bundle))
        result = self.root / "signed.apk"
        fake_cert = b"fake-owner-cert"
        pin = hashlib.sha256(fake_cert).hexdigest()
        seen_env = []

        def fake_run(command, *, env=None, stdout=False):
            seen_env.append(env or {})
            if command[0] == "keytool":
                return fake_cert
            if "sign" in command:
                output = Path(command[command.index("--out") + 1])
                source = Path(command[-1])
                shutil.copyfile(source, output)
                return b""
            if "verify" in command:
                return ("Signer #1 certificate SHA-256 digest: " + pin).encode()
            raise AssertionError(command)

        with patch.object(signer, "CERT_SHA256", pin), \
             patch.object(signer, "run_checked", side_effect=fake_run), \
             patch.object(signer, "apk_metadata", return_value=("0.12.30-ci2136", 56)), \
             patch.dict("os.environ", {"GITHUB_TOKEN": "PRIVATE_GH_TOKEN"}):
            digest = signer.sign_and_verify(self.apk, self.jar, private, result, 2136, "0.12.30-ci2136")
            self.assertTrue(result.is_file())
            self.assertEqual(hashlib.sha256(self.apk).hexdigest(), digest)
            self.assertTrue(all("GITHUB_TOKEN" not in x for x in seen_env))
            with self.assertRaisesRegex(signer.SigningError, "Refusing to overwrite"):
                signer.sign_and_verify(self.apk, self.jar, private, result, 2136, "0.12.30-ci2136")


if __name__ == "__main__":
    unittest.main()
