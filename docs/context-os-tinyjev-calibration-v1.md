# Lumena Context OS — TinyJev Calibration Kernel v1

## Purpose

Stage B makes TinyJev measurable before it becomes more autonomous.

The calibration path is deliberately separated from execution authority.

## Ground truth rule

A raw \`ToolResult.ok\` is **not** a calibration label.

TinyJev predictions are first stored as pending. They become calibration samples only when \`CoordinatorExperienceStore\` derives a verified \`RECOVERY\` example for the same task and failed tool family, with non-empty verified evidence IDs.

This preserves the existing evidence invariant:

\`\`\`
prediction
  -> pending
  -> real tool sequence
  -> Coordinator verified recovery
  -> calibration label
\`\`\`

Old recovery examples cannot retroactively label a newer prediction.

## Persisted data

\`lumena_tinyjev_calibration.json\` is app-private and atomic.

Stored:

- hashed task identity
- canonical tool family
- TinyJev model version
- bounded option probabilities
- selected option
- raw confidence
- local decision latency
- verified evidence IDs
- resolved label and timestamp

Not stored:

- raw prompts
- raw tool stdout/stderr
- secrets
- permissions
- user content beyond bounded structural IDs

Corruption fails closed instead of silently erasing calibration history.

## Metrics

The kernel computes:

- accuracy
- normalized multiclass Brier score
- ECE with 10 confidence bins
- selective coverage at the current 0.75 advisory threshold
- selective accuracy
- decision latency p50
- decision latency p95

## Calibration estimate

Calibration remains disabled until enough resolved samples exist.

Default gates:

- at least 12 resolved samples in the tool family, otherwise global fallback
- at least 3 samples in the relevant confidence bin

Reliability is estimated with Beta(1,1) smoothing.

The calibrated value is conservative:

\`\`\`
calibrated = min(raw confidence, smoothed empirical reliability)
\`\`\`

Stage B exposes this value separately from the existing reflex gate confidence.

It does **not** yet change tool execution, confirmation, ToolGate or Constitution authority.

## Current supported labels

Coordinator recovery structure currently provides reliable labels for:

- \`RETRY_VARIANT\`: failed operation succeeds again with no intermediate alternative step
- \`TRY_ALTERNATIVE\`: verified recovery contains intermediate repair/discovery steps before the original operation succeeds

\`STOP\`, \`ASK_PLANNER\` and \`DEGRADE_PARTIAL\` remain unlabelled by this store until a proof-typed ground-truth source exists for them.

## Go/no-go for Stage C

Do not enable a TinyJev fast path until phone/replay evidence shows:

- sufficient calibrated family coverage
- no wrong-action regression
- acceptable Brier/ECE
- meaningful latency/token savings
- current freshness and verifier checks
- no policy/confirmation bypass
