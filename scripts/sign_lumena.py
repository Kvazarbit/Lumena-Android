#!/usr/bin/env python3
"""Lumena owner-only APK signing, safely reusable across Android CI runs.

Examples (Termux):
  python sign_lumena.py --encrypt-backup              # once: plaintext ZIP -> encrypted bundle
  python sign_lumena.py --run-id 38171667500 --allow-pr
  python sign_lumena.py --run-id 38171667500 --allow-pr --verify-only

Download the corresponding UNSIGNED-Lumena-ciNNNN-signing-input.zip from the
GitHub Actions run to Android Download first. No GitHub token is required for
public run metadata; GITHUB_TOKEN can be set to avoid API rate limits.

Security: verify GitHub run/artifact identity and its SHA-256, use only a pinned
apksigner.jar, verify APK package/version/ABI, verify owner's existing key and
signed certificate; never send, log, generate or export the signing key.

Owner backup at rest: signing reads ~/.lumena/Lumena-signing.enc and asks for its
passphrase on the terminal. The plaintext backup ZIP is refused unless
--allow-plaintext-backup is given. The encrypted file format (version 1) uses only
the Python standard library, because Termux Python may lack `cryptography`:

  header = b"LUMENA-SIGN-ENC\\n" | version=1 | log2(N) | r | p | salt[32] | nonce[16]
  K      = scrypt(UTF-8 of NFC(passphrase), salt, N, r, p, dkLen=64)
  Kenc   = K[0:32], Kmac = K[32:64]    (two independent PBKDF2 output blocks)
  stream = HMAC-SHA256(Kenc, nonce | uint64_be(0)) | HMAC-SHA256(Kenc, nonce | uint64_be(1)) | ...
  body   = original backup ZIP XOR stream
  tag    = HMAC-SHA256(Kmac, header | body)    -> file = header | body | tag

Encrypt-then-MAC: the tag covers the whole header (including the KDF parameters)
and is checked in constant time before anything is decrypted. This is HMAC-SHA256
used as a PRF in counter mode, a textbook construction from standard primitives,
not a vetted AEAD such as AES-GCM or ChaCha20-Poly1305, and it has not been
independently reviewed. Its strength rests on HMAC-SHA256 being a PRF and on the
passphrase. It protects the backup at rest (file reads by bridge tools, copies,
device backups); it cannot protect against a process running as the same user
while you sign, which could read memory or alter this script.
"""
from __future__ import annotations

import argparse
import base64
import getpass
import hashlib
import hmac
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unicodedata
import urllib.error
import urllib.request
import warnings
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
# Encrypted owner backup, format version 1 (see the module docstring).
ENC_MAGIC = b"LUMENA-SIGN-ENC\n"
ENC_VERSION = 1
ENC_SALT_LEN = 32
ENC_NONCE_LEN = 16
ENC_TAG_LEN = 32
ENC_HEADER_LEN = len(ENC_MAGIC) + 4 + ENC_SALT_LEN + ENC_NONCE_LEN
# scrypt N=2^17, r=8, p=1 needs 128 MiB: OWASP's scrypt minimum, fine on a phone.
SCRYPT_LOG2_N = 17
SCRYPT_R = 8
SCRYPT_P = 1
# Accepted on decryption: no weaker than 2^15 and at most 256 MiB, so a crafted
# header can neither request a trivially weak KDF nor exhaust phone memory.
SCRYPT_MIN_LOG2_N = 15
SCRYPT_MAX_MEMORY = 256 * 1024 * 1024
MIN_PASSPHRASE = 12
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
        or re.fullmatch(r"/actions/runs/[1-9][0-9]*/artifacts[?]per_page=100", resource)
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


def state_dir() -> Path:
    return Path.home() / ".lumena"


def backup_location(value: Path | None) -> Path:
    """The legacy plaintext backup ZIP: input of --encrypt-backup."""
    if value is not None:
        return value.expanduser()
    preferred = state_dir() / "Lumena-private-signing-backup.zip"
    if preferred.is_file():
        return preferred
    legacy = DOWNLOAD / "Lumena-private-signing-backup.zip"
    if legacy.is_file():
        print("WARNING: private signing backup is in shared Download; move it into ~/.lumena for safer storage.")
    return legacy


def encrypted_backup_location(value: Path | None) -> Path:
    return value.expanduser() if value is not None else state_dir() / "Lumena-signing.enc"


def read_secret(prompt: str) -> str:
    """Reads a passphrase from the terminal without echo, never from a pipe."""
    try:
        with warnings.catch_warnings():
            # getpass falls back to an echoing stdin read when it cannot reach a
            # terminal; a piped or visible passphrase is refused instead.
            warnings.simplefilter("error", getpass.GetPassWarning)
            return getpass.getpass(prompt)
    except getpass.GetPassWarning:
        raise SigningError("No terminal to read the passphrase without echo; refusing to read it") from None
    except (EOFError, KeyboardInterrupt):
        raise SigningError("Passphrase entry cancelled") from None


def scrypt_parameters_ok(log2_n: int, r: int, p: int) -> bool:
    return (SCRYPT_MIN_LOG2_N <= log2_n <= 30 and 1 <= r <= 32 and 1 <= p <= 16
            and 128 * r * ((1 << log2_n) + 2 + p) <= SCRYPT_MAX_MEMORY)


def derive_keys(passphrase: str, salt: bytes, log2_n: int, r: int, p: int) -> tuple[bytes, bytes]:
    if not hasattr(hashlib, "scrypt"):
        raise SigningError("This Python has no hashlib.scrypt (needs OpenSSL 1.1+); cannot open the encrypted backup")
    # OpenSSL needs 128*r*(N+2+p) bytes; its 32 MiB default maxmem is too small.
    memory = 128 * r * ((1 << log2_n) + 2 + p) + (1 << 20)
    try:
        secret = unicodedata.normalize("NFC", passphrase).encode("utf-8")
        key = hashlib.scrypt(secret, salt=salt, n=1 << log2_n, r=r, p=p, maxmem=memory, dklen=64)
    except (ValueError, MemoryError):
        raise SigningError("scrypt key derivation failed (unusable passphrase or not enough memory)") from None
    return key[:32], key[32:]


def keystream_xor(key: bytes, nonce: bytes, data: bytes) -> bytes:
    blocks = (len(data) + 31) // 32
    stream = b"".join(hmac.digest(key, nonce + i.to_bytes(8, "big"), "sha256") for i in range(blocks))
    mixed = int.from_bytes(data, "big") ^ int.from_bytes(stream[:len(data)], "big")
    return mixed.to_bytes(len(data), "big")


def encrypt_backup_bytes(plaintext: bytes, passphrase: str) -> bytes:
    """Encrypt-then-MAC of the backup ZIP; the format is described in the module docstring."""
    if not 1 <= len(plaintext) <= MAX_BACKUP:
        raise SigningError("Owner signing backup is empty or oversized")
    log2_n, r, p = SCRYPT_LOG2_N, SCRYPT_R, SCRYPT_P
    if not scrypt_parameters_ok(log2_n, r, p):
        raise SigningError("Refusing weak or oversized scrypt parameters")
    salt, nonce = os.urandom(ENC_SALT_LEN), os.urandom(ENC_NONCE_LEN)
    header = ENC_MAGIC + bytes((ENC_VERSION, log2_n, r, p)) + salt + nonce
    enc_key, mac_key = derive_keys(passphrase, salt, log2_n, r, p)
    body = keystream_xor(enc_key, nonce, plaintext)
    return header + body + hmac.digest(mac_key, header + body, "sha256")


def decrypt_backup_bytes(blob: bytes, passphrase: str) -> bytes:
    if not ENC_HEADER_LEN + 1 + ENC_TAG_LEN <= len(blob) <= ENC_HEADER_LEN + MAX_BACKUP + ENC_TAG_LEN:
        raise SigningError("Encrypted signing bundle is truncated or oversized")
    if not blob.startswith(ENC_MAGIC):
        raise SigningError("Not a Lumena encrypted signing bundle")
    version, log2_n, r, p = blob[len(ENC_MAGIC):len(ENC_MAGIC) + 4]
    if version != ENC_VERSION:
        raise SigningError("Unsupported encrypted signing bundle version")
    if not scrypt_parameters_ok(log2_n, r, p):
        raise SigningError("Encrypted signing bundle has unsupported scrypt parameters")
    salt = blob[len(ENC_MAGIC) + 4:ENC_HEADER_LEN - ENC_NONCE_LEN]
    nonce = blob[ENC_HEADER_LEN - ENC_NONCE_LEN:ENC_HEADER_LEN]
    header, body, tag = blob[:ENC_HEADER_LEN], blob[ENC_HEADER_LEN:-ENC_TAG_LEN], blob[-ENC_TAG_LEN:]
    enc_key, mac_key = derive_keys(passphrase, salt, log2_n, r, p)
    # Authenticate everything before decrypting anything.
    if not hmac.compare_digest(tag, hmac.digest(mac_key, header + body, "sha256")):
        raise SigningError("Encrypted signing bundle rejected: wrong passphrase, or the file was modified or corrupted")
    return keystream_xor(enc_key, nonce, body)


def parse_owner_backup(data: bytes) -> tuple[dict, bytes]:
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            raw = one_entry(z, "LUMENA_SIGNING_BUNDLE.json", 2_000_000)
            cert_info = [i for i in z.infolist() if Path(i.filename).name == "lumena-signing-certificate.der"]
            if cert_info and sha256_bytes(one_entry(z, "lumena-signing-certificate.der", 128_000)) != CERT_SHA256:
                raise SigningError("Backup certificate does not match Lumena owner pin")
    except zipfile.BadZipFile:
        raise SigningError("Owner signing backup is not a valid ZIP") from None
    try:
        bundle = json.loads(raw)
    except ValueError:
        raise SigningError("Malformed signing bundle JSON") from None
    if not isinstance(bundle, dict) or any(not isinstance(bundle.get(k), str) or not bundle[k] for k in ("alias", "password", "keystore_base64")):
        raise SigningError("Signing backup does not contain the required fields")
    try:
        key_bytes = base64.b64decode(bundle["keystore_base64"], validate=True)
    except (ValueError, base64.binascii.Error):
        raise SigningError("Malformed signing keystore encoding") from None
    if not (1 <= len(key_bytes) <= 2 * 1024 * 1024):
        raise SigningError("Unexpected keystore size")
    return bundle, key_bytes


def read_backup_file(path: Path) -> bytes:
    if not path.is_file() or path.stat().st_size > ENC_HEADER_LEN + MAX_BACKUP + ENC_TAG_LEN:
        raise SigningError("Owner signing backup missing/oversized: " + str(path))
    return path.read_bytes()


def load_owner_backup(path: Path, *, read_passphrase=None, allow_plaintext: bool = False) -> tuple[dict, bytes]:
    """Owner key material, decrypted in memory only; plaintext ZIPs need explicit consent."""
    data = read_backup_file(path)
    if data.startswith(ENC_MAGIC):
        passphrase = (read_passphrase or read_secret)("Passphrase for " + path.name + ": ")
        return parse_owner_backup(decrypt_backup_bytes(data, passphrase))
    if not data.startswith(b"PK") or len(data) > MAX_BACKUP:
        raise SigningError("Unrecognized owner signing backup format: " + str(path))
    if not allow_plaintext:
        raise SigningError(
            "Plaintext signing backup refused: " + str(path) + ". Encrypt it once with"
            " 'python sign_lumena.py --encrypt-backup --backup " + str(path) + "'; the passphrase"
            " is then asked each time you sign. To sign from the plaintext anyway, add --allow-plaintext-backup.")
    banner = "!" * 72
    print(banner, file=sys.stderr)
    print("WARNING: SIGNING FROM A PLAINTEXT BACKUP (--allow-plaintext-backup):", path, file=sys.stderr)
    print("The owner signing key and its password are unencrypted in that file; any process", file=sys.stderr)
    print("running as this user, including Lumena bridge tools, can read and copy them.", file=sys.stderr)
    print("Encrypt it once: python sign_lumena.py --encrypt-backup --backup", path, file=sys.stderr)
    print(banner, file=sys.stderr)
    return parse_owner_backup(data)


def new_passphrase(read_passphrase) -> str:
    first = read_passphrase("New passphrase for the encrypted signing bundle: ")
    if len(unicodedata.normalize("NFC", first)) < MIN_PASSPHRASE:
        raise SigningError(f"Passphrase must have at least {MIN_PASSPHRASE} characters; nothing was written")
    second = read_passphrase("Repeat the passphrase: ")
    if not hmac.compare_digest(unicodedata.normalize("NFC", first).encode("utf-8", "surrogatepass"),
                               unicodedata.normalize("NFC", second).encode("utf-8", "surrogatepass")):
        raise SigningError("Passphrases do not match; nothing was written")
    return first


def write_private_file(path: Path, data: bytes) -> None:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    # O_EXCL refuses an existing file or symlink; O_BINARY keeps Windows from translating newlines.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0), 0o600)
    try:
        with os.fdopen(fd, "wb") as writer:
            writer.write(data)
            writer.flush()
            os.fsync(writer.fileno())
    except BaseException:
        path.unlink(missing_ok=True)
        raise


def encrypt_owner_backup(plaintext_path: Path, encrypted_path: Path, *, read_passphrase=None,
                         delete_plaintext: bool = False) -> None:
    """One-time conversion of the plaintext backup ZIP; deletes it only on explicit request."""
    read_passphrase = read_passphrase or read_secret
    plaintext = read_backup_file(plaintext_path)
    if plaintext.startswith(ENC_MAGIC):
        raise SigningError("Already an encrypted signing bundle: " + str(plaintext_path))
    if len(plaintext) > MAX_BACKUP:
        raise SigningError("Owner signing backup missing/oversized: " + str(plaintext_path))
    parse_owner_backup(plaintext)
    if os.path.lexists(encrypted_path):
        if not delete_plaintext:
            raise SigningError("Encrypted signing bundle already exists, refusing to overwrite: " + str(encrypted_path)
                               + ". To remove the plaintext after checking it against this bundle, add --delete-plaintext.")
        # Deleting later: the existing bundle must open and hold exactly this backup.
        stored = decrypt_backup_bytes(read_backup_file(encrypted_path),
                                      read_passphrase("Passphrase for " + encrypted_path.name + ": "))
        if not hmac.compare_digest(stored, plaintext):
            raise SigningError("The encrypted bundle does not match this plaintext backup; nothing was deleted")
        print("PASS: existing encrypted bundle opens and matches the plaintext backup:", encrypted_path)
    else:
        passphrase = new_passphrase(read_passphrase)
        write_private_file(encrypted_path, encrypt_backup_bytes(plaintext, passphrase))
        try:
            # Round trip from disk with the same passphrase before anything relies on it.
            if not hmac.compare_digest(decrypt_backup_bytes(read_backup_file(encrypted_path), passphrase), plaintext):
                raise SigningError("Encrypted signing bundle failed its round-trip check")
        except BaseException:
            encrypted_path.unlink(missing_ok=True)
            raise
        print("PASS: encrypted and verified:", encrypted_path)
    if delete_plaintext:
        plaintext_path.unlink()
        print("Deleted plaintext backup:", plaintext_path)
        print("Deleting does not securely erase flash storage, and copies made earlier (Download, PC, cloud) remain.")
        return
    print("PLAINTEXT BACKUP NOT deleted:", plaintext_path)
    print("Copy", encrypted_path, "to a second safe place first. Without the passphrase the bundle cannot")
    print("be opened, and without the key Lumena cannot be updated in place. Then remove the plaintext:")
    print("  python sign_lumena.py --encrypt-backup --backup '" + str(plaintext_path) + "' --delete-plaintext")


def signing_backup(backup: Path | None, encrypted: Path | None, allow_plaintext: bool) -> Path:
    if allow_plaintext:
        return backup_location(backup)
    if backup is not None:
        # An explicit file: its content decides, and a plaintext ZIP is refused on load.
        return backup.expanduser()
    path = encrypted_backup_location(encrypted)
    if not path.is_file():
        raise SigningError("Encrypted signing bundle not found: " + str(path) + ". Create it once from the"
                           " plaintext backup ZIP with: python sign_lumena.py --encrypt-backup")
    return path


def sign_and_verify(apk_bytes: bytes, jar_bytes: bytes, backup: Path, out: Path, run_number: int, version: str,
                    *, read_passphrase=None, allow_plaintext: bool = False) -> str:
    if out.exists() or not out.parent.is_dir():
        raise SigningError("Refusing to overwrite output or missing destination folder: " + str(out))
    # Decrypted key material stays in memory and, below, in the temporary signing directory only.
    bundle, key_bytes = load_owner_backup(backup, read_passphrase=read_passphrase, allow_plaintext=allow_plaintext)
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


def main(argv: list[str] | None = None, *, read_passphrase=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run-id", type=int, help="GitHub Actions run ID from its URL (required for signing)")
    p.add_argument("--allow-pr", action="store_true", help="Explicitly approve signing an unmerged same-repo PR build")
    p.add_argument("--artifact", type=Path, help="Local ZIP downloaded from the specified GitHub run")
    p.add_argument("--backup", type=Path, help="Plaintext owner backup ZIP (input of --encrypt-backup); defaults to ~/.lumena or legacy Download")
    p.add_argument("--encrypted-backup", type=Path, help="Encrypted owner bundle; defaults to ~/.lumena/Lumena-signing.enc")
    p.add_argument("--allow-plaintext-backup", action="store_true", help="Sign from the plaintext backup ZIP anyway (prints a warning)")
    p.add_argument("--encrypt-backup", action="store_true", help="One-time step: encrypt the plaintext backup ZIP into the encrypted bundle and verify it")
    p.add_argument("--delete-plaintext", action="store_true", help="With --encrypt-backup: delete the plaintext ZIP once the encrypted bundle is verified against it")
    p.add_argument("--out", type=Path, help="Signed APK output; refuses overwriting an existing file")
    p.add_argument("--verify-only", action="store_true", help="Verify provenance, ZIP and APK metadata without handling the key")
    args = p.parse_args(argv)
    if args.delete_plaintext and not args.encrypt_backup:
        p.error("--delete-plaintext only works together with --encrypt-backup")
    if args.encrypt_backup:
        if (args.run_id is not None or args.allow_pr or args.artifact or args.out or args.verify_only
                or args.allow_plaintext_backup):
            p.error("--encrypt-backup is a separate one-time step; run it without signing options")
        encrypt_owner_backup(backup_location(args.backup), encrypted_backup_location(args.encrypted_backup),
                             read_passphrase=read_passphrase, delete_plaintext=args.delete_plaintext)
        return 0
    if args.run_id is None:
        p.error("--run-id is required for signing")

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
    backup = signing_backup(args.backup, args.encrypted_backup, args.allow_plaintext_backup)
    checksum = sign_and_verify(apk_bytes, jar_bytes, backup, output, number, version,
                               read_passphrase=read_passphrase, allow_plaintext=args.allow_plaintext_backup)
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
