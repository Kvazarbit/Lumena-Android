# Critical state-boundary audit

Scope: embedded native loader, runtime policies, diagnostics and agent completion.

| Confirmed defect | Correction | Regression check |
| --- | --- | --- |
| SAF `statSize=-1` bypassed RAM guard and large-model generation limits; explicit GPU mode treated unknown size as safe for full offload. | Use the larger of known file/tensor size; refuse full loading if both are unknown; unknown size never enables GPU offload. | `LlamaPolicyTest` |
| RAM decision used a snapshot from before unloading the previous model and probing. | Refresh hardware profile immediately before the full load. | Android build; actual RAM behavior still needs device validation. |
| Probe failure always meant incompatible metadata; head-only truncation discarded terminal errors; tensor debug spam could hide them. | Preserve head and tail, prioritize explicit allocation/access errors, exclude debug noise. | `LlamaLoadDiagnosticsTest` |
| Native decode errors returned empty or partially generated text as success. | Throw an explicit generation failure after cleanup and discard partial output. Propagate cancellation before interpreting the response. | Native missing-model JNI smoke; decode fault injection/device inference not covered. |
| Non-seekable SAF descriptors entered a loader requiring seeks. | Reject failed seek before llama.cpp is invoked. | Host JNI smoke with a FIFO and invalid FD. |
| A successful unrelated script or arbitrary pytest subset cleared verification for all edited Python files. | Track each changed path case-sensitively; clear it only after a successful check/run of that path. New writes reopen verification. | `AgentControllerTest` |
| A visual-search prose response could bypass pending code verification. | Apply the verification gate to this completion branch too. | `AgentControllerTest` |
| RAM guard errors retried an unchanged load; native generation failures could become protocol retries. | Recognize terminal runtime failures and stop immediately. | `AgentControllerTest` |

Verification is intentionally conservative: a project test command does not establish
which edited files were checked. Pending files still need an explicit syntax check
or successful run. Lexical path normalization does not resolve remote symlinks or
equate absolute and workspace-relative paths; use the same path spelling in checks.

Host tests do not establish successful inference on Android or the cause of the
reported device crash. Earlier Linux GGUF memory measurements used synthetic,
partially populated weights; they are not an Android crash reproduction.

The Android workflow runs unit tests and builds the APK. Its separate `native-smoke`
job builds the pinned CPU llama.cpp and JNI bridge, then runs the Java smoke source
under `app/src/testNative/java`. No model download is required by this smoke test.
