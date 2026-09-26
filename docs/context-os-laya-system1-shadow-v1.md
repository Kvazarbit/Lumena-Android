# Laya System-1 Shadow v1

This stage adds an optional open-weight learned System-1 decision layer to
Lumena without giving the model execution authority.

## Provenance

- Model family: Convai Innovations Laya.
- Runtime path used on Android/Termux: pinned `shpati/laya.cpp`.
- Pinned runtime commit:
  `941e64863193c1290bffece6c22f8ac828dffecd`.
- Runtime license: Apache-2.0.
- Checkpoint used by this first Android path:
  `convaiinnovations/laya` (English, ModernBERT-large, 421M parameters).

The English checkpoint is sufficient for the first Lumena integration because
the input to Laya is not the raw user prompt. Lumena converts a recovery event
into a small language-neutral/English control state consisting of:

- failure source and class,
- retryability,
- effect class,
- dependency/action family,
- attempt number,
- unknown-effect bit,
- error code,
- Constitution-selected anchor,
- the already-authorized bounded recovery candidates.

Raw user prompts, tool stdout/stderr, and model prose are not sent through this
shadow path.

## Why Termux sidecar instead of bundling the weights in the APK

The current C runtime expands the 421M checkpoint to float32 in memory and its
own documentation reports roughly 1.9 GB RSS. Bundling the checkpoint into the
APK would also make the app artifact hundreds of megabytes larger and couple
model/runtime updates to APK releases.

Instead:

```
APK / WorkflowRunner
    |
    | authenticated loopback Bridge
    v
127.0.0.1:8765
    |
    | internal laya.predict
    v
127.0.0.1:29417
    |
    v
pinned laya.cpp + local Laya checkpoint
```

Both services bind only to loopback. Laya is optional; Lumena remains fully
functional when it is absent.

## Install on Termux

After installing Bridge v0.27:

```bash
~/.lumena/install_laya_system1.sh runtime
~/.lumena/install_laya_system1.sh model
~/.lumena/install_laya_system1.sh start
~/.lumena/install_laya_system1.sh status
```

Or:

```bash
~/.lumena/install_laya_system1.sh all
```

The installer downloads `laya.c` and its Apache-2.0 license from the pinned
commit, compiles it with Termux clang, downloads the original checkpoint
artifacts, and starts the service on `127.0.0.1:29417`.

## Authority boundary

Laya is deliberately **not** registered in `ToolRegistry`.

The deliberative model therefore cannot request `laya.predict` as a normal
tool. Only Lumena's controller-side code can call the internal Bridge surface.

Laya receives a `ReflexCandidateSet` that has already been bounded by
`ConstitutionKernel`. It cannot:

- add a candidate,
- construct a tool call or arguments,
- approve a mutation,
- bypass ToolGate,
- bypass user confirmation,
- alter retry budgets,
- mark a task verified.

Any Laya response whose probability keys do not exactly match the
constitutional candidate set is rejected fail-closed.

## Shadow mode

The live recovery recommendation still comes from the existing chain:

```
verified Coordinator experience
    -> ReflexExperienceRanker
    -> TinyJev bounded rerank
    -> ReflexRuntimeAdvice
```

In parallel:

```
same FailureEvent + same ReflexCandidateSet
    -> Laya
    -> typed choice + probabilities + confidence
    -> LayaShadowStore only
```

The Laya result does not affect the live option in v1.

## Persisted telemetry

`LayaShadowStore` stores only bounded data:

- hashed task identity,
- action family and attempt,
- constitutional anchor,
- current reference option,
- Laya option and probabilities,
- reported confidence,
- latency,
- availability/error code.

It stores no prompt, tool output, secrets, or generated prose.

Diagnostics expose:

```
[LAYA_SYSTEM1]
mode=SHADOW
authority=advisory_only
laya_configured=...
laya_ok=...
laya_stdout=...
shadow_samples=...
shadow_successful=...
shadow_unavailable=...
agreement_with_reference=...
mean_confidence=...
latency_p50_ms=...
latency_p95_ms=...
last_choice=...
last_error_code=...
```

`agreement_with_reference` is explicitly **not accuracy**. The current
reference is the existing advisory recommendation, not verified ground truth.

## Promotion criteria

Do not promote Laya out of shadow mode just because it agrees with TinyJev.

A later stage must bind Laya predictions to the same proof-typed verified
recovery examples used by TinyJev calibration. Promotion to advisory influence
should require, at minimum:

1. enough resolved verified samples per action family,
2. bounded calibration error,
3. acceptable Brier score,
4. stable p50/p95 latency on the phone,
5. no authority-expansion or stale-state violations,
6. independent post-action verification,
7. fail-closed fallback to Constitution/TinyJev/Gemma.

Only after those gates should a separate change consider letting Laya reduce
Gemma model turns for low-risk read-only recovery.

## Known limitations

- The initial Android path uses the English 421M checkpoint because the current
  C runtime supports that architecture. A multilingual mmBERT checkpoint needs
  a validated runtime implementation before it can replace this path.
- The C runtime currently expands weights to float32, so memory use is much
  larger than the stored FP16 checkpoint.
- This stage does not claim Android latency parity with MLX/Apple Silicon.
  Phone latency and thermal behavior must be measured on-device.
- A Laya `DONE`-like recommendation is never proof of task success. Lumena's
  Evidence/Verifier/Constitution layers remain authoritative.
