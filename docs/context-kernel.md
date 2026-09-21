# Local context kernel v1 / Core DNA v3

The cognitive exoskeleton is a deterministic runtime around a fallible model.
It preserves task-local execution evidence separately from model prose and
learned experience. It does not change model weights or imply consciousness.

## Common structure at three scales

Task -> phase (observe, act, verify) -> action evidence uses a shared task state.
The phase is classified by the tool registry, not awarded by the model. This
is a bounded hierarchy, not an arbitrary recursive planner or autonomous swarm.
An executed Python program is an action, not automatically a verification of
all requested outcomes. Existing same-file verification gates still apply.

The task-local ledger retains at most 48 records, each with an evidence ID,
tool, target, exact-argument fingerprint, outcome, world revision, output digest
and a 300-character excerpt. These are execution facts, not new instructions.
The prompt capsule uses up to 1800 characters and four recent records; full
retained records remain in local task state and the atomic checkpoint. This is
lossy context selection, not lossless compression or a measured token saving.
The panel groups evidence by phase and shows remaining tool steps. It does not
invent a remaining-token or RAM value.

## Completion and progress

Build 23 handles nonempty plain replies during tool work with one protocol
correction that explicitly permits done, partial, or the next tool. If the model
still returns prose, the controller preserves it as an explicitly unverified
PARTIAL report rather than a protocol crash. This does not award success, execute
text, clear pending verification, or discard an unknown-operation marker.
Malformed tool/protocol envelopes still fail closed. Empty responses cannot
complete even ordinary conversation. The verified-image shortcut now observes
the same kernel completion blockers as explicit done.

This improves graceful degradation, not proof of news accuracy or a cure for
provider failures. Inspect TOOL_RESULT to distinguish search failure from output
format failure. Build 22 installations do not contain this behavior; Bridge 0.18
does not need replacing for this Android controller fix. The signing-continuity
prerequisite in the roadmap still applies to installing a subsequent APK.

`partial` is a distinct protocol output and terminal task status. It can report
unfinished verification without pretending that the task succeeded. A final
model turn can choose done or partial at the tool limit. Unknown outcomes and
the most recent failed execution block done. Arbitrary natural-language goal
completion remains model-dependent; one success does not prove the whole goal,
and this version is not a full semantic completion validator.

Short plans no longer shrink an allocated budget. A model plan receives up to
two tool calls per listed item plus two reserve calls, within the existing
12-call ceiling. Two identical successful read observations at the same world
revision cause the next duplicate to be rejected with bounded corrective
feedback, even when separated by other read calls. An attempted mutation
advances the world revision even when it fails, because partial effects are
possible. External changes are not detected automatically; time-based polling
is not a supported monitoring mode of this finite agent loop.

Tool envelopes are checked for mismatched tool names and nonzero exit status.
A claimed successful batch also requires nonempty successful child results.
Transport errors for mutating/executable/unknown tools are marked outcome-unknown
and terminate the run; they do not produce an experience vote. Build 22 permits
one explicit retry for READ_ONLY calls only. A final failed read closes its marker
with a failure (no usable result), allowing bounded recovery. Neither path promises
exactly-once execution across a network failure. See [web-search.md](web-search.md).

## Durable local state

Before dispatching any tool, an AtomicFile checkpoint records the task's
in-flight marker. A returned result closes that marker and is checkpointed
before further work. Checkpoint writes run on IO and must succeed before
dispatch. The newest checkpoint is restored only for the matching interrupted
task; no effect is automatically replayed and no previous approval is renewed.
The snapshot is one app-private file, not an unbounded execution log. OS or
storage failure can still leave the latest outcome unknown.

On a new request explicitly asking to continue, a bounded capsule from the
previous task is labelled historical. Fresh task counters and approval gates
apply, and the model must recheck the environment. This is assisted continuation,
not transparent resumption of an unknown operation. Old sessions without the
kernel field load with an empty ledger. Work reports include the capsule.

## DNA and learned landscape

The engineering priors are goal, protocol, evidence, verification, bounded recovery,
resources, authority, and (v3) sources/uncertainty. Core v2 introduced partial
completion and unknown-effect handling. The existing scoped experience landscape
continues to learn only from returned tool results, under its existing support,
expiry and manual-exclusion rules. Learned advice cannot modify kernel gates.

The Termux syntax checker now compiles source bytes without executing them or
writing pyc/__pycache__, removing one source of recursive cleanup work.

## Validation boundaries

Regression tests cover serialization of interruption, unknown-result retention,
completion gates, partial status, nonconsecutive duplicate reads, invalidation
after mutations, budget monotonicity, bounded quoted capsules, legacy sessions,
tool envelopes and syntax checks without filesystem side effects. Android CI
compiles checkpoint/UI integration. Actual process-kill recovery and weak-model
task-completion rates still need on-device evaluation.
