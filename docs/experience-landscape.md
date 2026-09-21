# Experience landscape and working constitution v1

This layer learns **conditional tool-execution reliability**, not whether the
whole user goal was achieved. It does not train model weights, implement latent
reasoning, generate new executable procedures, or change permissions.

## Initial project DNA

`lumena-core-v3` is an eight-principle engineering seed: goal/constraints (G),
one protocol action (P), execution evidence (E), same-target verification (V),
bounded recovery (R), resource/context limits (M), authority boundaries (A), and
sources/uncertainty (S). V1 introduced the first seven; v3 adds the source rule.
Every short code is accompanied by its plain-language meaning in the agent
prompt. These are project priors with zero fabricated observations, not claims
of learned success. The dynamic context includes this seed before learned
advice. The settings panel displays its version and principles. Rollback of
learned rules cannot weaken this seed or the deterministic controller gates.
This is a shared agent protocol, not a new tokenizer or A/C/G/T latent model.

## Evidence and scope

Each completed tool call records its task/request identifiers, exact argument
fingerprint, tool, elapsed wall time, success/exit status, short result excerpt,
and the corresponding Context Genome event identifier. Model prose is never
accepted as an observation. Error details are displayed as evidence, never
interpolated into learned instructions. Duplicate deliveries are ignored.

A scope includes backend, model reference, requested compute mode, tuning,
endpoint, Android build/device fingerprint and app version code. Task intent
adds a separate classification. Different scopes never share active advice.
Scope is based on model reference, not a checksum of multi-gigabyte weights;
replacing a file in place can leave the reference unchanged. Workspace and
remote software changes are not automatically detected. All advice therefore
requires checking current applicability.

Legacy genome observations lack these conditions and are not backfilled with
invented scores. The existing genome remains available alongside the new layer.

## Nodes, transitions and scores

One node represents an exact tool/argument combination within a scope and task
class. A task contributes at most one reliability vote per node; any failure
in that task makes its vote negative even if a retry succeeds. Distinct task
IDs are a practical diversity check, not proof of statistically independent trials.

The displayed height is a heuristic, not a probability:

`100 * (2 * (successful_tasks + 1)/(all_tasks + 2) - 1)`

minus a bounded log penalty for median execution time. Frequency increases
evidence confidence rather than adding popularity points. Token cost, RAM,
energy and total-goal success are **not measured by this score**.

Edges connect consecutive observations only within the same task, scope and
class. An edge is successful only when both calls succeeded; repeated instances
of the same edge in one task count once, with failure taking precedence. Edges
are observational correlations, not causal proofs or executable macros.

The retained evidence window is at most 1024 observations and 30 days. Scores
are recomputed from retained evidence; expired/evicted support cannot justify
an active rule. Caps bound the controller's own storage and prompt overhead.

## Rule lifecycle and authority

Only an explicit allowlist of inspection/verification tools can produce rules.
There are two trusted templates: consider a repeatedly successful procedure,
or recheck prerequisites before repeating a failing procedure. Arbitrary
executables and mutations cannot promote themselves into rules.

Three supporting task votes produce a candidate. Automatic activation requires
at least eight supporting tasks, a 95% Wilson lower bound of at least 0.65 for
that outcome, and no contradiction in the latest observed task. These are
conservative engineering thresholds, not calibrated guarantees of future success.

A contradiction immediately removes the relevant rule from the active set.
Reactivation requires the evidence thresholds again. Pausing promotions still
allows demotion. User exclusions survive new evidence and rollback.

Active advice is supplied through the existing memory context budget, at most
three lines per turn. It cannot grant approval, bypass ToolGate/ToolRegistry,
change failure budgets, suppress required verification or execute an action.
Fixed controller rules and current user instructions retain authority.

## Persistence, review and rollback

`lumena_landscape.db` stores a bounded state snapshot and up to 32 immutable
constitution versions. State and version changes share one SQLite transaction.
Context Genome evidence is committed first; if the landscape update fails the
workflow reports `EXPERIENCE NOT SAVED` and no rating is invented. The two
databases are not one atomic transaction; an orphan genome event is possible.

The Settings panel shows classes, peaks/pits, transitions, candidate/active/
contested rules, source event references, and version history. Users can disable
rules or pause promotion. Restoring a version restores only its still-eligible
active set, preserves manual exclusions, and pauses new promotions. It creates
a new version rather than rewriting history. The initial empty version provides
a baseline while it remains among the 32 retained versions.

Clearing verified experience also clears the landscape and its constitution.
Corrupt storage is reported, not silently replaced by an empty successful state.

## Validation

Pure policy tests cover duplicate delivery, task-level vote isolation, replay,
contradiction and expiry, scope isolation, unknown tools, data-to-instruction
separation, transitions, costs, retention, rollback, exclusions, paused promotion
and serialization. Android CI compiles the store, runner integration and Compose
panel. On-device SQLite/UI behavior and actual improvement in task completion
remain to be measured; no performance improvement is claimed from these tests.
