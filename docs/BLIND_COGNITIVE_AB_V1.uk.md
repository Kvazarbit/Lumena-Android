# Blind cognitive exoskeleton A/B protocol v1

**Not an intelligence claim.** The test harness is standard-library Python and is
audited by synthetic unit tests. CI does not have the owner's phone/model and
MUST NOT claim a live Gemma vs Lumena result.

## Hypothesis (pre-registered)

Under the same synthetic prompts, the installed Lumena agent + its current
memory, policies and allowed tools produces more exact correct answers than
calling its own `gemma4:31b-cloud` model directly via the phone's Ollama API,
without Lumena tools or memory.

This is a **system comparison**, not evidence that a specific cognitive layer
causes the effect, and not a change in model weights.

## Blindness

- `scripts/blind_ab.py create` uses 256-bit random seed and constructs 10 tasks,
  2 each in five families: graphs, knapsack, schedule optimization, modular
  state transitions, evidence aggregation with untrusted quoted text.
- The private oracle (with computed answers) stays **outside** the phone,
  public GitHub, tool-visible files, and model prompts. DO NOT upload it.
- The public challenge includes SHA-256 commitment to the private oracle,
  published before collecting answers. Any alteration breaks the commitment.
- The same questions are posed to both arms. No oracle feedback, manual fixes,
  retries or prompt revisions are allowed between responses.
- All 10 Lumena replies are sealed before direct model calls on this pilot;
  each answer must contain a unique `ANSWER=...` line.
- All 10 direct model requests are single-turn and independent. There is no
  shared model context or tool use in the control arm.
- Scoring is only possible once **all** task IDs exist in both answer sets.
  Missing/ambiguous answers count as failures. The parser cannot turn a model's
  convincing prose into a passing answer.
- Publish paired accuracy, matched discordances and exact two-sided McNemar
  p-value. No claim of general intelligence gain from one small experiment.

## How to run a real phone pilot

Create a public challenge on a **separate scorer machine** and never export
its private scoring key:

```bash
python scripts/blind_ab.py create --repeats 2 --public /tmp/challenge.json --private /SAFE_PRIVATE_LOCATION/oracle.json
```

Only the public challenge JSON, generated task cards and public harness travel
to the phone. Run each public task through installed Lumena first, record
unmodified answers, then execute the **direct control** in Termux:

```bash
python scripts/blind_ab.py direct --challenge challenge.json --out gemma_direct_results.jsonl
```

Return both **raw model outputs** to the independent scorer. Do not give them
the private oracle until after both output collections are frozen:

```bash
python scripts/blind_ab.py score --challenge challenge.json --private /SAFE_PRIVATE_LOCATION/oracle.json \
    --direct gemma_direct_results.jsonl --lumena lumena_answers.jsonl
```

## Limitations / safety

- This pilot has small statistical power and fixed B-then-A order. Confirm with
  larger preregistered batches and counterbalanced order before broad claims.
- Cloud model aliases can change without version pinning; record the provider
  and model name and compare runs close in time.
- Lumena uses additional tools/resources and may cost more. Analyze accuracy
  jointly with tool steps, latency and cloud spend in later trials.
- Bench prompts contain ONLY synthetic information; no user passwords, files,
  signing keys or private history are in the corpus.
- Running in CI validates only the **harness**, not the reasoning capabilities.
