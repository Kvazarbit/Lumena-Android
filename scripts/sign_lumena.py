#!/usr/bin/env python3
"""Lumena owner-only APK signing, safely reusable across Android CI runs.

Examples (Termux):
  python sign_lumena.py --run-id 38171667500 --allow-pr
  python sign_lumena.py --run-id 38171667500 --allow-pr --verify-only

Download the corresponding UNSIGNED-Lumena-ciNNNN-signing-input.zip from the
GitHub Actions run to Android Download first. No GitHub token is required for
public run metadata; GITHUB_TOKEN can be set to avoid API rate limits.

Security: verify GitHub run/artifact identity and its SHA-256, use only a pinned
apksigner.jar, verify APK package/version/ABI, verify owner's existing key and
signed certificate; never send, log, generate or export the signing key.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import zipfile

REPO = "Kvazarbit/Lumena-Android"
REPO_ID = 1363253338
WORKFLOW_ID = 354421128
WORKFLOW_PATH = ".github/workflows/android.yml"
PACKAGE = "com.lumena.android"
CERT_SHA256 = "2ded3114b43582a841dd107a5388f6dd49c0d0f37af6d1a7a2e048093fc04d9d"
# The trusted Android Build Tools 35.0.0 apksigner.jar, checked from CI2136.
# Upgrade deliberately and review a new hash when changing the build toolchain.
APKSIGNER_SHA256 = "00ef9948f843fe395d2440ae3ef41405b8040a6d5d46493bd1902ac0ee6deae7"
DOWNLOAD = Path("/storage/emulated/0/Download")
MAX_ARTIFACT = 100 * 1024 * 1024
MAX_APK = 130 * 1024 * 1024
MAX_JAR = 35 * 1024 * 1024
MAX_BACKUP = 4 * 1024 * 1024
HEX_SHA = re.compile(r"[0-9a-f]{64}")
HEX_GIT_SHA = re.compile(r"[0-9a-f]{40}")


class SigningError(RuntimeError):
    pass


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def github_json(resource: str) -> dict:
    # Only the exact endpoints used by the signer are permitted.  The artifact
    # listing legitimately needs a bounded pagination query; an unconditional
    # ban on '?' previously blocked every signing attempt.
    allowed = (
        re.fullmatch(r"/actions/runs/[1-9][0-9]*", resource)
        or re.fullmatch(r"/pulls/[1-9][0-9]*", resource)
        or re.fullmatch(r"/actions/runs/[1-9][0-9]*/artifacts\\?per_page=100", resource)
    )
    if not allowed:
        raise SigningError("Invalid GitHub API resource")
    url = "https://api.github.com/repos/" + REPO + resource
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "Lumena-Owner-Signer/1"}
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            if response.geturl() != url:
                raise SigningError("Unexpected GitHub API redirect")
            length = response.read(2_000_001)
    except (OSError, urllib.error.HTTPError, ValueError):
        raise SigningError("GitHub API metadata unavailable; signing refused") from None
    if len(length) > 2_000_000:
        raise SigningError("Oversized GitHub API response")
    try:
        result = json.loads(length)
    except (ValueError, UnicodeDecodeError):
        raise SigningError("Malformed GitHub API response") from None
    if not isinstance(result, dict):
        raise SigningError("GitHub metadata is not an object")
    return result


def trusted_run(run_id: int, *, allow_pr: bool, fetch=github_json) -> tuple[dict, dict]:
    if run_id < 1:
        raise SigningError("Invalid run ID")
    run = fetch("/actions/runs/" + str(run_id))
    if (run.get("id") != run_id or run.get("repository", {}).get("id") != REPO_ID
            or run.get("repository", {}).get("full_name") != REPO
            or run.get("head_repository", {}).get("id") != REPO_ID
            or run.get("head_repository", {}).get("full_name") != REPO
            or run.get("workflow_id") != WORKFLOW_ID
            or run.get("path") != WORKFLOW_PATH
            or run.get("status") != "completed" or run.get("conclusion") != "success"):
        raise SigningError("Run is not a successful build from the pinned Lumena repository and workflow")
    event = run.get("event")
    commit = run.get("head_sha", "")
    number = run.get("run_number")
    if not isinstance(number, int) or number < 1 or not isinstance(commit, str) or not HEX_GIT_SHA.fullmatch(commit.lower()):
        raise SigningError("Missing or invalid GitHub run identity")
    if event == "pull_request":
        if not allow_pr:
            raise SigningError("Signing a PR build requires explicit --allow-pr")
        prs = run.get("pull_requests")
        if not isinstance(prs, list) or len(prs) != 1 or not isinstance(prs[0].get("number"), int):
            raise SigningError("Cannot uniquely identify the pull request")
        pr = fetch("/pulls/" + str(prs[0]["number"]))
        if (pr.get("head", {}).get("sha") != commit
                or pr.get("head", {}).get("repo", {}).get("id") != REPO_ID
                or pr.get("base", {}).get("repo", {}).get("id") != REPO_ID):
            raise SigningError("PR head or repository differs from the verified workflow run")
    elif event != "push":
        raise SigningError("Unsupported GitHub Actions event; only push or explicitly allowed same-repo PR")

    artifacts = fetch("/actions/runs/" + str(run_id) + "/artifacts?per_page=100").get("artifacts")
    name = f"UNSIGNED-Lumena-ci{number}-signing-input"
    if not isinstance(artifacts, list):
        raise SigningError("Artifact metadata unavailable")
    matches = [a for a in artifacts if a.get("name") == name]
    if len(matches) != 1:
        raise SigningError("Expected exactly one GitHub signing artifact: " + name)
    artifact = matches[0]
    digest = artifact.get("digest", "")
    origin = artifact.get("workflow_run", {})
    if (artifact.get("expired") is not False or not isinstance(digest, str)
            or not digest.startswith("sha256:") or not HEX_SHA.fullmatch(digest[7:].lower())
            or origin.get("head_sha") != commit
            or (origin.get("head_branch") and origin.get("head_branch") != run.get("head_branch"))):
        raise SigningError("Artifact provenance, availability or SHA-256 is invalid")
    return run, artifact


def one_entry(archive: zipfile.ZipFile, basename: str, max_size: int) -> bytes:
    matches = [i for i in archive.infolist() if not i.is_dir() and Path(i.filename).name == basename]
    if len(matches) != 1:
        raise SigningError(f"Expected exactly one {basename} in signing ZIP")
    entry = matches[0]
    if (entry.file_size < 1 or entry.file_size > max_size or entry.compress_size > MAX_ARTIFACT
            or entry.filename.startswith("/") or ".." in Path(entry.filename).parts):
        raise SigningError("Unsafe or oversized signing ZIP entry: " + basename)
    data = archive.read(entry)
    if len(data) != entry.file_size:
        raise SigningError("Unexpected ZIP entry length: " + basename)
    return data


def verified_archive(path: Path, digest: str) -> tuple[bytes, bytes]:
    if not path.is_file() or path.stat().st_size > MAX_ARTIFACT:
        raise SigningError("Missing or oversized artifact ZIP: " + str(path))
    # Hash and parse the same immutable bytes; avoid a ZIP replacement race in Download.
    payload = path.read_bytes()
    if sha256_bytes(payload) != digest:
        raise SigningError("Artifact ZIP SHA-256 disagrees with verified GitHub Actions metadata")
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        apk = one_entry(archive, "app-release-unsigned.apk", MAX_APK)
        signer = one_entry(archive, "apksigner.jar", MAX_JAR)
    if sha256_bytes(signer) != APKSIGNER_SHA256:
        raise SigningError("Unexpected apksigner.jar SHA-256; review the build tools before signing")
    with zipfile.ZipFile(io.BytesIO(apk)) as inner:
        names = set(inner.namelist())
        if "AndroidManifest.xml" not in names or "lib/arm64-v8a/liblumena_llama.so" not in names:
            raise SigningError("Not an expected arm64 Lumena APK")
    return apk, signer


def run_checked(argv: list[str], *, env: dict | None = None, stdout: bool = False) -> bytes:
    try:
        result = subprocess.run(argv, env=env, check=False, capture_output=True, timeout=180)
    except (OSError, subprocess.TimeoutExpired):
        raise SigningError("Required local signing command unavailable or timed out") from None
    if result.returncode != 0:
        # Never print subprocess stderr: vendor tools may inadvertently echo secrets.
        raise SigningError("Local signing/check command failed: " + Path(argv[0]).name)
    return result.stdout if stdout else b""


def apk_metadata(apk: Path, run_number: int) -> tuple[str, int]:
    aapt = shutil.which("aapt") or shutil.which("aapt2")
    if not aapt:
        raise SigningError("aapt/aapt2 is required for verified package/version metadata; refusing to sign")
    result = run_checked([aapt, "dump", "badging", str(apk)], stdout=True).decode("utf-8", errors="replace")
    m = re.search(r"^package:\s+name='([^']+)'\s+versionCode='([^']+)'\s+versionName='([^']+)'", result, re.M)
    if not m:
        raise SigningError("aapt did not verify package version information")
    package, code, version = m.groups()
    if (package != PACKAGE or not code.isdecimal() or int(code) < 1
            or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+-ci" + str(run_number), version)):
        raise SigningError("APK identity/version does not match expected Lumena CI run")
    return version, int(code)


def backup_location(value: Path | None) -> Path:
    if value is not None:
        return value.expanduser()
    preferred = Path.home() / ".lumena" / "Lumena-private-signing-backup.zip"
    if preferred.is_file():
        return preferred
    legacy = DOWNLOAD / "Lumena-private-signing-backup.zip"
    if legacy.is_file():
        print("WARNING: private signing backup is in shared Download; move it into ~/.lumena for safer storage.")
    return legacy


def load_owner_backup(path: Path) -> tuple[dict, bytes]:
    if not path.is_file() or path.stat().st_size > MAX_BACKUP:
        raise SigningError("Owner signing backup missing/oversized: " + str(path))
    with zipfile.ZipFile(path) as z:
        bundle = json.loads(one_entry(z, "LUMENA_SIGNING_BUNDLE.json", 2_000_000))
        cert_info = [i for i in z.infolist() if Path(i.filename).name == "lumena-signing-certificate.der"]
        if cert_info and sha256_bytes(one_entry(z, "lumena-signing-certificate.der", 128_000)) != CERT_SHA256:
            raise SigningError("Backup certificate does not match Lumena owner pin")
    if not isinstance(bundle, dict) or any(not isinstance(bundle.get(k), str) or not bundle[k] for k in ("alias", "password", "keystore_base64")):
        raise SigningError("Signing backup does not contain the required fields")
    try:
        key_bytes = base64.b64decode(bundle["keystore_base64"], validate=True)
    except (ValueError, base64.binascii.Error):
        raise SigningError("Malformed signing keystore encoding") from None
    if not (1 <= len(key_bytes) <= 2 * 1024 * 1024):
        raise SigningError("Unexpected keystore size")
    return bundle, key_bytes


def sign_and_verify(apk_bytes: bytes, jar_bytes: bytes, backup: Path, out: Path, run_number: int, version: str) -> str:
    if out.exists() or not out.parent.is_dir():
        raise SigningError("Refusing to overwrite output or missing destination folder: " + str(out))
    bundle, key_bytes = load_owner_backup(backup)
    with tempfile.TemporaryDirectory(prefix="lumena-owner-sign-") as directory:
        tmp = Path(directory)
        apk = tmp / "unsigned.apk"
        jar = tmp / "apksigner.jar"
        key = tmp / "key.p12"
        signed = tmp / "signed.apk"
        apk.write_bytes(apk_bytes)
        jar.write_bytes(jar_bytes)
        key.write_bytes(key_bytes)
        key.chmod(0o600)
        # Never pass a GitHub API token to the child signing tools.
        env = {k: v for k, v in os.environ.items() if k not in {"GITHUB_TOKEN", "GH_TOKEN"}}
        env["LUMENA_SIGNING_PASSWORD"] = bundle["password"]
        cert = run_checked([
            "keytool", "-exportcert", "-storetype", "PKCS12", "-keystore", str(key),
            "-storepass:env", "LUMENA_SIGNING_PASSWORD", "-alias", bundle["alias"],
        ], env=env, stdout=True)
        if sha256_bytes(cert) != CERT_SHA256:
            raise SigningError("Owner keystore certificate differs from pinned Lumena certificate")
        command = ["java", "-jar", str(jar)]
        run_checked(command + [
            "sign", "--ks", str(key), "--ks-type", "PKCS12", "--ks-key-alias", bundle["alias"],
            "--ks-pass", "env:LUMENA_SIGNING_PASSWORD", "--key-pass", "env:LUMENA_SIGNING_PASSWORD",
            "--out", str(signed), str(apk),
        ], env=env)
        receipt = run_checked(command + ["verify", "--verbose", "--print-certs", str(signed)], env=env, stdout=True)
        match = re.search(rb"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", receipt)
        if not match or match.group(1).decode().lower() != CERT_SHA256:
            raise SigningError("Signed APK owner certificate verification failed")
        new_version, _ = apk_metadata(signed, run_number)
        if new_version != version:
            raise SigningError("Signed APK version changed")
        checksum = sha256_file(signed)
        # Atomically avoid overwriting preexisting output, including symlinks.
        fd = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
        try:
            with os.fdopen(fd, "wb") as writer, signed.open("rb") as src:
                shutil.copyfileobj(src, writer)
        except Exception:
            out.unlink(missing_ok=True)
            raise
    return checksum


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run-id", type=int, required=True, help="GitHub Actions run ID from its URL")
    p.add_argument("--allow-pr", action="store_true", help="Explicitly approve signing an unmerged same-repo PR build")
    p.add_argument("--artifact", type=Path, help="Local ZIP downloaded from the specified GitHub run")
    p.add_argument("--backup", type=Path, help="Local owner backup ZIP; defaults to ~/.lumena or legacy Download")
    p.add_argument("--out", type=Path, help="Signed APK output; refuses overwriting an existing file")
    p.add_argument("--verify-only", action="store_true", help="Verify provenance, ZIP and APK metadata without handling the key")
    args = p.parse_args(argv)

    run, artifact = trusted_run(args.run_id, allow_pr=args.allow_pr)
    number = run["run_number"]
    digest = artifact["digest"].split(":", 1)[1].lower()
    path = args.artifact or DOWNLOAD / f"UNSIGNED-Lumena-ci{number}-signing-input.zip"
    apk_bytes, jar_bytes = verified_archive(path, digest)
    with tempfile.TemporaryDirectory(prefix="lumena-apk-inspect-") as temp:
        apk = Path(temp) / "app-release-unsigned.apk"
        apk.write_bytes(apk_bytes)
        version, code = apk_metadata(apk, number)
    print("GitHub run:", args.run_id, "CI:", number)
    print("Verified commit:", run["head_sha"])
    print("Artifact SHA-256:", digest)
    print("APK:", PACKAGE, "versionName=" + version, "versionCode=" + str(code))
    print("Owner certificate pin:", CERT_SHA256)
    if args.verify_only:
        print("PASS: verified source and APK; no signing key opened or APK signed")
        return 0
    output = args.out or DOWNLOAD / f"Lumena-v{version}-arm64.apk"
    checksum = sign_and_verify(apk_bytes, jar_bytes, backup_location(args.backup), output, number, version)
    print("PASS: owner-signed APK SHA-256:", checksum)
    print("SIGNED APK:", output)
    print("To update the existing app without uninstalling: termux-open", str(output))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (SigningError, OSError, zipfile.BadZipFile, KeyError, ValueError, json.JSONDecodeError) as err:
        # Sanitized diagnostics, no key contents, passwords or command stderr.
        print("SIGNING STOPPED:", err, file=sys.stderr)
        sys.exit(1)
