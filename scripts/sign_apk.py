#!/usr/bin/env python3
"""Sign only with the owner's pinned key. Never generate a CI debug identity."""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def sign(apk, output, apksigner, bundle, pin):
    expected = Path(pin).read_text().strip().lower()
    if not re.fullmatch(r'[a-f0-9]{64}', expected):
        raise ValueError('Invalid pinned certificate')
    with tempfile.TemporaryDirectory(prefix='lumena-signing-') as directory:
        key = Path(directory) / 'key.p12'
        key.write_bytes(base64.b64decode(bundle['keystore_base64'], validate=True))
        key.chmod(0o600)
        env = dict(os.environ, LUMENA_SIGNING_PASSWORD=bundle['password'])
        cert = subprocess.run(['keytool', '-exportcert', '-keystore', str(key),
            '-storepass:env', 'LUMENA_SIGNING_PASSWORD', '-alias', bundle['alias']],
            env=env, check=True, capture_output=True).stdout
        import hashlib
        if hashlib.sha256(cert).hexdigest() != expected:
            raise ValueError('Signing key differs from pinned Lumena certificate; refusing incompatible update')
        command = ['java', '-jar', apksigner] if str(apksigner).endswith('.jar') else [apksigner]
        subprocess.run(command + ['sign', '--ks', str(key), '--ks-key-alias', bundle['alias'],
            '--ks-pass', 'env:LUMENA_SIGNING_PASSWORD', '--key-pass', 'env:LUMENA_SIGNING_PASSWORD',
            '--out', output, apk], env=env, check=True, capture_output=True)
        result = subprocess.run(command + ['verify', '--verbose', '--print-certs', output],
            check=True, capture_output=True, text=True)
        actual = re.search(r'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)', result.stdout)
        if not actual or actual.group(1).lower() != expected:
            Path(output).unlink(missing_ok=True)
            raise ValueError('Output certificate mismatch')
        print('Verified Lumena certificate SHA-256: ' + expected)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--apk', required=True)
    p.add_argument('--out', required=True)
    p.add_argument('--apksigner', required=True)
    p.add_argument('--bundle-file')
    p.add_argument('--pin', default='signing/certificate.sha256')
    args = p.parse_args()
    raw = Path(args.bundle_file).read_text() if args.bundle_file else os.environ.get('LUMENA_SIGNING_BUNDLE', '')
    if not raw:
        raise SystemExit('LUMENA_SIGNING_BUNDLE is missing. No installable APK will be published.')
    try:
        sign(args.apk, args.out, args.apksigner, json.loads(raw), args.pin)
    except subprocess.CalledProcessError:
        # Tool stderr can contain sensitive keystore arguments; do not print it.
        raise SystemExit('Signing or verification failed. No credentials were logged.')
