# Lumena ci2337 → follow-up hardening (review before phone deployment)

Base: `feature/ci2333-phone-stability-fixes-v1` @ `cbd70bd68b7`.
Branch: `fix/ci2337-repair-relevance-image-previews-ux-v1`. Android 0.12.36 / versionCode 71; Bridge remains 0.35.

## Three bounded changes

1. **RepairCases relevance.** A different test in the same file with the same exception type no longer qualifies for historical advice solely on generic `AssertionError|assert N == N`. A meaningful message Jaccard overlap of at least 0.35 is necessary for this fallback. Exact failing-test identity and legacy exact receipt rules retain precedence. The rule prefers no advice over misleading advice.
2. **Image previews.** Wikimedia host validation uses exact domain or dot-separated subdomain (rejects lookalikes such as `evilwikimedia.org`). Openverse may display HTTPS image previews only on an explicit small public CDN allowlist: Unsplash, Flickr, Pexels, Pixabay, Wikimedia and Openverse. URL userinfo, private hosts/IP literals, nonstandard ports, HTTP and fragments are refused. These third-party images remain untrusted public content and may contact their CDN when displayed.
3. **Project discovery and archive hygiene.** A visible Projects/papki Termux header shows the actual selected root. The Projects panel explains that its Save action only registers an existing Termux folder; Chats and Context Genome are separate. ZIP export skips non-project `repair_lab_*` SharedPreferences entries; the previous GGUF key remains excluded.

## Tests and release gate

A detached parent commit `dff7006a` adds intentionally failing regressions for unrelated same-file repairs, image URL origin validation and unrelated cache keys in ZIP export. The implementation follows in its child commit. Android CI must pass new Kotlin and Python tests; the project-memory regression must execute `siblingFailedTestNeedsMeaningfulFailureOverlap` and produce `KILLED_EXPECTED same-file-unrelated-message`, not `SKIPPED`. No remote APK or phone state is modified by CI.

## Known residuals

- The runtime may have GGUF weights loaded from earlier file bytes. A selected-file SHA-256 is **not** proof of the loaded model identity. Do not begin a 50/50 inference experiment until this is attested.
- Bridge liveness retries, project activation guards, private-context rejection, GoalContract permissions, basic image timeout fallback and the GGUF cache export fix were implemented earlier on ci2337, but all require owner-phone acceptance.
- Cloud model `:cloud` receives project context only after the owner's per-project permission; this patch adds no bypass.
