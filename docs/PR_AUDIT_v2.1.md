# Lumena PR audit — v2.1

Date: 2026-09-26

Audited TIP: `21ace87ee9472c5a3824bb406f09c41070c742e4` (PR #99)

Main snapshot SHA: `9934294dd71df150b92505e3b9054574de9db621`

No side PR head changed between the read-only audit and owner approval.

## Classification

| PR | Head SHA | Class | Evidence / disposition |
|---|---|---|---|
| #1 | `5a9f0cff0069aa40a506655526bc2800ba21a7fd` | `IN` | ancestor of TIP |
| #2 | `e7a3a386b807fc20b9a5cb87b8456a2765d10538` | `IN` | ancestor of TIP |
| #3 | `a5c929f3eadb663db2c0ae8750c9b3d759069f4c` | `B?` | embedded/GGUF/hybrid/workspace present; characterization pending |
| #4 | `7f7191a8d36014e549169f265763e47eceb37bc8` | `IN` | ancestor of TIP |
| #5 | `eac69cd3eb2df0680c7634135096d0b0225e4c2d` | `IN` | ancestor of TIP |
| #6 | `5c7c2c898a40f56aad16a876e0b366c9ba25630d` | `IN` | ancestor of TIP |
| #7 | `3618a498880c0455f68cb0064f766b957f579637` | `B + DEFER` | history branch behavior preserved; native tool_calls/old SQLite deferred |
| #8 | `989973024557a5149fb077c9f941c457288be4c7` | `B?` | modern stop/cancel present; old suite not cross-run |
| #9 | `684e9151b62e1db01bf2f8a31b8f6cac986cea97` | `IN` | ancestor of TIP |
| #13 | `5d0de7fef6234830dbd39d3b157733ab04162b2a` | `B?` | core invariants preserved; exact old policy/test suite superseded |
| #23 | `695a4dc379cc143ef20bc53aa24e6207bc8e9fc1` | `B` | old test bodies preserved in TIP + CI green |
| #50 | `ce8158aa7013a1bee1474439a5bd25a1405a78ee` | `DEFER` | owner-approved v1.1 |
| #52 | `98a49fe021d332517a06879dc036eca032b2aa4c` | `B` | old inspector test bodies preserved + CI green |
| #53 | `939faa0c8ec69bffb5553708d48ec9b664568cea` | `B?` | authority boundary present; rewritten test layer |
| #58 | `bc165227cd629b7162c2336b480e79aba3a17fd2` | `C` | missing E2E lifecycle test-only gate |
| #59 | `32e8f9875b374996e8239645fa74306ed0a76ad9` | `DEFER` | owner-approved v1.1 |
| #61 | `28acadee3478b900042026bd7a32bff7f9636846` | `B?` | equivalent follow-up outcome behavior present; old test absent |
| #65 | `4a12319b154be2fb1514618a7ead2c7ec8c74b72` | `B` | old test bodies preserved + CI green |
| #68 | `909d7135a6eba7547b33d36fbedd05393658734c` | `B + C` | edge-case preserved; E2E gate missing |
| #69 | `d9fd09393a774a59b1a11c2af0b47380758d3b95` | `A` | package/version/label only |
| #71 | `b04fba74fc95a36d8c04955adbc449407cc0a458` | `A` | package/version/label only |
| #72 | `1bafb8be8174fe9fac68cb821fa74719d0fb8314` | `A` | package/version/label only |
| #73 | `81568249e62aa03636de7025fff31d47dea52052` | `B` | old test bodies preserved + CI green |
| #75 | `47c616ba0ef21ed84d03f263f3cfc7490b9138da` | `A` | package/version/label only |
| #77 | `463b2f30a4a973d223455a8185b0ef8f128af1fd` | `A` | package/version/label only |
| #79 | `634d59648049cec61fcae2fec381133df004291f` | `A` | package/version/label only |
| #80 | `10f67aad1e9c4187c1344a5cb72b5bbd88efc0a7` | `A` | package/version/label only |
| #82 | `d52bae32edb98a628cf533098198e834dfdf5749` | `B?` | modern stronger protocol-repair test exists; old two bodies not present |
| #85 | `56dc4b0fba78a0a302760c7dec10d3779d20279e` | `A` | package/version/label only |
| #87 | `0c737e330d95d56681c0ca27e479b91456f7d585` | `A` | package/version/label only |
| #88 | `19027383c0314ea88ac0bee0b64d8c900252ab6a` | `C` | missing failed-verification guard; unified source |
| #89 | `aa85e891b4b04bbde455a5f0b52d5ae739cf39ca` | `C` | missing project mutation scope guard |
| #90 | `45c9765de105aa5481192638322349ca680afb12` | `C` | missing repair-before-reverify semantics; unified source |
| #91 | `581406ef1bcccabbe299af0e0c23ea9a141d7c7f` | `C` | missing unchanged failed-verification replay guard |
| #97 | `7b4ee06a1f1033e04b5d69a136d7c2f3a26b7a5f` | `B` | key test blobs identical in TIP + CI #1367 green |

## Strict-proof caveat

`B?` means the successor behavior is structurally/semantically present, but the exact old regression or a characterization test has not yet been run against TIP. These PRs must **not** be mass-closed as proven `B` until that run exists.

## Owner decisions (2026-09-26)

- #50 Remote Relay: **DEFER to v1.1**.
- #59 runtime evidence-candidate ingestion: **DEFER to v1.1**.
- #7 native Ollama tool_calls / old SQLite-specific implementation: **DEFER after v1.0**; keep the modern history-tree successor.
- Use bounded `stabilize/v0.13`, scope limited by COMPLETION_PLAN v2.1, time limit **7 days**.
- #88/#90/#91 are sources for **one** modern failed-verification replay guard, not three competing implementations.
- No merge to `main`, no mass closure, no branch deletion, and no learned-layer authority without a new explicit owner decision.

## Snapshot status

37 snapshot targets are recorded (main + TIP + 35 side PR heads). The ChatGPT GitHub connector in this session has no write API for tag refs, so tag creation is intentionally left to `tools/create_snapshot_tags_2026-09-26.sh`. The script refuses to move an existing mismatched tag and pushes tags atomically.
