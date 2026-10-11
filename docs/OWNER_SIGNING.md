# Signing Lumena APK builds

Use scripts/sign_lumena.py for a locally downloaded unsigned signing-input ZIP produced by the Lumena Android CI workflow.

Prerequisites on the local device: Python 3.10+, Java, keytool, aapt or aapt2, and the existing owner backup provided separately. The backup must remain local to the owner's device.

1. Open the successful build at https://github.com/Kvazarbit/Lumena-Android/actions/workflows/android.yml.
2. Download its UNSIGNED-Lumena-ciNNNN-signing-input.zip to Android Download (do not extract).
3. Copy the run ID from the GitHub Actions URL.
4. Run the following in Termux for the verified CI2136 example:

~~~sh
python sign_lumena.py --run-id 38171667500 --allow-pr --verify-only
python sign_lumena.py --run-id 38171667500 --allow-pr
~~~

The --allow-pr option is required for an unmerged same-repository pull-request build. It is not needed for an ordinary push build. For a renamed local ZIP, pass --artifact followed by its path.

Every new run is checked against the GitHub API: correct workflow, repository, commit, successful conclusion, and SHA-256 artifact digest. The APK package, version, arm64 library and signer tool hash must match the expected Lumena build. The generated signed APK remains local and is never installed automatically.

If the workflow, signer toolchain, certificate, or archive provenance changes, the script refuses to sign until reviewed. A successful CI run does not by itself make arbitrary PR code safe.

Security tests:

~~~sh
python -m unittest discover -s scripts -p 'test_sign_lumena.py' -v
~~~
