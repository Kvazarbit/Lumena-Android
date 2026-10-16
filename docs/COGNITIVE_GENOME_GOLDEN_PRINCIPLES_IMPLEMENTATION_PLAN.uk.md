# Lumena Cognitive Genome: Golden Principles / Negative Experience — V0–V3

Repository: Kvazarbit/Lumena-Android.
Working branch: feature/fractal-cognitive-genome-v0.
Baseline: PR #128, ab60a50ad84704cf17b327bb1568d0eaa2b533e4.
Status: V0 code implementation and CI/phone validation; V1–V3 PLAN.
Do not merge without the owner's explicit permission. GitHub CI is not phone E2E.

## 1. Product goal and evidence boundary

Lumena should become a persistent cognitive contour for different AI models:
a stronger model can propose a general strategy; Lumena remembers the
conditions, evidence, counterexamples and tests; a weaker model can use the
strategy on a new project without receiving the whole old chat.

Represent SOLUTION PRINCIPLES rather than only successful commands. Persist
both POSITIVE and NEGATIVE experiences, hidden dependency hypotheses, dead-end
routes, successful recoveries and contradictory examples, with proof provenance.

"100% verified positive result across all modern models" is not an empirical
claim we can establish. A finite number of passing tests proves only scoped
properties of the tested implementation; universal model performance cannot
be assumed. Keep two disjoint classes:
- LOCAL KERNEL CONTRACTS ("golden core"): normative, enforced and testable
  implementation invariants, never learned from popularity or success rate.
- EMPIRICAL PRINCIPLES: scoped hypotheses and evidence-supported strategies,
  which are explicitly defeasible and never become HARD automatically.

Do not mistake model self-report, Fractal BEST peak, many supporters,
high probability, a passing tool or a repeated command for independent success.

## 2. DNA analogy: four cognitive atoms

O = OBSERVE: current state, sources and measurements.
H = HYPOTHESIZE: a testable causal explanation (not a fact).
A = ACT: a proposed allowed operation, subject to owner-controlled ToolGate.
V = VERIFY: a postcondition, checked separately from narration.

Versioned codec V0: CG0:OHA.V encodes [O,H,A,V]. Three-letter groups
resemble codons; a final incomplete group is canonical. No other letters,
noncanonical grouping, unknown versions or streams longer than 48 atoms pass.

The codon sequence is only a type skeleton and is reversible. Metadata holds
the actual conditions, causal statement, procedure, evidence references,
failure classes, contradicting examples, expected outcomes and version.
Four bases alone contain only 2 bits per atom and do not compress arbitrary
natural language or magically improve correctness. Test end-to-end data size,
tokens and measured task outcomes versus unencoded structured text.

## 3. Golden core: immutable local catalog (V0)

Seed four test-anchored, model-neutral contracts:
- G1: model prose cannot create TOOL_RESULT (ContextKernel).
- G2: model, experience or import cannot grant execution rights (ToolGate).
- G3: successful tools do not alone satisfy a user goal (GoalContract).
- G4: UNKNOWN and CONTESTED are not positive learning labels (evidence policy).

Each contract names local tests but their names are not execution receipts.
This catalog grants NO permissions and changes neither Constitution Charter
nor execution policy. "Golden" is a local normative designation, not a
universal 100%-success score. Reviewer approval and direct tests remain required.

Ordinary reusable heuristics, including observe-before-write,
verify-after-write, prefer reversible action and do-not-blindly-repeat,
begin as advisory candidates and can be falsified by new conditions.

## 4. Existing Fractal evidence is source of truth

V0 derives a read-only projection of FractalExperienceCanvasState.records and
FractalCausalExperiencePolicy.links. No second store, state migration, or
rewriting the owner's saved 512-record/1024-node Canvas.

Stored principle fields: stable ID, stage, codon skeleton, mechanism,
conditions/scope hash, normalized tool sequence, source task hashes,
evidence IDs, counterexample IDs, sanitized structured failure classes,
dependency fingerprints, observed successes/failures and an explicit number
of independent goal proofs (zero in V0).

- POSITIVE_CANDIDATE: successful tool sequence with actual evidence IDs;
  not proof of independent user-level utility.
- NEGATIVE_DEAD_END: failed tool sequence; keep structural failure data and
  source evidence. Negative labels describe observed conditions, not universal
  bans on this tool.
- RECOVERY_CANDIDATE: verified step outcomes show a recovery after failure;
  keep the earlier failed path rather than replacing it.
- CONTESTED: success and failure of the same scoped tool+target shape;
  never majority-vote away a counterexample.
- KERNEL_CONTRACT: immutable local obligations, not derived from usage.

Unknown hidden causes cannot be invented: a latent mechanism is HYPOTHESIS
until independently probed. Failure classes are only structured tool data;
dependencies are hashed; never put raw private tool output/model prose into a
principle or into a new model's prompt.

Bounded projection max 256 rules: contract, negative/contested, recovery, then
positive candidates. This intentionally favors retaining cautionary evidence
but is NOT yet a scalable long-term database; original Fractal caps remain.

## 5. Verification and promotion ladder

L0 local contract specification (with actual test coverage on exact HEAD).
L1 historical verified tool receipt, good or bad.
L2 hypothesis with structured source failure.
L3 falsifiable cause probes; independent signatures for VERIFIED.
L4 independent goal postcondition, target artifact hash and test version.
L5 transfer to unseen models/projects/devices with revalidation.
L6 measured improvement in preregistered, paired A/B trials.

Never promote L1 to L4 or L5 based on a large count. A new counterexample
must demote or contest the advisory principle without erasing past evidence.
Model identifiers are provenance, not independent judges. Imported examples
are advisory only until local replay; no self-authored permissions.

## 6. Realistic negative curriculum (design fixtures, NOT auto-import)

- Long-lived aquarium server entered through bounded python.run → timeout.
  Principle candidate: distinguish short task from managed service lifecycle,
  and verify process + port + functional API after permission approval.
- HTTP 403 without permitted Origin → read policy, retain authorization.
- HTTP 404 from a missing .cjs dependency → validate served asset graph.
- Pseudo JSON inside Gemma prose → reject execution, require canonical tool
  protocol and TOOL_RESULT.
- Old Marketplace tool history marked false-corrupt when module disabled →
  historical evidence is independent of CURRENT tool eligibility.
- Aquarium reward favors motion over food for some conditions → evaluate
  reward proxies using independent ecological/business outcome measures.

These are illustrations from past work. They do not enter a verified skill
store unless evidence lineage is actually present in local sources. Negative
paths remain scope-limited; do not globally prohibit a tool due to one failure.

## 7. Detailed delivery plan and acceptance gates

### V0 — NOW / implementation in this PR
Code: settings/CognitiveGenomeV0.kt, its regression tests, read-only
FractalExperienceCanvasStore projection/stats, separate diagnostic section
COGNITIVE_GENOME_V0; docs and roadmap reference.

Acceptance: strict lossless four-atom codec, 4 local contracts, no automatic
"golden" promotion, success/negative/contested/recovered projection, no
evidence → no candidate, no model prose in suggestions, negative survival
under positive-heavy compaction, saved Canvas roundtrip, zero new authority.
Unit tests + exact-head CI + native smoke + unsigned Android build.
Phone acceptance only after verified StateVault backup, owner-signed update,
no app-data reset, and diagnostic showing historical Canvas still readable.

V0 intentionally DOES NOT auto-inject new skills into model prompts:
such exposure must remain governed and measured, not silently increase
permissions or invalidate LayerGovernor experiments.

### V1 — structured transferable principle records (PLAN)
Implement typed validity scopes, dependencies, precondition predicates,
expected postcondition/verifier, environmental fingerprint, revision,
owner review, expiry and revocation. Independently verify code artifact hashes
and exact target coverage; store provenance in an indexed append-only ledger.
Safely migrate old StateVault and Fractal data, with rollback.
Add stale/revoked/contested/unknown examples, adversarial input tests,
duplicate suppression and changing-module-state regression.

Beyond current MAX_RECORDS=512/MAX_NODES=1024, build a scalable indexed archive:
hierarchical compaction must preserve primary failure evidence IDs and
conflicting branches. Index summaries from immutable provenance rather
than silently rewriting history. Test 10,000+ synthetic episodes with
storage, CPU, battery and replay latency budgets before enabling on Android.

### V2 — model-neutral transfer and measured utility (PLAN)
Different models may propose hypotheses, but only a local independent
verifier may assign success. Retrieve only principles whose preconditions
match the current project and branch, preserving tool-permission separation.
Measure stronger-model-created tip -> weaker-model reproduction under exactly
the same verifier, tools and rights, with hidden new tasks and counterexamples.

Design a preregistered memory OFF / frozen-genome ON paired A/B with
counterbalanced order. At least 20 independent tasks in each of three
families: Python, JS/Kotlin and formal/normative reasoning. Record accuracy,
false DONE, missing verification, repeated failures, unauthorized actions,
tokens, latency and manual intervention. The existing Blind A/B PR #126 is
a separate whole-system pilot and does not itself prove this specific layer.

### V3 — million-LOC software and large coherent constitutions (PLAN)
Software: incremental AST/API graph, dependency maps, branch/commit provenance,
change-impact search, interface invariants, per-module tests, checkpoint
replay, rollback, parallel model workers and guarded PC/CI compute.
Gradually validate at 10k, 100k, then 1M LOC; never require the full source
inside one model's context. Measure retrieval recall, test coverage and
cross-module regression detection.

Constitution: typed clauses with scope, precedence, exceptions, references,
authority and accepted revisions. Dependency/conflict graphs and optional
SAT/SMT checks for a *formally specified subset*. Ambiguous natural-language
norms remain flagged for human review. Learned principles cannot change the
owner's HARD clauses without explicit approval and independent checks.
Measure deliberate conflicts, false acceptance and consistency after edits.

## 8. Governance, privacy and release gates

Phone/ToolGate remains execution authority. No unapproved process launch,
shell access, permission amplification or signer access. Never overwrite,
uninstall, reset Canvas or restore older state without owner approval.
Keep backups, SHA-256, versioned schema and exact branch/CI linkage.

CI green is code test evidence, not phone proof, intelligence proof, or
100% universal reliability. No PR merges without explicit user approval.

Release gates: (1) V0 CI green and owner-approved phone canary,
(2) verified StateVault + no historical-memory regression,
(3) V1 migration/rollback, (4) V2 blind A/B measurable benefit,
(5) V3 incremental large-project acceptance.

## 9. Definition of done for this PR

- Source/tests/docs committed in an isolated branch; no private data.
- Negative routes do not disappear when a recovery succeeds.
- Same-target contrary evidence remains explicitly CONTESTED.
- Model prose never counts as an independently verified result.
- Kernel contract count stays fixed, and empirical golden count stays 0.
- Exact-head Android CI passes and the PR remains draft/unmerged.
- Phone proof and demonstration of model performance remain PENDING.
