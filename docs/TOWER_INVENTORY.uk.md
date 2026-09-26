# Lumena-Android: інвентар вежі PR (знімок 2026-09-26)

Вершина: PR #99 `feature/laya-system1-shadow-v1-rebased`.
Ланцюжок від `main` до вершини: 56 PR — #10 #11 #12 #21 #22 #24 #25 #26 #27 #28 #29 #30 #31 #32 #33 #34 #35 #36 #37 #38 #39 #40 #41 #42 #43 #44 #45 #46 #47 #48 #49 #51 #54 #55 #56 #57 #60 #62 #63 #64 #66 #67 #70 #74 #76 #78 #81 #83 #84 #86 #92 #93 #94 #95 #96 #99.
Усі вони потрапляють у `main` разом із вершиною (Етап 4, консолідація).

Відкриті PR поза ланцюжком: 35.

Класи за планом v2.1 (гіпотеза, крім рядків «перевірено»):
- `IN` — коміти вже містяться у вершині `#99`; закрити як «увійшло в main» на Етапі 4.
- `A` — телефонна збірка з іншою назвою пакета; коду не несе; закрити.
- `B` — поведінка вже є у вершині; доказ — таблиця тверджень PR і прогін тестів на вершині (план v2.1, Етап 1). Частково покритий PR розщеплюється на `B` + `C`/`DEFER`.
- `B?` — схоже на `B`, але доказ тестами ще не отримано.
- `C` — реальна зміна, якої у вершині немає; перенести через тест у гілці стабілізації (Етап 2).
- `C?` — ймовірно реальна зміна; після аудиту стає `C` (у v1.0) або `DEFER` (рішення власника).
- `DEFER` — реальна функція, відкладена на після v1.0; PR закривається, тег-знімок лишається.
- Split-клас (`B + C`, `B + DEFER`) — частина поведінки вже збережена, решта окремо переноситься або відкладається.
- `B?` після аудиту не закривається: структурна/семантична еквівалентність є, але strict old-test/characterization run на TIP ще не виконано.

| PR | Назва | Комітів поза вершиною | Клас | Примітка | Перевірено |
|---|---|---|---|---|---|
| #1 | Add local Termux/Python agent bridge (v0.3) | 0 | `IN` | вже у вершині | так |
| #2 | Add Ollama workflow chat and dark UI (v0.4) | 0 | `IN` | вже у вершині | так |
| #3 | Embed llama.cpp and add collaborative project workspace (v0.5) | 19 | `B?` | embedded llama/GGUF picker/hybrid backend/workspace behavior є в TIP; старий app-private auto-scan замінено persisted URI; потрібен characterization run | частково |
| #4 | Add official ChatGPT companion workflow (v0.6) | 0 | `IN` | вже у вершині | так |
| #5 | Make Local agent resilient to timeouts and tab switches (v0.7.3) | 0 | `IN` | вже у вершині | так |
| #6 | Add AgentController with live progress and smart STOP (v0.8.1) | 0 | `IN` | вже у вершині | так |
| #7 | Add topic history tree, checkpoint branches and native Ollama tools (v0.9) | 17 | `B + DEFER` | Topic→Task→Branch/fork/cancel-before-switch збережені; native Ollama tool_calls і стара SQLite migration відкладені після v1.0 | так (split схвалено) |
| #8 | Visible Local progress and safe Stop controls (v0.8.1) | 2 | `B?` | сучасний AgentRunCoordinator + bridge /cancel є; старий RunControl/managed-bridge regression suite не cross-run на TIP | частково |
| #9 | Add navigable Topic → Task → Branch history tree (v0.9) | 0 | `IN` | вже у вершині | так |
| #13 | Fix web-search/Ollama retry loops and context overflow handling | 12 | `B?` | no-loop/error/context invariants збережені; стара політика terminal-on-first-web-failure навмисно superseded; потрібен strict compatibility run | частково |
| #23 | protocol-normalizer-v1: deterministic constitutional protocol boundary | 10 | `B` | старі ProtocolNormalizer/WorkflowRunner regression bodies збережені в TIP; exact-head CI green | так |
| #50 | ChatGPT remote relay v1: private outbound web-tool mailbox | 8 | `DEFER` | Remote Relay відсутній у TIP; власник схвалив відкладення на v1.1 | так |
| #52 | Evidence graph v1 step 4: inspector and phone-visible provenance | 4 | `B` | старі EvidenceGraphInspector test bodies збережені в TIP; exact-head CI green | так |
| #53 | Evidence graph v1 step 5: grounded semantic claim boundary | 13 | `B?` | semantic candidate authority boundary є в сучасному EvidenceClaimCandidate; старий test file переписано, strict cross-run ще нема | частково |
| #58 | Evidence graph v1 step 7: end-to-end lifecycle gate | 1 | `C` | test-only E2E lifecycle gate відсутній у TIP; повернути як тест без production authority | так |
| #59 | Evidence graph v1 step 8: runtime pending semantic proposals | 13 | `DEFER` | runtime `evidence_candidates` parser/ingestion відсутній у TIP; власник схвалив відкладення на v1.1 | так |
| #61 | Step 8.2: preserve failed task context for follow-ups | 4 | `B?` | failure follow-up/capsule behavior є в FollowUpGoal tests; старий TaskOutcomeContextTest відсутній, strict cross-run ще нема | частково |
| #65 | Step 8.5: verified project episode to Constitution Genome candidate | 9 | `B` | усі старі ConstitutionContributionPolicy test bodies збережені в TIP; exact-head CI green | так |
| #68 | Step 8.7A: full living-context lifecycle E2E gate | 3 | `B + C` | production monotonic binding edge-case збережений; LivingContextEndToEndTest відсутній і має бути повернений як test-only gate | так (split) |
| #69 | Step 8.7: isolated side-by-side phone E2E build | 2 | `A` | diff лише applicationId/versionName/label | так |
| #71 | Step 8.7b: isolated E2E build with mixed-task budget fix | 2 | `A` | diff лише applicationId/versionName/label | так |
| #72 | Step 8.7c: final isolated phone E2E build | 2 | `A` | diff лише applicationId/versionName/label | так |
| #73 | Step 8.7 runtime fix: required tool obligations and duplicate suppression | 6 | `B` | старі AgentController/TaskIntentRouter regression bodies збережені в TIP; exact-head CI green | так |
| #75 | Step 8.7d: isolated E2E build with required-tool obligations | 2 | `A` | diff лише applicationId/versionName/label | так |
| #77 | Step 8.7 phone validation: isolated PARTIAL-continuity build | 2 | `A` | diff лише applicationId/versionName/label | так |
| #79 | Diagnostics: canonical phone build 0.12.6-diag1 | 2 | `A` | diff лише applicationId/versionName/label | так |
| #80 | Diagnostic v1 phone build | 2 | `A` | diff лише applicationId/versionName/label | так |
| #82 | Step 8.7: make protocol repair restore the active task | 2 | `B?` | successor #83/modern protocol repair є; дві старі regression bodies замінені сильнішою перевіркою, strict cross-run ще нема | частково |
| #85 | Step 8.7 RC1 phone validation build | 2 | `A` | лише зміна пакета, +3/−3 | так |
| #87 | Step 8.7 RC2 phone validation build | — | `A` | лише зміна пакета, +3/−3 | так |
| #88 | Step 8.7: block unchanged failed verification replays | 5 | `C` | ВТРАЧЕНО: `redundantFailedVerification` + tests відсутні у TIP; duplicate source для unified #88/#90/#91 fix | так |
| #89 | Step 8.7: keep mutations inside active project scope | 2 | `C` | ВТРАЧЕНО: `projectMutationScopeViolation` + tests відсутні у TIP | так |
| #90 | Step 8.7: require repair before failed verification replay | 6 | `C` | ВТРАЧЕНО: `failedVerificationReplayWithoutRepair` semantics відсутня у TIP; source для unified fix | так |
| #91 | Step 8.7: block unchanged failed verification replays | 4 | `C` | ВТРАЧЕНО: `repeatedFailedVerificationWithoutMutation` + tests відсутні у TIP | так |
| #97 | Context OS: local Laya System-1 shadow on Android/Termux | — | `B` | 4 ключові Laya test blobs ідентичні #97→TIP #99; exact-head CI #1367 green | так |

Закриті раніше: #14 (злитий), #15, #16 (злитий), #17, #18 (злитий), #19 (злитий), #20 (злитий), #98.

Як перевірити рядок (з локального клону, після `git fetch --all`):

```bash
TIP=origin/feature/laya-system1-shadow-v1-rebased
git merge-base --is-ancestor origin/<head-гілка> $TIP && echo IN || echo NOT_IN
git log --oneline $TIP..origin/<head-гілка>        # коміти, яких немає у вершині
git diff $TIP...origin/<head-гілка> --stat          # що саме вони змінюють
```

Дані: `evidence/pr_inventory.json` (GitHub API, compare `tip...head`), `evidence/lumena.json`.