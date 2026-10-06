# Design trace: user-value weight on the Fractal Experience Canvas

Date: 2026-10-06  
Branch at implementation: `feature/exoskeleton-integrity-v1`  
Recorder / contributor model: `chatgpt:gpt-5.6-sol`  
Authority of this document: **engineering provenance only**. It is not evidence that the design is correct and grants no execution permission.

## User intent

The user asked to add a separate user-value weight to the fractal canvas and to leave an explicit trace of who introduced the change, so that later models or developers can identify, challenge, repair or revert it if it proves wrong.

## Decision

Add a **normative user-priority axis** that is strictly separate from:

- evidence confidence;
- BEST / WORST / CONTESTED state;
- causal verification;
- ToolRegistry / ToolGate authority;
- Goal Contract completion;
- Constitution promotion.

The weight is attached to an **existing deterministic fractal node** and is accepted only from an explicit user-sourced turn through app-owned code. A model name such as GPT, Opus, Gemma or Laya cannot create weight by reputation.

The adapter/model/UI that records the user's instruction is stored as `recordedBy` **only for provenance**. It does not multiply or otherwise affect the numerical weight.

## Weight semantics

Range: `-3..+3`.

- `+3`: high user priority.
- `0`: neutral / revoke the previous preference.
- `-3`: low user priority.

A newer explicit value supersedes the older active value for the same node. Older records remain in bounded audit history as `SUPERSEDED`.

If compaction or reprojection temporarily removes the target node, the audit record is retained but has no ranking effect while the node is absent. It must not be silently deleted just because a derived node cache changed.

No raw user turn is stored in the value record. A 24-hex SHA-256 prefix is stored as `sourceTurnHash` so a later audit can correlate the record without persisting the whole sentence in this layer.

## Ranking rule

User value is a **tie-breaker only**.

Current ordering for relevant fractal nodes is:

1. semantic overlap with the current query;
2. number of distinct supporting tasks;
3. explicit user-value weight;
4. evidence confidence;
5. recency.

This deliberately prevents a normative preference from outvoting stronger independent evidence. The weight must never modify `supportCount`, `failureCount`, `distinctTasks`, `confidence`, `peak` or causal stages.

## Why exact-node weighting

The first version does **not** infer which abstract value a sentence refers to and does not spread a user's weight semantically across unrelated nodes. Automatic semantic propagation would let a model turn vague language into hidden normative influence.

A future implementation may add value families, but only if:
- the mapping is inspectable;
- the user can correct/revoke it;
- evidence confidence remains separate;
- the mapping has adversarial tests.

## User commitments boundary

During this audit I found a separate safety conflict: earlier direct user commitments such as "do not touch config.json" were routed through `AdvisoryLayer.COMMITMENTS` and could be WITHHELD by the Layer Governor.

That conflicts with the project's own invariant that user constraints are never experimental. The implementation therefore removes binding commitments from the governed layer set and always preserves their reminder when active.

This is intentionally separate from user-value ranking:
- **constraint / commitment** = binding user instruction, never randomized;
- **user-value weight** = normative priority among already-observed advisory nodes, never execution authority.

## What would falsify this design

Treat the design as wrong or incomplete if any of the following happens:

1. Changing a user-value weight changes evidence confidence, peak classification or causal stage.
2. A model can assign a weight without an explicit user-sourced record.
3. A high user weight makes a weaker-evidence node outrank a node with more independent-task support.
4. The weight grants a tool permission, bypasses confirmation, satisfies Goal Contract or promotes Constitution.
5. Revoking a value silently deletes the prior audit record instead of superseding it.
6. StateVault round-trip loses active weights without an explicit migration reason.
7. A binding user constraint can still be withheld by Layer Governor.
8. `recordedBy` changes ranking or authority.

## Repair / rollback path

If the ranking effect proves harmful:

1. Remove the user-weight comparator from `FractalExperienceCanvasPolicy.relevant`.
2. Keep decoding and preserving `userValueWeights` so historical provenance is not silently destroyed.
3. Mark the feature disabled in Diagnostics / documentation.
4. Do not reinterpret stored weights as evidence.
5. Add a migration only after the new semantics are explicitly defined and tested.

If exact-node weighting is too narrow, add a new versioned mapping layer rather than mutating old records in place.

If the whole concept is rejected, retain this document and the superseded records as historical provenance so future audits know why the change existed.

## Tests required for acceptance

The implementation must keep tests proving:

- explicit user weight does not change evidence confidence or peak;
- a newer value supersedes rather than deletes history;
- stronger independent evidence outranks user preference;
- user preference breaks only a genuine tie;
- contributor/model identity alone creates no weight;
- nonexistent nodes cannot receive weight;
- there is no model tool such as `fractal.user_weight` that can self-assign weight;
- encode/decode preserves a real canvas user-value record;
- binding commitments are not an `AdvisoryLayer`.

## Important non-claim

No runtime phone episode was fabricated to "prove" this change. This document and Git history are the contribution trace. Phone behavior remains unverified until an APK containing these commits is installed and tested on the device.
