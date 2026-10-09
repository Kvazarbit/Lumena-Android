# Lumena — PC-first preference evaluation + module hardening (2026-10-09)

**Execution branch:** `feature/pc-eval-modules-hardening-v1`, created from Opus
`feature/modules-v1` HEAD `656abac097401f8278951640719ce2881d790b55`
(app 0.12.28, CI #2049 SUCCESS). Do not merge or replace
`phone-stable-v0.12.21-ci2006` without explicit owner approval.

**Owner decision:** do not promote any new learned listing preferences to Android until
independent PC evaluation of *real, owner-labeled* examples demonstrates useful
generalization. The reported PC evaluation involved 24 labels, with 6 of 6
driver-role examples marked 👎. **This aggregate report is not the underlying
dataset**; titles, salary/conditions, exact feedback, duplicates and reasons
were not provided to this session. No synthetic record may be represented as
one of the 24 owner ratings. Six driver rejections challenge the prior but do
not establish a global ban on driving.

## 1. Offline, non-mutating preference evaluation

Implementation:
- `scripts/listing_preference_benchmark.py` reads private owner JSONL and
  outputs **aggregate-only** score summaries, no raw listing texts;
- `scripts/test_listing_preference_benchmark.py` uses explicitly synthetic,
  labeled fixtures to test the procedure (never claims real-data validity);
- strict owner label parsing (👍/👎 only); stable offer/group IDs exclude
  republished copies from their own training holdout; deterministic fixed
  hyperparameters; leave-one-group-out estimates;
- compare `always_reject`, `driver_prior_proxy_not_android_full_model`,
  `contextual_lexical_logistic`. The driver proxy is *not* the full Kotlin
  ListingAttention policy, so results cannot justify replacing app weights;
- report exact sample/class/group counts, errors, balanced accuracy and log loss.
  Mixed-class and adequate holdout requirements are necessary; absent data or
  single-class data does not count as successful learning;
- **promotion_allowed remains false** until a separate owner-reviewed gate,
  sufficient independent holdout and actual phone comparison are completed.

Private input example (sample, not owner data):
```jsonl
{"group_id":"sample-1","title":"Monter instalacji","description":"Ogłoszenie przykładowe","company":"Firma","feedback":"👍"}
{"group_id":"sample-2","title":"Kierowca kat. B","description":"Przykład testowy","company":"Firma B","feedback":"👎"}
```

PC command (do not commit private data to Git):
```bash
python -m unittest discover -s scripts -p 'test_listing_preference_benchmark.py' -v
python scripts/listing_preference_benchmark.py --data /private/path/owner-listings.jsonl --report /private/path/pc-report.json
```

**Pending**: obtain authorized original 24 records/reasons, run evaluation,
add uncertainty/holdout by employer/time/salary/role, compare with full Kotlin
policy on the same independent examples, decide what exactly should learn.
No driver priors or Constitution weights changed by this branch.

## 2. Ten-point audit implementation tracker

The patches below target defects in the Opus APK; these are code candidates,
not phone-verified behavior. Exact-head CI and mutation probes determine
whether they pass narrow technical gates.

| Audit | Implementation / test | Remaining acceptance |
|---|---|---|
| #1 OLX broad package matching | exact allow-list `pl.tablica`; foreign/spoof ID rejected in `ListingAttentionPolicyTest`; raw storage separate opt-in | verify actual installed OLX package/channel & private-chat format on phone; expand allow-list only by evidence |
| #2 Pracuj network | redirect/retry disabled, fixed host HTTPS, streaming 4 MiB cap; `PracujClientTest` | Android/network phone check |
| #3 MCP typo/negation | `McpSearchCue` bounded cue recognition; `McpSearchCueTest` covers `мср`, `мсп`, mixed scripts, negation, query cleanup | phone e2e with real configured MCP provider; robustness extension |
| #4 mutation | `scripts/mutation_module_probe.py`: 11 isolated mutants, exact-byte restore, only assertion failures accepted | exact-head mutation CI success |
| #5 unknown Bridge | `BridgeCompatibility` requires observed compatible version; negative tests; UI already runs `health` on resume | cold phone startup and downgrade test |
| #6 duplicate manifest | `ModuleRegistry` rejects duplicate tool/alias/state ID inside one module; tests | phone modules panel check |
| #7 sensitive defaults | absent settings disable notification-reading module; raw OLX push capture has separate default-OFF consent toggle | owner opt-in and no silent re-enable after reset/restore |
| #8 Cyrillic city | explicit Варшава/Легіоново aliases; unsupported scripts cause visible error, not silent Legionowo; tests | typed input in phone Compose |
| #9 multi-place offer | expand concrete workplace entries + own links; `PracujSourceTest` | live multi-location listing |
| #10 timestamps | `ListingNotice.observedAt` separates first seen from publication time; tests | dedupe, repost and migration replay phone checks |

Additional safety: Pracuj watch establishes its first successful page as a
baseline without broadcasting 50 false `new` alerts; a changed search URL
creates a new baseline; storage failures are not treated as empty successful
results. The wakeup is Android WorkManager, no Termux dependency.

## 3. Invariants to preserve for every model

- **Kernel remains authoritative**. Module tools are registered only after
  `ModuleRegistry` validation; external MCP `readOnlyHint` never grants
  executable/write authority. Learned preferences are not Constitution.
- **No silent preference promotion.** 👍/👎 are explicit owner labels; an alert
  or prediction never becomes a positive training example.
- **No private raw data in Git/prompt/StateVault by default.** `ListingCaptureSettings`
  is separate explicit opt-in, off if its preferences are lost.
- **No fake data/approval/phone tests.** The 24 examples are reported only
  as aggregate until the original data is locally available.
- **No CloudFront/CAPTCHA bypass.** Site read failures are visible partial
  results; source URLs must have real provenance.
- **No PR merge to stable** or owner signing/install without an explicit later
  approval. Existing owners' local app data must remain untouched.

## 4. Test ladder / result taxonomy

1. `UNIT`: Python synthetic PC suite; Kotlin routing/network/privacy tests;
   Termux MCP broker tests.
2. `MUTATION`: `python scripts/mutation_module_probe.py`, 11 source mutants,
   each killed by a named assertion; compile/infrastructure timeouts are FAIL.
3. `CI`: green exact-head job(s) for these changes, not a prior unrelated run.
4. `PC_REAL_DATA`: original 24 user-rated examples; report counts and
   uncertainty; no auto-updating weights.
5. `PHONE`: install owner-signed matching APK and bridge, grant explicit
   permissions, verify Pracuj/OLX and MCP behavior using real local evidence.

**Do not substitute a green unit/CI result for steps 4 or 5.** Next model
must inspect current Git HEAD/CI and actual phone diagnostic before acting.
