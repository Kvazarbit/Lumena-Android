# Laya OpenMP pilot (2026-09-27)

Laya remains a shadow adviser. It cannot approve or execute tools, certify task
completion, or rewrite the constitution. Existing `lumena_laya_shadow_v1.json`
records agreement with the reference policy; agreement is not ground-truth accuracy.
No model weights were trained in this change.

## Observed on OnePlus LE2123 / Android 14

Pinned C source: `941e64863193c1290bffece6c22f8ac828dffecd`, SHA-256
`2cb905156d775f25c4a1b1403085a029392a71fd984e209cbaf73ad9d66f03a9`.
Clang with the Android standard-setjmp fallback and static OpenMP compiled.
For one 56-token prompt, inference times in milliseconds (three runs each):

| Threads | Measurements | Median |
| --- | --- | --- |
| 1 | 1089, 1515, 966 | 1089 |
| 2 | 875, 841, 916 | 875 |
| 4 | 1120, 482, 921 | 921 |
| 8 | 865, 875, 1343 | 875 |

Two threads are a conservative pilot default, not proven universally optimal.
Do not compare CLI wall time, which includes model loading, with warm HTTP latency.
A separate HTTP service on port 29418 reported OpenMP=true, threads=2 and answered
two short cases correctly in 744 and 732 ms. The primary service was not replaced
by that experiment. Four synthetic scenarios with reversed option order passed
8/8; these do not establish code-review quality, multilingual accuracy or reliable
calibration. Test longer inputs and real tasks before expanding model authority.

## Runtime update

Use the new installer in Termux, with the model already installed:

```sh
LUMENA_LAYA_THREADS=2 bash ~/.lumena/install_laya_system1.sh upgrade
```

The installer verifies the pinned source, builds in a staging directory, backs up
the existing binary/configuration, renames the candidate into place and restarts.
Startup must report ready, OpenMP=true and the requested thread count. Failure
restores and starts the previous runtime. `rollback` restores the latest backup
manually. `runtime` only builds/installs; it does not replace a running process.
A lock prevents simultaneous installer operations. The bridge also reads the
persisted `~/.lumena/laya/threads` file when starting a service. Updating the APK
alone does not install this Termux runtime or update a running bridge.

The model weights and runtime are in Termux. They are not included in the Android
memory-vault ZIP. Keep their provenance/configuration with any device migration;
revalidate performance and behavior on the destination device.

## Companion transport

APK 0.12.8 accepts the existing plain command format and an integrity envelope:

```text
LUMENA_TOOL
{"encoding":"base64-sha256-v1","payload_b64":"...","sha256":"..."}
```

Use `python scripts/encode_companion.py request.json` to generate the complete
fenced block. The payload is UTF-8 JSON for one ordinary tool request. Base64 must
be canonical and SHA-256 must match the decoded bytes (maximum 96 KiB). Corrupt,
truncated, oversized, invalid UTF-8 and nested envelopes are rejected. This detects
screen-transport damage; it is not authentication or authorization. The decoded
request still goes through the same registry, approval and task-grant gates.
The receiver does not strip arbitrary invisible characters from source code.
Legacy plain commands cannot detect arbitrary source-code corruption; use the
encoded form for writes and patches. Screen capture still requires the complete
block to be visible/accessibility-readable; split large operations if necessary.
