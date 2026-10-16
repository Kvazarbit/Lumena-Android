"""Offline security regression tests for sign_lumena.py."""
import base64
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

import sign_lumena as signer


def make_zip(members):
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in members:
            z.writestr(name, data)
    return b.getvalue()


PASSPHRASE = "correct horse battery staple"
FAKE_PASSWORD = "SHOULD_NOT_BE_PRINTED"
FAKE_KEYSTORE = b"fake-pkcs12-keystore-material"


def fake_backup_zip(password=FAKE_PASSWORD, keystore=FAKE_KEYSTORE):
    """An owner backup stored uncompressed, so a plaintext leak is byte-visible."""
    bundle = {"alias": "lumena", "password": password,
              "keystore_base64": base64.b64encode(keystore).decode()}
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w", zipfile.ZIP_STORED) as z:
        z.writestr("LUMENA_SIGNING_BUNDLE.json", json.dumps(bundle))
    return b.getvalue()


def passphrases(*answers):
    """Scripted stand-in for getpass; fails on any unexpected extra prompt."""
    queue = list(answers)

    def read(prompt):
        read.prompts.append(prompt)
        if not queue:
            raise AssertionError("unexpected passphrase prompt: " + prompt)
        return queue.pop(0)

    read.prompts = []
    return read


def key_material(password=FAKE_PASSWORD, keystore=FAKE_KEYSTORE):
    return (password.encode(), keystore, base64.b64encode(keystore),
            b"LUMENA_SIGNING_BUNDLE", b"PK\x03\x04")


def fake_signing_tools(cert, seen=None):
    """keytool/apksigner stand-ins that record what they were given."""
    pin = hashlib.sha256(cert).hexdigest()
    seen = seen if seen is not None else {}
    seen.setdefault("env", [])

    def run(command, *, env=None, stdout=False):
        seen["env"].append(env or {})
        if command[0] == "keytool":
            key = Path(command[command.index("-keystore") + 1])
            seen["key_path"] = key
            seen["key_bytes"] = key.read_bytes()
            return cert
        if "sign" in command:
            shutil.copyfile(Path(command[-1]), Path(command[command.index("--out") + 1]))
            return b""
        if "verify" in command:
            return ("Signer #1 certificate SHA-256 digest: " + pin).encode()
        raise AssertionError(command)

    return pin, run


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
            self.assertEqual(0, signer.main(["--run-id", str(self.run_id), "--allow-pr", "--verify-only"],
                                            read_passphrase=passphrases()))

    def test_owner_signs_without_overwriting_or_leaking_github_token(self):
        private = self.root / "backup.zip"
        private.write_bytes(fake_backup_zip())
        result = self.root / "signed.apk"
        seen = {}
        pin, fake_run = fake_signing_tools(b"fake-owner-cert", seen)
        stderr = io.StringIO()
        with patch.object(signer, "CERT_SHA256", pin), \
             patch.object(signer, "run_checked", side_effect=fake_run), \
             patch.object(signer, "apk_metadata", return_value=("0.12.30-ci2136", 56)), \
             patch.dict("os.environ", {"GITHUB_TOKEN": "PRIVATE_GH_TOKEN"}), \
             contextlib.redirect_stderr(stderr):
            digest = signer.sign_and_verify(self.apk, self.jar, private, result, 2136, "0.12.30-ci2136",
                                            read_passphrase=passphrases(), allow_plaintext=True)
            self.assertTrue(result.is_file())
            self.assertEqual(hashlib.sha256(self.apk).hexdigest(), digest)
            self.assertTrue(all("GITHUB_TOKEN" not in x for x in seen["env"]))
            with self.assertRaisesRegex(signer.SigningError, "Refusing to overwrite"):
                signer.sign_and_verify(self.apk, self.jar, private, result, 2136, "0.12.30-ci2136",
                                       read_passphrase=passphrases(), allow_plaintext=True)
        self.assertIn("PLAINTEXT", stderr.getvalue())
        self.assertNotIn(FAKE_PASSWORD, stderr.getvalue())


class EncryptedBackupTest(unittest.TestCase):
    """Owner backup encrypted at rest; plaintext only behind an explicit flag."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.state = self.root / "home" / ".lumena"
        self.state.mkdir(parents=True)
        self.outside = self.root / "elsewhere"
        self.outside.mkdir()
        self.plain = fake_backup_zip()
        # Sanity: the scans below would see a plaintext leak (the raw keystore
        # bytes appear only once decoded, e.g. as key.p12 while signing).
        for secret in key_material():
            if secret != FAKE_KEYSTORE:
                self.assertIn(secret, self.plain)
        for patcher in (
            # Tests must never reach the real interactive prompt.
            patch.object(signer.getpass, "getpass", side_effect=AssertionError("real getpass called")),
            patch.object(signer, "state_dir", return_value=self.state),
            # Keep the scrypt cost low in tests; one test covers the real default.
            patch.object(signer, "SCRYPT_LOG2_N", 15),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def assert_no_key_material(self, root):
        files = [p for p in root.rglob("*") if p.is_file()]
        for path in files:
            data = path.read_bytes()
            for secret in key_material():
                self.assertNotIn(secret, data, f"{secret!r} written to {path}")
        return files

    def encrypted(self, passphrase=PASSPHRASE):
        return signer.encrypt_backup_bytes(self.plain, passphrase)

    def test_round_trip_with_random_salt_and_nonce(self):
        blob = self.encrypted()
        self.assertTrue(blob.startswith(signer.ENC_MAGIC))
        for secret in key_material():
            self.assertNotIn(secret, blob)
        self.assertEqual(self.plain, signer.decrypt_backup_bytes(blob, PASSPHRASE))
        again = self.encrypted()
        self.assertNotEqual(blob, again)
        self.assertNotEqual(blob[:signer.ENC_HEADER_LEN], again[:signer.ENC_HEADER_LEN])
        self.assertEqual(self.plain, signer.decrypt_backup_bytes(again, PASSPHRASE))

    def test_default_kdf_parameters_round_trip(self):
        """Regression: OpenSSL scrypt needs an explicit maxmem at real cost."""
        with patch.object(signer, "SCRYPT_LOG2_N", 17):
            blob = self.encrypted()
        self.assertEqual(17, blob[len(signer.ENC_MAGIC) + 1])
        self.assertEqual(self.plain, signer.decrypt_backup_bytes(blob, PASSPHRASE))

    def test_unicode_passphrase_is_normalized(self):
        blob = signer.encrypt_backup_bytes(self.plain, "кава café пароль")
        decomposed = "кава café пароль"
        self.assertEqual(self.plain, signer.decrypt_backup_bytes(blob, decomposed))

    def test_wrong_passphrase_is_refused_without_leaking(self):
        blob = self.encrypted()
        wrong = "correct horse battery stapler"
        with self.assertRaisesRegex(signer.SigningError, "passphrase") as caught:
            signer.decrypt_backup_bytes(blob, wrong)
        message = str(caught.exception)
        for secret in (PASSPHRASE, wrong, FAKE_PASSWORD):
            self.assertNotIn(secret, message)
        self.assertIsNone(caught.exception.__cause__)

    def test_tampered_header_ciphertext_or_tag_is_refused(self):
        blob = self.encrypted()
        h = signer.ENC_HEADER_LEN
        magic = len(signer.ENC_MAGIC)
        offsets = {
            "magic": 0, "version": magic, "log2_n": magic + 1, "r": magic + 2, "p": magic + 3,
            "salt": magic + 4, "nonce": h - 1, "ciphertext start": h,
            "ciphertext end": len(blob) - signer.ENC_TAG_LEN - 1,
            "tag start": len(blob) - signer.ENC_TAG_LEN, "tag end": len(blob) - 1,
        }
        for region, offset in offsets.items():
            tampered = bytearray(blob)
            tampered[offset] ^= 0x01
            with self.assertRaises(signer.SigningError, msg=region) as caught:
                signer.decrypt_backup_bytes(bytes(tampered), PASSPHRASE)
            self.assertNotIn(PASSPHRASE, str(caught.exception))
        for region, tampered in {"truncated": blob[:-1], "extended": blob + b"\0",
                                 "header only": blob[:h], "empty": b""}.items():
            with self.assertRaises(signer.SigningError, msg=region):
                signer.decrypt_backup_bytes(tampered, PASSPHRASE)

    def test_plaintext_refused_without_flag_and_loudly_allowed_with_it(self):
        zipped = self.outside / "Lumena-private-signing-backup.zip"
        zipped.write_bytes(self.plain)
        with self.assertRaisesRegex(signer.SigningError, "--encrypt-backup") as caught:
            signer.load_owner_backup(zipped, read_passphrase=passphrases())
        self.assertIn("--allow-plaintext-backup", str(caught.exception))
        self.assertNotIn(FAKE_PASSWORD, str(caught.exception))
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            bundle, key = signer.load_owner_backup(zipped, read_passphrase=passphrases(), allow_plaintext=True)
        self.assertEqual(FAKE_KEYSTORE, key)
        self.assertEqual("lumena", bundle["alias"])
        self.assertIn("WARNING", stderr.getvalue())
        self.assertIn("PLAINTEXT", stderr.getvalue())
        self.assertIn("--encrypt-backup", stderr.getvalue())

    def test_encrypted_backup_loads_with_one_passphrase_prompt(self):
        path = self.state / "Lumena-signing.enc"
        path.write_bytes(self.encrypted())
        read = passphrases(PASSPHRASE)
        bundle, key = signer.load_owner_backup(path, read_passphrase=read)
        self.assertEqual(FAKE_KEYSTORE, key)
        self.assertEqual(FAKE_PASSWORD, bundle["password"])
        self.assertEqual(1, len(read.prompts))
        with self.assertRaisesRegex(signer.SigningError, "passphrase"):
            signer.load_owner_backup(path, read_passphrase=passphrases("not the passphrase"))

    def test_encrypt_cli_keeps_plaintext_and_writes_no_key_material_in_state_dir(self):
        zipped = self.outside / "Lumena-private-signing-backup.zip"
        zipped.write_bytes(self.plain)
        read = passphrases(PASSPHRASE, PASSPHRASE)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(0, signer.main(["--encrypt-backup", "--backup", str(zipped)], read_passphrase=read))
        self.assertEqual(2, len(read.prompts))
        enc = self.state / "Lumena-signing.enc"
        self.assertEqual([enc], self.assert_no_key_material(self.state))
        self.assertTrue(enc.read_bytes().startswith(signer.ENC_MAGIC))
        if os.name == "posix":
            self.assertEqual(0o600, enc.stat().st_mode & 0o777)
        # Never deleted silently: the plaintext stays and the owner is told how to remove it.
        self.assertEqual(self.plain, zipped.read_bytes())
        self.assertIn("NOT deleted", out.getvalue())
        self.assertIn(str(zipped), out.getvalue())
        self.assertNotIn(PASSPHRASE, out.getvalue())
        bundle, key = signer.load_owner_backup(enc, read_passphrase=passphrases(PASSPHRASE))
        self.assertEqual(FAKE_KEYSTORE, key)
        with self.assertRaisesRegex(signer.SigningError, "exists"):
            signer.main(["--encrypt-backup", "--backup", str(zipped)], read_passphrase=passphrases(PASSPHRASE, PASSPHRASE))
        self.assertEqual(self.plain, zipped.read_bytes())

    def test_delete_plaintext_only_after_verified_encryption(self):
        default_plain = self.state / "Lumena-private-signing-backup.zip"
        default_plain.write_bytes(self.plain)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, signer.main(["--encrypt-backup", "--delete-plaintext"],
                                            read_passphrase=passphrases(PASSPHRASE, PASSPHRASE)))
        self.assertFalse(default_plain.exists())
        enc = self.state / "Lumena-signing.enc"
        self.assertEqual([enc], self.assert_no_key_material(self.state))
        self.assertEqual(self.plain, signer.decrypt_backup_bytes(enc.read_bytes(), PASSPHRASE))

    def test_delete_plaintext_later_requires_the_matching_passphrase(self):
        zipped = self.outside / "backup.zip"
        zipped.write_bytes(self.plain)
        with contextlib.redirect_stdout(io.StringIO()):
            signer.main(["--encrypt-backup", "--backup", str(zipped)], read_passphrase=passphrases(PASSPHRASE, PASSPHRASE))
            with self.assertRaisesRegex(signer.SigningError, "passphrase"):
                signer.main(["--encrypt-backup", "--backup", str(zipped), "--delete-plaintext"],
                            read_passphrase=passphrases("a different passphrase"))
            self.assertTrue(zipped.exists())
            other = self.outside / "other.zip"
            other.write_bytes(fake_backup_zip(password="another-password-value"))
            with self.assertRaisesRegex(signer.SigningError, "does not match"):
                signer.main(["--encrypt-backup", "--backup", str(other), "--delete-plaintext"],
                            read_passphrase=passphrases(PASSPHRASE))
            self.assertTrue(other.exists())
            self.assertEqual(0, signer.main(["--encrypt-backup", "--backup", str(zipped), "--delete-plaintext"],
                                            read_passphrase=passphrases(PASSPHRASE)))
        self.assertFalse(zipped.exists())

    def test_encrypt_refuses_mismatch_short_passphrase_or_invalid_backup(self):
        zipped = self.outside / "backup.zip"
        zipped.write_bytes(self.plain)
        enc = self.state / "Lumena-signing.enc"
        for answers, pattern in (((PASSPHRASE, PASSPHRASE + "!"), "do not match"),
                                 (("short", "short"), "at least"),
                                 (("", ""), "at least")):
            with self.assertRaisesRegex(signer.SigningError, pattern):
                signer.main(["--encrypt-backup", "--backup", str(zipped)], read_passphrase=passphrases(*answers))
            self.assertFalse(enc.exists())
        zipped.write_bytes(make_zip([("README.txt", b"not a signing backup")]))
        with self.assertRaisesRegex(signer.SigningError, "LUMENA_SIGNING_BUNDLE"):
            signer.main(["--encrypt-backup", "--backup", str(zipped)], read_passphrase=passphrases(PASSPHRASE, PASSPHRASE))
        self.assertFalse(enc.exists())

    def test_cli_mode_arguments_are_validated(self):
        with contextlib.redirect_stderr(io.StringIO()):
            for argv in ([], ["--delete-plaintext", "--run-id", "1"],
                         ["--encrypt-backup", "--run-id", "1"]):
                with self.assertRaises(SystemExit, msg=argv):
                    signer.main(argv, read_passphrase=passphrases())

    def test_passphrase_reader_refuses_echoing_fallback(self):
        def echoing_getpass(prompt):
            warnings.warn("Can not control echo on the terminal.", signer.getpass.GetPassWarning)
            return PASSPHRASE

        with patch.object(signer.getpass, "getpass", side_effect=echoing_getpass):
            with self.assertRaisesRegex(signer.SigningError, "terminal"):
                signer.read_secret("Passphrase: ")
        with patch.object(signer.getpass, "getpass", side_effect=EOFError):
            with self.assertRaisesRegex(signer.SigningError, "cancelled"):
                signer.read_secret("Passphrase: ")

    def signing_main(self, argv, read):
        """Runs main() through signing with provenance and Android tools faked."""
        run = {"run_number": 2136, "head_sha": "a" * 40}
        artifact = {"digest": "sha256:" + "b" * 64}
        apk = make_zip([("AndroidManifest.xml", b"m"), ("lib/arm64-v8a/liblumena_llama.so", b"n")])
        self.seen = {}
        pin, fake_run = fake_signing_tools(b"fake-owner-cert", self.seen)
        out, err = io.StringIO(), io.StringIO()
        try:
            with patch.object(signer, "trusted_run", return_value=(run, artifact)), \
                 patch.object(signer, "verified_archive", return_value=(apk, b"jar")), \
                 patch.object(signer, "apk_metadata", return_value=("0.12.30-ci2136", 56)), \
                 patch.object(signer, "CERT_SHA256", pin), \
                 patch.object(signer, "run_checked", side_effect=fake_run), \
                 contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                return signer.main(["--run-id", "38171667500", "--allow-pr"] + argv, read_passphrase=read)
        finally:
            self.stdout, self.stderr = out.getvalue(), err.getvalue()

    def test_signing_uses_encrypted_bundle_and_key_only_lives_in_temp_signing_dir(self):
        (self.state / "Lumena-signing.enc").write_bytes(self.encrypted())
        scratch = self.root / "tmp"
        scratch.mkdir()
        result = self.outside / "signed.apk"
        read = passphrases(PASSPHRASE)
        with patch.object(signer.tempfile, "tempdir", str(scratch)):
            self.assertEqual(0, self.signing_main(["--out", str(result)], read))
        self.assertEqual(1, len(read.prompts))
        self.assertTrue(result.is_file())
        # The decrypted keystore existed only inside the temporary signing directory...
        self.assertEqual(FAKE_KEYSTORE, self.seen["key_bytes"])
        self.assertEqual(scratch, self.seen["key_path"].parent.parent)
        # ...which is gone afterwards, and nothing plaintext reached the state dir or output.
        self.assertEqual([], list(scratch.iterdir()))
        self.assert_no_key_material(self.state)
        for secret in (PASSPHRASE, FAKE_PASSWORD):
            self.assertNotIn(secret, self.stdout + self.stderr)

    def test_signing_without_encrypted_bundle_explains_how_to_encrypt(self):
        (self.state / "Lumena-private-signing-backup.zip").write_bytes(self.plain)
        result = self.outside / "signed.apk"
        with self.assertRaisesRegex(signer.SigningError, "--encrypt-backup"):
            self.signing_main(["--out", str(result)], passphrases())
        zipped = self.outside / "backup.zip"
        zipped.write_bytes(self.plain)
        with self.assertRaisesRegex(signer.SigningError, "--allow-plaintext-backup"):
            self.signing_main(["--out", str(result), "--backup", str(zipped)], passphrases())
        self.assertFalse(result.exists())
        self.assertEqual(0, self.signing_main(
            ["--out", str(result), "--backup", str(zipped), "--allow-plaintext-backup"], passphrases()))
        self.assertTrue(result.is_file())
        self.assertIn("PLAINTEXT", self.stderr)
        self.assertNotIn(FAKE_PASSWORD, self.stdout + self.stderr)


if __name__ == "__main__":
    unittest.main()
