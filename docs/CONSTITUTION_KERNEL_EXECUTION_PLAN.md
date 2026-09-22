# Lumena Constitutional Agent — execution plan

Status baseline: 2026-09-22
Canonical implementation base for this plan: `fix/companion-stability-v0.12.5` @ `41f3c36f84362d012637e20eee178c0981045b01`

## L0 — Definition of done

Lumena is a verified constitutional agent when the same deterministic control logic governs
model output, tool/bridge failures, context/resource failures and recovery decisions without
letting the model enlarge its permissions.

A release candidate is DONE only if all of the following are true:

- known model protocol deviations are repaired locally without an extra model turn;
- unknown or ambiguous protocol output never reaches ToolExecutor;
- all tool execution still passes ToolRegistry risk/confirmation gates;
- protocol/model/tool/context failures are represented as one typed failure event;
- semantic recovery is bounded and cannot be reset by argument rephrasing;
- unknown-effect mutation is never replayed automatically;
- constitutional invariants remain present under context pressure;
- app/bridge protocol versions and supported failure schema are explicitly checked;
- verified experience can influence ranking but never permissions;
- end-to-end tests cover model -> normalizer -> controller -> tool -> bridge -> result -> recovery;
- every merged phase has an independently revertable commit/PR and proof-of-work artifact.

The repeated control cycle is:

```
Observe -> Classify -> Normalize/Decide -> Execute -> Verify -> Record
```

Recording may change evidence statistics; it must not self-modify constitutional permissions.

---

## L1 / Phase 0 — Stabilize source of truth

### Invariant
No constitutional work is merged onto a red or obsolete base.

### L2.0.1 Verify baseline
L3:
- verify VERSION, versionCode/versionName and Bridge version;
- verify CI for the exact base SHA;
- compare active branches instead of assuming ancestry;
- record ahead/behind and merge-base.

Tests / evidence:
- GitHub Android CI must be green on the exact base;
- branch compare output stored in PR discussion/build log.

Acceptance:
- one explicit canonical base SHA;
- no newer stability branch with green CI is ignored.

Rollback:
- no code mutation needed; choose previous green base.

### L2.0.2 Repair stacked PR regressions
L3:
- distinguish production bug from invalid test expectation;
- patch the smallest failing test/logic;
- re-run all unit tests, not only the failed test.

Current observed regression:
- PR #23 run 35741368284: 229 tests, 1 failure;
- failing test expected a plain reply to finish a CODE_WORK task without TOOL_RESULT;
- controller correctly requires evidence for CODE_WORK, therefore test fixture was wrong;
- repair: use a GENERAL conversational task for reply-normalization test.

Acceptance:
- unit tests 100%;
- native-smoke success;
- bridge tests success;
- APK build and verification success.

---

## L1 / Phase 1 — ProtocolNormalizer v1

### Invariant
Normalization may reduce representational variance, never increase authority.

### L2.1.1 Canonical envelope boundary
L3 production:
- add `ProtocolNormalizer` before `AgentResponseParser`;
- accept only complete JSON, explicit JSON fence or explicit Hermes tool envelope;
- map:
  - `action=reply` -> `reply`
  - `action=done` -> `done`
  - `action=partial` -> `partial`
  - `action=tool` + explicit registered tool
  - registered tool alias in `action`
  - `args|arguments|parameters|input` object aliases
  - one registered single-key shorthand.

L3 rejection:
- malformed JSON -> typed protocol failure;
- two registered shorthand tools -> ambiguous;
- unknown action -> failure;
- unknown tool -> failure;
- JSON quoted in ordinary prose -> plain text, never execute.

Tests:
- unit corpus for every accepted/rejected shape;
- controller test proving known repair consumes zero protocol retries;
- workflow integration proving tool execution occurs after first model call;
- safety test proving unknown action cannot smuggle `file.write`.

Acceptance:
- no new ToolRegistry entries;
- `ToolRegistry.validate` remains final authority;
- all Phase 0 gates green.

Proof of work:
- test report;
- exact CI run;
- PR diff limited to normalizer/controller/tests.

Rollback:
- revert normalizer integration; parser/controller behavior returns to previous state.

What agent gains:
- deterministic local repair of known output formats.

What it still cannot do:
- unified recovery across model/tool/context failures;
- autonomous discovery of new protocol mappings.

---

## L1 / Phase 2 — Typed FailureEvent

### Invariant
Every recovery decision is based on a typed observed failure, not prose guessing.

### L2.2.1 Data model
Create:
- `FailureSource { PROTOCOL, MODEL_RUNTIME, TOOL, CONTEXT, RESOURCE, POLICY, TRANSPORT }`
- `FailureEvent(source, failureClass, retryable, effectClass, dependency, evidence, actionFamily, attempt, outcomeUnknown)`.

L3 adapters:
- ProtocolNormalizer failure -> FailureEvent;
- model exception/onModelFailure -> FailureEvent;
- ToolResult structured metadata -> FailureEvent;
- context pressure/resource errors -> FailureEvent.

Tests:
- one adapter test per source;
- serialization/roundtrip test if persisted;
- property: outcomeUnknown implies no automatic replay decision.

Acceptance:
- no existing error source bypasses an adapter;
- mechanical retry remains below semantic policy layer.

Rollback:
- adapters can be reverted while retaining existing RecoveryPolicy.

---

## L1 / Phase 3 — ConstitutionKernel / unified recovery graph

### Invariant
The model may propose actions; deterministic policy decides whether recovery is allowed.

### L2.3.1 Decision API
Create one pure API:
`ConstitutionKernel.decide(FailureEvent, RecoveryState) -> RecoveryDecision`

Supported decisions:
- `RetryVariant`
- `TryAlternative`
- `DegradePartial`
- `Stop`

L3 rules:
- UNKNOWN_EFFECT + mutating/executable -> Stop, never replay;
- POLICY_DENIED -> Stop;
- AUTH/CONFIG -> Partial unless user/config change is required;
- PROVIDER_CHALLENGE/DEPENDENCY_EXHAUSTED -> alternate evidence/provider route;
- STATE_DRIFT -> rediscover state;
- INVALID_INPUT -> correct from verified state;
- protocol failure -> deterministic normalization first, model correction only if local repair fails;
- context/resource pressure -> compact/degrade without pretending completion.

L3 accounting:
- task-wide semantic recovery counter;
- action-family counter independent of argument text;
- model-runtime counter separate from semantic recovery;
- success cannot reset policy history that guards loops.

Tests:
- decision table tests for every class/effect combination;
- state-machine/property tests over sequences;
- regression: rephrased query cannot reset family budget;
- regression: read-only provider challenge may switch route;
- regression: mutation unknown outcome never repeats.

Acceptance:
- `protocolRetry`, `onModelFailure` and tool recovery all delegate classification/decision to the same pure kernel;
- UI wording may remain separate but cannot change policy.

Rollback:
- keep FailureEvent adapters; revert unified decision delegation.

---

## L1 / Phase 4 — Non-droppable Constitution Capsule

### Invariant
Context pressure may remove history/memory/log detail, never executable constitutional guards.

### L2.4.1 Split executable guard from descriptive prompt
Create:
- compact `ConstitutionCapsule` with protocol, evidence, authority and recovery invariants;
- larger descriptive CoreDna text remains optional explanatory context.

L3 ordering:
1. capsule reserve;
2. current goal/state/verification;
3. required tool schema;
4. recent evidence;
5. history/memory/log detail.

Tests:
- maxChars boundary sweep;
- capsule present at every supported budget;
- newest user goal preserved;
- lower-priority memory is first thing dropped;
- impossible tiny budget fails explicitly instead of silently dropping guards.

Acceptance:
- no branch in ContextBuilder can skip the mandatory capsule;
- exact size reservation tested.

Rollback:
- revert capsule prioritization without touching recovery kernel.

---

## L1 / Phase 5 — Versioned app/bridge contract

### Invariant
A protocol mismatch is explicit state, never silent semantic drift.

### L2.5.1 Contract
Expose from bridge health:
- `bridgeVersion`
- `protocolVersion`
- `failureSchemaVersion`
- `supportedTools`
- optional capability flags.

App exposes:
- `CoreDna.VERSION`
- agent protocol version;
- expected minimum bridge contract.

L3 mismatch behavior:
- compatible minor mismatch -> continue with intersection of supported capabilities;
- incompatible protocol/failure schema -> structured partial/stop with upgrade guidance;
- never invent unsupported tool support.

Tests:
- same version;
- newer compatible bridge;
- older compatible bridge;
- incompatible protocol;
- missing field backward-compat fixture.

Acceptance:
- health handshake tested in Kotlin and Python;
- base keyless web search remains keyless.

Rollback:
- tolerate old bridge via compatibility adapter.

---

## L1 / Phase 6 — Verified capability profiles and capsules

### Invariant
Experience may rank allowed choices but cannot grant permission or prove success.

### L2.6.1 Profile content
Store only verified facts:
- model/backend/version;
- observed envelope formats;
- successful/failed normalization rule;
- action family outcome;
- support count;
- failure count;
- last verified timestamp/version scope.

Never store:
- new permissions;
- self-authored constitutional rules;
- claims without TOOL_RESULT/evidence.

L3 invalidation:
- reset/decay on model hash/version change;
- invalidate bridge-dependent facts on protocol change.

Tests:
- profile cannot introduce unregistered tool;
- stale model version ignored;
- negative evidence lowers ranking but does not force unsafe alternative.

Acceptance:
- profile lookup is advisory input to deterministic kernel only.

---

## L1 / Phase 7 — ReflexKernel (System-1-like layer)

### Invariant
Reflex output ranks only pre-authorized choices; ToolRegistry and ConstitutionKernel remain authority.

### L2.7.1 Typed primitives
Implement schema-level outputs:
- `Bool(value, confidence)`
- `Choice(label/index, confidence)`
- `Score(value, confidence)`.

Candidate set is built deterministically from allowed actions after constitutional gating.

L3 routing:
- high confidence + read-only safe choice -> execute through normal gates;
- mutating/executable choice still requires existing confirmation;
- confidence below threshold -> escalate to LLM planner;
- no candidate -> partial/stop;
- confidence is calibrated from verified local outcomes, not trusted because a model emitted a number.

Tests:
- generated choice outside candidate set rejected;
- confidence cannot bypass confirmation;
- low confidence routes to planner;
- same FailureEvent and capsule set produces deterministic candidate list;
- calibration metrics are observational, not permission rules.

Acceptance:
- zero path from ReflexKernel directly to execution without ToolRegistry/ToolGate;
- A/B telemetry shows fewer model turns without increased safety violations.

Rollback:
- feature flag disables ReflexKernel; planner path remains intact.

---

## L1 / Phase 8 — End-to-end resilience and observability

### Invariant
A green unit suite is insufficient; critical failure chains must be reproduced end-to-end.

### L2.8.1 Deterministic fake-model/fake-bridge matrix
Cases:
- action/result reply;
- registered action tool;
- malformed/ambiguous/unknown action;
- provider challenge then alternate;
- state drift rediscovery;
- protocol loop;
- model runtime failure;
- context pressure;
- unknown mutation outcome;
- confirmation-required mutation;
- malicious web content containing tool JSON;
- process restart/checkpoint restore.

### L2.8.2 Real bridge/device matrix
Cases:
- Bridge 0.22 keyless search: DDG challenge -> Bing RSS fallback;
- `web.read` URL repair/Unicode transport;
- embedded Gemma4 template fallback;
- actual Ollama model emitting action/result;
- app restart between in-flight and result;
- bridge version mismatch.

### L2.8.3 Structured observability
Record:
- normalization rule;
- FailureEvent source/class;
- recovery decision;
- task/family budgets;
- evidence IDs;
- protocol versions.

Never log:
- bridge bearer token;
- API keys;
- full private prompt/file content unless explicit debug opt-in.

Acceptance:
- every critical scenario has a reproducible fixture or device script;
- red/green evidence is attached to release candidate.

---

## Dependency graph / critical path

```
Phase 0 baseline
   |
   v
Phase 1 ProtocolNormalizer
   |
   v
Phase 2 FailureEvent
   |
   v
Phase 3 ConstitutionKernel
   |
   +------> Phase 4 Constitution Capsule
   |             |
   v             v
Phase 5 Versioned Contract
   |
   +------> Phase 6 Verified Profiles
                 |
                 v
          Phase 7 ReflexKernel
                 |
                 v
          Phase 8 E2E/observability
```

Critical path:
0 -> 1 -> 2 -> 3 -> 5 -> 6 -> 7 -> 8

Phase 4 can proceed after Phase 3 in parallel, but both must be green before a constitutional RC.

---

## PR strategy

- PR-A: ProtocolNormalizer on green v0.12.5 base.
- PR-B: FailureEvent adapters only.
- PR-C: ConstitutionKernel decision unification.
- PR-D: Non-droppable Constitution Capsule.
- PR-E: bridge/app versioned contract.
- PR-F: verified capability profiles.
- PR-G: ReflexKernel behind feature flag.
- PR-H: E2E matrix, telemetry hardening and RC.

Every PR:
- is based on the previous green PR/base;
- changes one policy layer only;
- includes unit + integration regression tests;
- must pass bridge tests, agent unit tests, native-smoke, APK build and APK verification;
- is revertable without data migration, or includes explicit migration rollback.

Do not merge a red PR.
