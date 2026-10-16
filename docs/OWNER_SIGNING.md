# Signing Lumena APK builds

Use scripts/sign_lumena.py for a locally downloaded unsigned signing-input ZIP produced by the Lumena Android CI workflow.

Prerequisites on the local device: Python 3.10+, Java, keytool, aapt or aapt2, and the existing owner backup provided separately. The backup must remain local to the owner's device.

## Encrypt the owner backup once

Signing reads the encrypted bundle ~/.lumena/Lumena-signing.enc and asks for its passphrase on the terminal each time. The legacy plaintext Lumena-private-signing-backup.zip is refused unless you pass --allow-plaintext-backup, which prints a loud warning. Convert it once:

~~~sh
python sign_lumena.py --encrypt-backup
~~~

This reads ~/.lumena/Lumena-private-signing-backup.zip (or the legacy copy in Download; pass --backup for another path), asks for a new passphrase twice (at least 12 characters), writes ~/.lumena/Lumena-signing.enc with mode 0600, and decrypts it again from disk to check the round trip. It refuses to overwrite an existing bundle and never deletes the plaintext by itself. It prints the path of the plaintext that is still there.

Copy Lumena-signing.enc to a second safe place before you delete the plaintext. The bundle is the only copy of the signing key once the plaintext is gone. If you lose the passphrase or the file, Lumena can no longer be updated in place. Then delete the plaintext. This step asks for the passphrase again and deletes only if the bundle opens and holds exactly that ZIP:

~~~sh
python sign_lumena.py --encrypt-backup --delete-plaintext
~~~

Deleting a file does not securely erase flash storage. Copies made earlier (Download, a PC, cloud backups) are not touched; remove them yourself.

What the encryption covers: the backup at rest. File reads by bridge tools, copies and device backups only get ciphertext. What it does not cover: a process running as the same Termux user while you sign. Such a process can read the signer's memory, or it can change sign_lumena.py to capture the passphrase. Check the script against the repository before you type the passphrase, or sign on a PC.

The passphrase is read only through getpass. The script refuses to read it from a pipe or from a terminal that would echo it. It is never taken from an argument or an environment variable. The decrypted ZIP stays in memory. The keystore exists as a file only in the temporary signing directory while keytool/apksigner run, as before.

Format (version 1, Python standard library only, because Termux Python may lack `cryptography`):

- header = `LUMENA-SIGN-ENC\n`, version, log2(N), r, p, a random 32-byte salt and a random 16-byte nonce.
- K = scrypt(UTF-8 of the NFC-normalized passphrase, salt, N=2^17, r=8, p=1, 64 bytes). The encryption key is K[0:32] and the MAC key is K[32:64].
- The ciphertext is the original ZIP XOR a keystream. Keystream block i is HMAC-SHA256(encryption key, nonce ‖ uint64_be(i)).
- The tag is HMAC-SHA256(MAC key, header ‖ ciphertext). It is checked in constant time before anything is decrypted. A wrong passphrase or any modified byte gives the same refusal, with no key material in the message.

This is encrypt-then-MAC with HMAC-SHA256 as a PRF in counter mode. It is a textbook construction from standard primitives, not a vetted AEAD such as AES-GCM or ChaCha20-Poly1305, and it has not been independently reviewed. Its strength rests on HMAC-SHA256 being a PRF and on the quality of the passphrase. On decryption the KDF parameters must be at least N=2^15 and use at most 256 MiB.

## Sign a CI build

1. Open the successful build at https://github.com/Kvazarbit/Lumena-Android/actions/workflows/android.yml.
2. Download its UNSIGNED-Lumena-ciNNNN-signing-input.zip to Android Download (do not extract).
3. Copy the run ID from the GitHub Actions URL.
4. Run the following in Termux for the verified CI2136 example:

~~~sh
python sign_lumena.py --run-id 38171667500 --allow-pr --verify-only
python sign_lumena.py --run-id 38171667500 --allow-pr
~~~

Signing asks for the bundle passphrase only after the run, artifact and APK checks pass; --verify-only never opens the bundle. The --allow-pr option is required for an unmerged same-repository pull-request build. It is not needed for an ordinary push build. For a renamed local ZIP, pass --artifact followed by its path.

Every new run is checked against the GitHub API: correct workflow, repository, commit, successful conclusion, and SHA-256 artifact digest. The APK package, version, arm64 library and signer tool hash must match the expected Lumena build. The generated signed APK remains local and is never installed automatically.

If the workflow, signer toolchain, certificate, or archive provenance changes, the script refuses to sign until reviewed. A successful CI run does not by itself make arbitrary PR code safe.

Security tests:

~~~sh
python -m unittest discover -s scripts -p 'test_sign_lumena.py' -v
~~~
