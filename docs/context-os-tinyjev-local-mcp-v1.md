# Lumena Context OS v1 — TinyJev + Local MCP

## Goal

Embed a local System-1 decision layer and a local MCP data layer directly in the Lumena APK without giving either one execution authority.

This is intentionally an incremental step on top of the verified Step 8.7 evidence/constitution stack.

Base functional head:

- \`763b04af7ec2012f21a0f0db5d081f921ae617f0\`
- PR #86: aggregate EvidenceGraph outcome remains monotonic while independent bindings continue PENDING -> APPLIED -> VERIFIED.

## What is implemented in this step

### 1. TinyJev v1

\`TinyJev\` is Lumena's APK-local System-1-style scorer.

It is **not** the proprietary TypeSafe Jev model.

It is a deliberately small auditable linear/softmax scorer:

- no network
- no API key
- no background service
- no tool executor
- no permission API
- no direct mutation path
- model weights are shipped inside the APK in \`assets/tinyjev-v1.properties\`
- fallback weights are compiled into code so a corrupt/missing asset fails safe to a known local model

Current use:

- TinyJev only re-ranks recovery options that:
  1. are already admitted by \`ConstitutionKernel -> ReflexKernel\`
  2. already have verified local support from \`ReflexExperienceRanker\`

It therefore cannot invent a new action family and cannot expand authority.

Raw softmax confidence is explicitly marked \`calibrated=false\`.

### 2. Local MCP runtime

\`LocalMcpRuntime\` implements the current MCP 2026-07-28 data-layer shape for the APK-local host:

- \`server/discover\` semantics through \`discover()\`
- \`tools/list\` semantics through \`listTools()\`
- \`tools/call\` semantics through \`callTool()\`
- protocol version: \`2026-07-28\`
- stateless
- in-process
- no network listener
- no new port
- projects the existing \`ToolRegistry\`
- forwards read-only execution to the existing \`ToolExecutor\`

Mutating or executable calls do **not** run directly. They return \`input_required\` so a confirmation-capable host flow can remain authoritative.

This keeps the current safety boundary:

\`\`\`
TinyJev / MCP
      |
      v
bounded proposal / tool request
      |
      v
ToolRegistry -> ToolGate -> Constitution / confirmation
      |
      v
executor
\`\`\`

## Why the model is intentionally small

The first goal is not general language understanding. The goal is low-cost bounded decisions such as:

- retry vs alternate provider
- retry vs planner escalation
- stop on unknown effect
- stop on policy denial
- switch route after provider challenge
- escalate under context/resource pressure

A large 4B-9B model is wasteful for this class of decision.

TinyJev v1 uses a compact feature vector and softmax ranking. Its parameter file is tiny enough to live directly in the APK.

## Rollout plan

### Stage A — current PR

- [x] APK-local TinyJev core
- [x] embedded model weights
- [x] Android asset loader
- [x] safe re-ranking integration into the existing reflex pipeline
- [x] APK-local MCP 2026-07-28 core
- [x] read-only MCP execution
- [x] side-effect calls fail closed to \`input_required\`
- [x] unit tests

### Stage B — calibration

Add a persistent \`TinyJevCalibrationStore\` fed only from verified outcomes.

Track by decision family:

- Brier score
- ECE
- selective accuracy
- coverage
- wrong-action rate
- fallback rate
- p50/p95 latency

Only after enough verified samples should a score be exposed as calibrated.

### Stage C — System-1 fast path

Allow a calibrated TinyJev decision to bypass the large planner only when all of the following are true:

- candidate set is bounded by Constitution
- risk class allows automation
- empirical calibration passes threshold for that decision family
- freshness/precondition check passes
- no user confirmation is required
- verifier exists for the expected effect

High-risk actions remain confirmation-gated regardless of confidence.

### Stage D — learned small model

Replace or augment the linear scorer with a genuinely trained small local model using replay data:

\`\`\`
(state, bounded candidates, selected candidate, verified outcome)
\`\`\`

Possible deployment targets:

- tiny MLP / linear model
- small encoder
- sub-1B GGUF classifier if the measured gain justifies memory/latency

The provider interface must remain stable so model replacement does not affect policy authority.

### Stage E — MCP transport

Current Local MCP is in-process by design.

A later adapter may expose:

- local stdio-compatible endpoint
- loopback-only Streamable HTTP
- remote MCP client connections

Any transport layer must remain outside the authority path and preserve existing confirmation/policy checks.

## Go / no-go gates

TinyJev should gain more autonomy only when replay/phone tests show:

- lower median latency than the large planner
- materially lower token cost
- no increase in wrong-action rate
- calibrated confidence over the intended coverage range
- no authority bypass
- no regression in Step 8.7 verification continuity

Until then, TinyJev remains an advisory bounded scorer.

## Security invariants

1. Model confidence is not permission.
2. Model prose is not evidence.
3. MCP tool discovery does not imply authorization.
4. Mutating/executable MCP calls cannot execute without the existing approval path.
5. Unknown effect never counts as success.
6. TinyJev cannot add candidates that Constitution did not admit.
7. TinyJev cannot use unverified model output as training proof.
8. Hard Constitution authority remains code-owned.
