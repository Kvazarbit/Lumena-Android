# Lumena 0.12.30 — unified phone test candidate

**State:** development candidate (not phone-stable, not installed, not signed).
**Repository:** `Kvazarbit/Lumena-Android`.
**Branch:** `feature/unified-phone-0.12.30`.
**Base:** `feature/pc-eval-modules-hardening-v1` @ `53a81e6926100f0ec3ddc43e52c737dfbe36748e`.
**Included Opus fix:** `40b9d4c40267bddcb5d45112fffb980b9b50bd89` (CI #2128 SUCCESS): preserve each marketplace watch's `limit` and `time_range` during poll, with three tests and legacy `20/month` fallback.

This candidate intentionally **combines** the Android-side hardening from
[PR #112](https://github.com/Kvazarbit/Lumena-Android/pull/112)
with the independently tested OLX watch bridge fix, without merging PR #112 or changing phone-stable.

## Expected runtime and limits

- App package: `com.lumena.android`; `versionCode=56`; `versionName=0.12.30-ci<run>`.
- Termux bridge supplied by this source tree: `0.29`, with corrected OLX watch persisted filters; bridge installation is **separate from APK**.
- Existing app features: Companion, Local tools and ChatGPT handoff, StateVault, Constitution Charter/Genome, ContextKernel, MCP tool broker (requires external configured provider), Marketplace/OLX, Listing Attention (permission-based, disabled by default), native Pracuj WorkManager watcher.
- Android Core/Termux/Python tests + 13 MCP/module mutation probes are gated in CI. Green CI proves code/build checks only, **not** that the installed phone works.
- Job search, OLX notifications and Pracuj's website remain subject to real provider availability, site format and permission settings.
- `rozklad` lab is an external Claude-produced archive; it is **not** included in this APK or repository. Hidden `check.py` stays off phone.

## Download and signing

The current CI, without `LUMENA_SIGNING_BUNDLE`, uploads an
`UNSIGNED-Lumena-ci<run>-signing-input` artifact. It **cannot be installed as an ordinary upgrade** until signed using the **owner's pinned signing key**. The private key is never committed to Git.

After exact-head CI SUCCESS, use the GitHub Actions artifact on the run page
and `scripts/sign_apk.py` with the pinned certificate
`signing/certificate.sha256`. This requires the owner's private signing bundle,
Java keytool, and Android apksigner. Sign locally on the owner's trusted device,
then verify signer SHA-256, applicationId, versionCode, and APK hash.
Never publish the bundle or any keystore secrets, and never invent a different key.

## Safe phone acceptance (manual)

1. Record **currently installed** package/versionCode/certificate and Bridge version.
2. Export a StateVault backup; check its status and preserve a separate copy.
3. Update with a correctly owner-signed APK, preserving app data; if signature mismatches, stop — **do not uninstall**.
4. Reinstall/update Termux Bridge from this exact source tree using `termux/install_bridge.sh`; restart old Bridge and confirm `health` reports version 0.29 and required tools.
5. Verify chat/Companion, `workspace.list`, `file.read`, `python.tests`, basic project state and history.
6. Verify module toggles, the sensitive OLX notification module default-OFF, and explicit opt-in for raw captures.
7. Verify MCP clean, typo and negation routes with a configured real read-only provider; a missing provider should report failure/partial, not completion.
8. Only after the owner explicitly consents, verify Pracuj and OLX watchers in a sandbox with notification permission and controlled records; no fabricated vacancies.
9. Run the external `rozklad` lab in a dedicated **disposable** Termux workspace directory; external hidden grader remains on a separate PC.
10. If anything breaks, capture `LUMENA_DIAGNOSTIC_V1` without credentials, preserve installed APK and StateVault backup, and revert to a verified owner-signed APK if available.

**Not accomplished:** phone e2e acceptance, proof of exoskeleton memory benefit, PC independent evaluation on the 24 original user labels, or automated constitution improvement. Do not call these DONE.

## Handoff

All future agents should verify the current branch HEAD and exact-head CI
before claiming installability. No branch merge or release designation without owner approval. See
`docs/pc-preference-evaluation-and-module-hardening.uk.md` for audit gates.
