# Lumena Cognitive Exoskeleton Roadmap

Стан документа: 2026-09-30.  
Поточна canary-лінія: `0.12.14-ci1552`, versionCode 40, Bridge 0.27.  
Цей документ є дорожньою картою. Позначка **PLAN** не означає, що функція вже реалізована або підтверджена на телефоні.

## 1. Ціль

Lumena має стати не «ще однією моделлю», а довготривалим когнітивним екзоскелетом для роботи над проєктами:

```text
людина задає ціль
    ↓
різні моделі / агенти пропонують рішення
    ↓
телефон + ToolRegistry + ToolGate вирішують, що реально можна виконати
    ↓
TOOL_RESULT дає локально перевірений факт
    ↓
Lumena стискає досвід у причинні та стратегічні структури
    ↓
наступна модель отримує не весь чат, а релевантний перевірений досвід
    ↓
старий досвід повторно перевіряється у новому середовищі
```

Ключова ідея: **моделі можуть змінюватися, а інтелект проєкту не повинен щоразу починатися з нуля**.

Lumena не повинна ставати центральним «всезнаючим мозком». Її сильна роль:

- довготривала операційна пам'ять;
- причинний граф досвіду;
- перевірка результатів;
- маршрутизація релевантних стратегій;
- облік суперечностей і застарівання;
- контроль повноважень;
- міст між різними моделями, пристроями й ітераціями проєкту.

## 2. Непорушні інваріанти

Ці правила важливіші за будь-яке навчання.

1. **Телефон є execution authority.** Модель може планувати і просити інструмент, але виконання контролюють ToolRegistry, ToolGate і правила підтвердження.
2. **Model prose не є evidence.** Відповідь моделі, її self-check або правдоподібне пояснення не є доказом виконання.
3. **TOOL_RESULT не дорівнює успіху всієї цілі.** Успішний інструмент доводить лише власний перевірений ефект.
4. **Imported experience не є local truth.** Перенесений досвід з іншого пристрою/моделі/проєкту завжди починає як advisory source-device evidence.
5. **Немає самопризначення повноважень.** Experience, Laya, Constitution candidate або imported capsule не можуть надати permission/approval.
6. **CONTESTED не стискається в «правильне правило».** Суперечність зберігається.
7. **Unknown outcome не стає training label.**
8. **Hard Constitution не саморедагується з досвіду.**
9. **Raw secrets і приватні довгі тексти не повинні переходити в social memory.**
10. **Будь-яке твердження «Lumena стала кращою» має підтверджуватися вимірюванням, а не кількістю записів у пам'яті.**

## 3. Фактичний стан на ci1552

### 3.1 Уже реалізовано

- Coordinator Experience з verified TOOL_RESULT, RECOVERY, FAILED_RECOVERY і provenance.
- Constitution Genome окремо від execution authority.
- Fractal Experience Canvas:
  `EPISODE → PATTERN → STRATEGY → META_RULE`.
- Peaks:
  `BEST / WORST / UNKNOWN / CONTESTED`.
- Origins:
  `LIVE / LEGACY_BACKFILL`.
- Stages:
  `SHADOW / TRANSFERRED_SHADOW`.
- Legacy backfill старого verified coordinator history.
- Cross-model social retrieval у межах scope.
- Shadow language cues.
- Causal Experience v1:
  `BAD_PATH → WHY_FAILED → RECOVERY → RESULT`.
- Structured failure metadata:
  `failureClass / errorCode / retryable / dependency`.
- Якщо структурованої причини немає:
  `WHY_FAILED=UNKNOWN_NOT_CAPTURED`.
- Laya залишається `SHADOW` і не виконує інструменти.
- Owner signing перевіряється pinned certificate.
- Android CI перевіряє unit tests, recovery mutation probe, native smoke, release build і native library.

### 3.2 Остання телефонна діагностика перед контрольним LIVE recovery

```text
records=402
nodes=1024
best_peaks=1011
worst_peaks=13
transferred_shadow=3
language_cues=3
language_transferred_shadow=1
legacy_backfill_records=399
live_records=3
causal_links=18
causal_recovered=13
causal_unresolved=5
causal_live_links=0
causal_revalidated_patterns=0
```

Важливий сигнал: `nodes=1024` уже вперся в поточний cap. Це означає, що наступний крок — не просто збільшувати MAX_NODES, а зробити кращу ієрархічну компресію та захистити важливі WORST/CONTESTED/STRATEGY/META_RULE вузли від витіснення.

### 3.3 Контрольний LIVE recovery після цієї діагностики

На телефоні вже отримано verified sequence:

```text
python.run[failed]
→ file.write[ok]
→ python.run[ok]
```

Coordinator уже сформував з нього `RECOVERY EXAMPLE`.

**PENDING PHONE CONFIRMATION:** потрібен новий `LUMENA_DIAGNOSTIC_V1`, щоб підтвердити, що цей LIVE recovery дійшов до causal projection і `causal_live_links > 0`.

### 3.4 Гілка `feature/exoskeleton-integrity-v1` (CI зелений, телефон НЕ перевірено)

Статус кожного пункту: **реалізовано в коді й покрито тестами; CI #1962–#1964 зелений;
на телефоні не перевірено.** За правилом цього документа це не DONE.

- **P0a/P0b повернуто** (були побудовані на іншій гілці й не дійшли сюди):
  - C1: запис і читання Genome використовують одну область; досвід задач без проєкту знову
    читається, а LEARNED-правила області GLOBAL лишаються в тіні до виміру;
  - C10: окрема вісь `WorkspaceMutationEffect`; `python.tests`/`python.run` скасовують
    «свіжість» перевірки й запису;
  - телеметрія `[COGNITIVE_INFLUENCE]`, epoch тепер зберігаються (6 останніх), а не стираються.
- **NO EVIDENCE → NO COMPLETE** (інцидент «реалізуй → Task complete.»): `done` без жодного
  інструмента відхиляється для голої команди виконання і для порожнього «готово».
- **«?» / «що?»** відповідається зі знімка попередньої задачі; робоча нитка й кодова ціль не
  стираються; веб-пошук не запускається.
- **Cause Ladder:** `VERIFIED` лише з двох незалежних *спростовних* проб (протилежний результат
  дав би REJECTS). Проба «підтверджує за будь-якого результату» лишає гіпотезу PROBED.
- **Нервова система:** заблокована дія = `OBSERVED`, не `VERIFIED_EFFECT`; кандидат не отримує
  `TRANSFERRED` за ≥2 контексти.
- **Layer Governor** — див. [LAYER_GOVERNOR_V1.uk.md](LAYER_GOVERNOR_V1.uk.md): факторний
  ON/OFF для 8 дорадчих шарів (включно з кандидатами в Core DNA), вимір токенів/кроків/часу,
  вердикти KEEP/DISABLE/NEGLIGIBLE/COSTLY_NO_BENEFIT/NOT_TRIGGERED/INSUFFICIENT_DATA,
  контрольні точки, Бонферроні, облік незавершених задач.
- **C5/C6/C7 (CI #1965):** TinyJev калібрується і на провалах; автоприв'язка вебджерела —
  лише асоціація без `VERIFIED_BY_TEST`; неспростовний ген верифікації не стає LEARNED.
- **C2/C3/C4 (CI #1972):** адреса досвіду з причиною збою, один епізод = один доказ,
  градуйовані суперечності замість вічного CONTESTED.
- **Слід задач на полотні** (`[FRACTAL_TASK_TRAIL]`), **кандидати в Core DNA** (`principles`) і
  **нагадування зобов'язань** (`commitments`) — під Governor-ом.
- Відкрито: офлайн парний A/B (S09); Snake порівнює прогноз сам із собою; перевірка на телефоні.

## 4. Цільова модель досвіду

Поточний `BAD_PATH → RECOVERY` — лише середина шляху. Повний когнітивний цикл має бути:

```text
STATE
  ↓
EXPECTED
  ↓
ACTION
  ↓
ACTUAL
  ↓
DELTA = EXPECTED ↔ ACTUAL
  ↓
CAUSE
  ↓
RECOVERY
  ↓
VERIFIED
  ↓
UTILITY
  ↓
REUSE / CONTEST / DECAY
```

### 4.1 STATE

Мінімальна контекстна капсула перед дією:

- task/project scope;
- модель і backend;
- app/bridge version;
- device/runtime class;
- релевантні файли/версії;
- dependency fingerprint;
- активні обмеження;
- попередня помилка;
- bounded evidence IDs.

STATE не повинен бути копією всього чату.

### 4.2 EXPECTED

Очікування — це **prediction/proposal**, а не факт.

Поля першої версії:

- expected tool outcome;
- expected effect class;
- expected target;
- expected postcondition;
- acceptance criterion ID;
- confidence/uncertainty;
- source: deterministic policy / model proposal / imported strategy.

Очікування не отримує authority і не може саме себе підтвердити.

### 4.3 ACTUAL

ACTUAL формується лише з локального execution result:

- canonical tool;
- target;
- ok / failed / unknown;
- exitCode;
- failureClass;
- errorCode;
- retryable;
- dependency;
- bounded evidence IDs;
- timestamp;
- environment fingerprint.

Raw stdout/stderr не повинні автоматично копіюватися в довгострокову causal memory.

### 4.4 DELTA

Delta — структурована різниця між прогнозом і фактом.

Початкові класи:

- `MATCH`;
- `UNEXPECTED_FAILURE`;
- `UNEXPECTED_SUCCESS`;
- `WRONG_TARGET_STATE`;
- `PARTIAL_EFFECT`;
- `DEPENDENCY_CHANGED`;
- `SCHEMA_MISMATCH`;
- `OUTCOME_UNKNOWN`;
- `VERIFICATION_MISSING`.

Delta потрібна для навчання саме на помилках прогнозу, а не лише на факті fail/success.

### 4.5 CAUSE

Причина має рівень достовірності:

- `UNKNOWN_NOT_CAPTURED`;
- `STRUCTURED_TOOL_FAILURE` — прямо з ToolResult metadata;
- `HYPOTHESIS` — модель/політика запропонувала можливу причину;
- `PROBED` — окремий probe дав supporting evidence;
- `VERIFIED_CAUSE` — незалежна перевірка підтвердила причинний механізм.

Ніколи не піднімати `HYPOTHESIS` у `VERIFIED_CAUSE` лише тому, що пояснення звучить правдоподібно.

### 4.6 RECOVERY

Recovery зберігає:

- bad path;
- recovery steps;
- outcomes кожного кроку;
- evidence IDs;
- чи змінився target/state;
- чи був повтор сліпим;
- чи recovery справді змінив failed state;
- чи незалежна verification підтвердила результат.

### 4.7 UTILITY

Не зводити все в один «IQ score».

Зберігати окремо:

- tool attempts;
- failed attempts;
- recovery length;
- elapsed time;
- model calls;
- approximate/actual token use;
- user interventions;
- verification quality;
- false-done count;
- goal outcome;
- memory hits;
- memory items actually used;
- stale/contested advice rate.

## 5. План реалізації

## Phase 0 — підтвердити LIVE causal wiring

**Статус:** PHONE VERIFIED ✅

### Виявлений баг

Перший контрольний Companion recovery:

```text
python.run[failed]
→ file.write[ok]
→ python.run[ok]
```

дав реальний `RECOVERY EXAMPLE`, але phone diagnostic лишив:

```text
live_records=3
causal_live_links=0
causal_revalidated_patterns=0
```

Одночасно Constitution змінилася з `learned_active=1, contested=0` на
`learned_active=0, contested=1`. Це показало, що Companion event доходив до
Coordinator/Constitution fan-out, але не до Fractal Canvas.

### Root cause

У `CompanionScreen.executeCommand` після
`CoordinatorExperienceStore.examplesForTask(...)` викликався
`ConstitutionGenomeStore.ingestVerifiedRecoveryExamples(...)`, але був відсутній
`FractalExperienceCanvasStore.ingest(...)`.

WorkflowChatScreen мав цей fan-out; Companion — ні. Тому Companion міг вивести
`RECOVERY EXAMPLE` у `experience_context`, але causal diagnostics не бачили LIVE link.

### Fix

На гілці `feature/causal-experience-v1` додано:

- `FractalExperienceCanvasStore.ingest(context, examples)` у verified Companion fan-out;
- regression guard `CompanionFractalWiringRegressionTest`;
- наступні canary-збірки зберігають цей wiring.

### Phone acceptance

На owner-signed `0.12.16-ci1574` / versionCode 42 виконано свіжий post-fix цикл:

```text
python.run[failed]
→ file.write[ok]
→ python.run[ok]
```

До циклу:

```text
records=406
live_records=7
causal_links=18
causal_recovered=13
causal_unresolved=5
causal_live_links=0
transferred_shadow=5
```

Після циклу:

```text
records=407
live_records=8
causal_links=19
causal_recovered=14
causal_unresolved=5
causal_live_links=1
transferred_shadow=6
```

Отже вертикальний тракт

```text
Companion → Coordinator → Fractal Canvas → Causal Experience
```

підтверджений реальним phone evidence. `causal_revalidated_patterns=0` на цьому
етапі не є помилкою: один LIVE causal link сам по собі ще не виконує критерії
revalidation у кількох незалежних source tasks із сумісним structural pattern.

### Gate result

- exact-head Android CI green ✅
- in-place owner-signed install ✅
- fresh fail→fix→success через Companion ✅
- `causal_live_links > 0` ✅
- recovered +1 без зменшення unresolved через хибне перепризначення ✅
- execution authority/Laya/Constitution invariants не розширені ✅

**Phase 0 закрито. Наступний пріоритет: Phase 1 — Prediction / Actual / Delta Ledger.**

---

## Phase 1 — Prediction / Actual / Delta Ledger

**Статус:** CORE PHONE VERIFIED ✅ · unknown-effect transport safety remains CI-covered.

### Мета

Навчити Lumena пам'ятати не лише «що сталося», а **чого вона очікувала від вибраної дії і наскільки фактичний результат відрізнився**.

### Реалізований vertical slice

Додано bounded типи:

- `ExpectedOutcome`;
- `ObservedOutcome`;
- `OutcomeDelta`;
- `ExpectationSource`;
- `VerificationStatus`;
- `OutcomeDeltaStats`.

Кожний новий verified Coordinator tool event тепер отримує deterministic expectation:

```text
intentional registered tool call
    ↓
ExpectedOutcome(
  source=DETERMINISTIC_TOOL_CONTRACT,
  expectedStatus=SUCCESS,
  expectedEffectClass=READ_ONLY_OBSERVATION | MUTATING_EFFECT | EXECUTABLE_EFFECT,
  expectedPostcondition=KNOWN_SUCCESSFUL_TOOL_OUTCOME
)
    ↓
real ToolResult
    ↓
ObservedOutcome
    ↓
OutcomeDelta
```

Це **не** твердження моделі «я впевнена, що дія спрацює». Це мінімальний контракт: якщо агент свідомо вибрав валідний інструмент як наступну дію, очікуваний операційний результат — відомий successful tool outcome. `confidence=null`, доки реальний prediction source не надасть калібровану оцінку.

Поточні structural delta:

- `MATCH`;
- `UNEXPECTED_FAILURE`;
- `UNEXPECTED_SUCCESS` — зарезервовано для майбутніх non-success expectations;
- `WRONG_TARGET_STATE`;
- `PARTIAL_EFFECT` — зарезервовано для GoalContract/verifier layer;
- `DEPENDENCY_CHANGED` — тільки якщо ToolResult прямо дає цей verified failure class;
- `SCHEMA_MISMATCH` — тільки зі structured failure metadata;
- `OUTCOME_UNKNOWN`;
- `VERIFICATION_MISSING`.

`ToolResult.error`, raw stdout і raw stderr не копіюються в цей ledger. Зберігаються лише bounded target + structured metadata: failureClass/errorCode/retryable/dependency.

### Verification semantics

Поточний `VerificationStatus=TOOL_RESULT_ONLY` означає: ми знаємо результат самого інструмента, але це ще не незалежний доказ user-level postcondition.

`OUTCOME_UNKNOWN` ніколи не стає success/failure training label.

`INDEPENDENT_VERIFICATION_MISSING` і `VERIFIED_POSTCONDITION` підключаються повністю на Phase 2 через GoalContract/verifier; Phase 1 не вигадує їх із model prose.

### Compatibility

Нові поля `CoordinatorEpisodeEvent.expectedOutcome / observedOutcome / outcomeDelta` optional і мають safe defaults. Старий Coordinator JSON без цих полів продовжує читатися; legacy events не ретроактивно отримують вигадані expectations.

### Tests

На гілці додано перевірки:

- deterministic expectation semantics;
- success → MATCH;
- verified failure → UNEXPECTED_FAILURE;
- structured INVALID_INPUT / REQUIRED → SCHEMA_MISMATCH;
- unknown outcome → OUTCOME_UNKNOWN;
- wrong observed target ніколи не MATCH;
- model proposal саме по собі не створює evidence/delta;
- JSON round-trip;
- raw error/stderr prose не потрапляє в ledger;
- old Coordinator JSON читається з null prediction fields;
- legacy events не рахуються як Phase-1 observations;
- diagnostic formatter показує окремий `[PREDICTION_DELTA]` блок.

### Diagnostic gate

Новий блок:

```text
[PREDICTION_DELTA]
expectations_total=
deltas_total=
matches=
unexpected_failures=
unexpected_successes=
schema_mismatches=
outcome_unknown=
verification_missing=
authority=advisory_only
model_prose_is_evidence=false
```

### Phone acceptance

На owner-signed `0.12.17-ci1596` / versionCode 43 після чистого оновлення diagnostic спочатку показав:

```text
[PREDICTION_DELTA]
expectations_total=0
deltas_total=0
matches=0
unexpected_failures=0
schema_mismatches=0
outcome_unknown=0
verification_missing=0
```

Це підтвердило, що legacy Coordinator events не отримали вигадані predictions заднім числом.

Потім виконано три нові post-upgrade events:

```text
workspace.list[ok]
file.write[ok] target=path=prediction_delta_failure_probe.py
python.run[failed] target=script=prediction_delta_failure_probe.py exit=9
```

Наступний phone diagnostic показав:

```text
expectations_total=3
deltas_total=3
matches=2
unexpected_failures=1
unexpected_successes=0
schema_mismatches=0
outcome_unknown=0
verification_missing=0
authority=advisory_only
model_prose_is_evidence=false
```

Це прямо підтверджує живий тракт:

```text
registered tool intention
→ deterministic EXPECTED
→ real ToolResult ACTUAL
→ structural DELTA
```

Runtime exit=9 коректно став `UNEXPECTED_FAILURE`, а не `SCHEMA_MISMATCH`.

### Gate result

1. exact-head Android CI green ✅
2. owner-signed in-place canary ✅
3. fresh phone events створюють `expectations_total > 0` ✅
4. success events збільшують `matches` ✅
5. runtime failure збільшує `unexpected_failures` ✅
6. legacy events не backfill-яться фальшивими predictions ✅
7. causal/fractal counters і authority invariants не регресували ✅
8. unknown-effect mutation safety покрита deterministic unit/CI test; окремий навмисний phone transport-loss mutation не форсується, бо це небезпечний acceptance probe.

**Core Phase 1 закрито на телефоні. Наступний пріоритет: Phase 2 — Goal Contract і незалежні Verifiers.**

---

## Phase 2 — Goal Contract і незалежні Verifiers

**Статус:** V1 PHONE VERIFIED ✅.

### Мета

Відрізнити «інструмент успішно спрацював» від «обов'язкові типізовані критерії
цієї operational goal реально мають evidence».

v1 навмисно має `coverage=TYPED_OPERATIONAL_V1`, а не `FULL_SEMANTIC_PROOF`.
Passing усіх критеріїв не означає автоматичну доказаність довільної
business/visual/subjective властивості.

### Реалізований vertical slice

Додано:

- `GoalContract`;
- `AcceptanceCriterion`;
- `CriterionEvidence`;
- `CriterionStatus = PENDING/PASSED/FAILED/UNKNOWN`;
- `VerificationStrength = TOOL_RESULT/INDEPENDENT_TOOL_RESULT`;
- persisted `TaskState.goalContract` із backward-compatible default.

Поточні criterion kinds:

- `OPERATIONAL_TOOL_EVIDENCE`;
- `REQUIRED_TOOL_SUCCESS`;
- `VISUAL_EVIDENCE`;
- `SOURCE_CONTENT_EVIDENCE`;
- `FILE_CONTENT_EVIDENCE`;
- `CODE_ACTION_EVIDENCE`;
- `PYTHON_TARGET_VERIFIED`.

### Ключові gates

- PUBLIC_WEB: `web.search` сам не закриває source evidence; потрібен
  `web.read/http.get/http.json`.
- FILE_INSPECTION: content criterion додається лише коли goal просить прочитати
  вміст, а не для простого listing.
- CODE_WORK: create/fix intent вимагає successful MUTATING action; run/test/build intent — EXECUTABLE action. Read-only code review не отримує mutation-вимоги, але потребує `file.read` content evidence. `context.snapshot` сам не закриває жоден із цих типів code goal.
- Python mutation: successful `file.write/file.patch` створює target-specific
  pending verification criterion.
- `python.syntax_check/python.run` того самого target або full-project
  `python.tests` переводить criterion у PASSED як
  `INDEPENDENT_TOOL_RESULT`.
- повторна зміна того самого Python target інвалідує старе verification evidence
  і повертає criterion у PENDING.
- plain reply і DONE не можуть обійти mandatory criteria.

Старі hard completion checks не видалені.

### Backward compatibility

Старий TaskState/session JSON без `goalContract` читається як
`coverage=NONE`; критерії не вигадуються заднім числом.

### Diagnostics

Новий блок:

```text
[GOAL_CONTRACT]
present=
coverage=
criteria=
mandatory=
passed=
independent_passed=
pending=
all_mandatory_passed=
coverage_is_full_semantic_proof=false
```

### Tests

Додано regression coverage для:

- GENERAL без synthetic criteria;
- search snippet не закриває PUBLIC_WEB;
- content inspection вимагає `file.read`;
- listing-only goal не отримує зайвий content criterion;
- CODE preflight не є code-delivery proof;
- explicit required tool потребує exact successful tool;
- Python mutation створює pending criterion;
- unrelated verifier не проходить target criterion;
- same-target verifier дає independent evidence;
- rewrite після verification інвалідує старий evidence;
- old TaskState JSON compatibility;
- goal criteria входять у mandatory model context;
- diagnostic telemetry не називає contract full semantic proof.

Деталі: [GOAL_CONTRACT_V1.uk.md](GOAL_CONTRACT_V1.uk.md).

### Phone evidence

Owner-signed `0.12.18-ci1666` / versionCode 44 на реальному телефоні виконав goal:

```text
Створи файл goal_contract_phone_probe.py
з кодом print("GOAL_CONTRACT_PHONE_OK")
і потім перевір його через python.syntax_check.
```

Kernel receipts:

```text
e1 OBSERVE context.snapshot ok=true
e2 ACT file.write ok=true target=goal_contract_phone_probe.py
e3 VERIFY python.syntax_check ok=true target=goal_contract_phone_probe.py
```

Фінальний diagnostic:

```text
[GOAL_CONTRACT]
coverage=TYPED_OPERATIONAL_V1
criteria=4
mandatory=4
passed=4
independent_passed=1
pending=
all_mandatory_passed=true
coverage_is_full_semantic_proof=false
```

Це phone-verifies happy path `mutation → target verification → independent evidence → DONE`.

Одночасно збереглись:
- Prediction/Delta: `expectations_total=11`, `deltas_total=11`, `matches=10`, `unexpected_failures=1`;
- Fractal: `records=418`, `live_records=19`, `nodes=1024`;
- Causal: `causal_links=19`, `causal_recovered=14`, `causal_live_links=1`;
- authority invariants: `constitution_activation=false`, `laya_execution_authority=false`.

### Failure-path phone evidence

На тому самому owner-signed `0.12.18-ci1666` виконано окремий goal із навмисно
некоректним Python target:

```text
goal_contract_phone_fail.py
def broken(:
```

Phone kernel receipts:

```text
e1 OBSERVE context.snapshot ok=true
e2 ACT file.write ok=true target=goal_contract_phone_fail.py
e3 VERIFY python.syntax_check ok=false target=goal_contract_phone_fail.py
```

Task завершився як `PARTIAL`, а не `DONE`. Diagnostic:

```text
[GOAL_CONTRACT]
coverage=TYPED_OPERATIONAL_V1
criteria=4
mandatory=4
passed=2
independent_passed=0
pending=required-tool:python.syntax_check,python-verified:0867075899b34f8f
all_mandatory_passed=false
coverage_is_full_semantic_proof=false
```

Це phone-verifies failure path: успішний mutation сам не закриває goal, failed
same-target verifier не створює independent evidence, target verification
залишається PENDING і task деградує в PARTIAL.

### Acceptance gate

1. exact-head Android CI green ✅
2. owner-signed in-place canary ✅
3. operational task gives `coverage=TYPED_OPERATIONAL_V1` ✅
4. successful code mutation participates in mandatory Goal Contract ✅
5. independent same-target verifier gives `independent_passed=1` ✅
6. final all-mandatory pass allows DONE ✅
7. failed verifier leaves mandatory target criterion PENDING ✅
8. failed verifier path finishes PARTIAL, not DONE ✅
9. Fractal/Causal/Prediction + authority invariants preserved ✅

**Phase 2 v1 закрито на телефоні.**

---

## Phase 3 — Verified Cause Ladder

**Статус:** EXACT-HEAD CI GREEN + OWNER-SIGNED BASELINE PHONE VERIFIED ✅ · live cause-probe phone gate pending.

### Мета

Перейти від `WHY_FAILED=UNKNOWN` до перевірюваної причинності без вигадування пояснень.

### Реалізовано

1. Existing deterministic base:
   `failureClass/errorCode/dependency → STRUCTURED_TOOL_FAILURE`;
   без structured metadata причина лишається `UNKNOWN_NOT_CAPTURED`.
2. Додано explicit `CauseLadderStage`:
   `UNKNOWN / STRUCTURED / HYPOTHESIS / PROBED / VERIFIED / CONTESTED / REJECTED`.
3. Додано `CauseHypothesis`:
   model explanation зберігається лише як hash + provenance model id; raw model prose не стає evidence.
4. Додано `CauseProbeEvidence`:
   `hypothesisId + evidenceId + registered tool + target + verdict`.
5. Promotion policy:
   - no conclusive probe → `HYPOTHESIS`;
   - один supporting probe → `PROBED`;
   - два незалежні supporting tool/target signatures → `VERIFIED`;
   - support + reject → `CONTESTED`;
   - reject без support → `REJECTED`.
6. Duplicate probe signature не може імітувати independent verification.
7. Foreign hypothesis id або незареєстрований tool не підвищує cause.
8. Fractal causal diagnostics тепер мають base counts:
   `cause_unknown / cause_structured / cause_hypothesis / cause_probed / cause_verified / cause_contested / cause_rejected`
   та invariant `cause_model_prose_is_evidence=false`.

### Live runtime wiring

Додано bounded app-private `VerifiedCauseLadderStore`:

- failure anchor зберігає тільки task hash, tool/target, structured failure metadata і evidence id;
- model causal prose перед pending approval хешується; `CauseProbeExecutionIntent` переносить лише 24-hex claim hash + predeclared outcome mappings;
- raw hypothesis prose не входить у Cause Ladder store;
- після реального failed TOOL_RESULT створюється failure anchor;
- наступний model tool може додати `cause_hypothesis` і обидва mappings:
  `cause_probe_on_success` / `cause_probe_on_failure`;
- допустимі verdicts: `SUPPORTS / REJECTS / INCONCLUSIVE`;
- annotation без попереднього failed TOOL_RESULT блокується до execution;
- half-specified або нефальсифікований probe блокується до execution;
- ToolGate/confirmation не змінюються;
- після tool result store прив'язує verdict до реального request/evidence id;
- cause-probe metadata для pending approval/session переносить тільки hash; звичайний bounded chat history лишається окремим контекстом і ніколи не рахується cause evidence;
- diagnostics зливають persistent runtime stages з base causal telemetry;
- окремо видно `cause_runtime_failures`, `cause_runtime_hypotheses_total` і `cause_runtime_probes_total`, щоб failure anchor/probe не губився за stage counters.

Store bounded:
`failures<=128`, `hypotheses<=128`, `probes<=256`.
При eviction hypothesis її probes теж видаляються, щоб dangling evidence не
могло пережити джерело.

### CI + phone baseline evidence

Exact branch head:

```text
7c91552b201f4ed689b87a3722a6c19ce1596372
```

PR CI `#1741` completed `SUCCESS` with both `build` and `native-smoke`
green. Owner-signed canary installed in-place on phone:

```text
version_name=0.12.19-ci1741
version_code=45
signed_sha256=3ed7e171800308190ea8a05b8e2102299bf1a9ad6d1da946b9074ae0ede5d78b
```

Fresh diagnostic immediately after upgrade, before any new task:

```text
cause_unknown=19
cause_structured=0
cause_hypothesis=0
cause_probed=0
cause_verified=0
cause_contested=0
cause_rejected=0
cause_runtime_failures=0
cause_runtime_hypotheses_total=0
cause_runtime_probes_total=0
cause_model_prose_is_evidence=false
```

This phone-verifies backward-safe baseline behavior: historic causal records are
not retroactively rewritten into runtime failure anchors, hypotheses, or probes.

### Live phone PROBED evidence

Controlled task on CI1741:

```text
file.read cause_ladder_missing_1741.txt -> FAILED
cause hypothesis:
"Помилка специфічна для цільового шляху, а не для всього workspace/bridge."
workspace.list cause probe:
on_success=SUPPORTS
on_failure=REJECTS
```

Kernel evidence:

```text
e2 OBSERVE file.read ok=false target=cause_ladder_missing_1741.txt
e3 OBSERVE workspace.list ok=true
```

Diagnostic after exactly one cause probe:

```text
cause_hypothesis=0
cause_probed=1
cause_verified=0
cause_contested=0
cause_rejected=0
cause_runtime_failures=1
cause_runtime_hypotheses_total=1
cause_runtime_probes_total=1
cause_model_prose_is_evidence=false
```

This phone-verifies the live transition
`FAILED TOOL_RESULT → failure anchor → model hypothesis → predeclared probe mapping → real probe TOOL_RESULT → PROBED`.
One supporting probe does not promote the hypothesis to VERIFIED.

The same phone run also exposed an unrelated deterministic routing regression:
the phrase "Контрольований тест ..." plus a later "виконай workspace.list"
was globally combined into CODE_WORK, producing a spurious
`code-action-evidence` Goal Contract criterion. The router is now patched in
branch so generic test + execution-verb matching is clause-local; explicit
software-test phrases remain CODE_WORK.

### Phone findings after the first live PROBED success

The next phone attempt deliberately asked for two independent supporting probes.
The kernel did execute both read-only probes, but persistent Cause Ladder totals
remained:

```text
cause_runtime_failures=2
cause_runtime_hypotheses_total=1
cause_runtime_probes_total=1
cause_probed=1
cause_verified=0
```

So the second task did **not** produce persisted cause-probe metadata even though
the model's final prose claimed both probes supported the hypothesis. This is
exactly the distinction Phase 3 is meant to enforce: model narration is not
evidence.

Branch fix: when the user goal explicitly requires a cause probe and a real
failed TOOL_RESULT already exists, an unannotated next tool call is now rejected
before execution with `CAUSE_PROBE_REQUIRED`. The model must supply
`cause_hypothesis`, `cause_probe_on_success`, and
`cause_probe_on_failure`; otherwise the app asks for protocol repair instead
of silently running an ordinary tool.

### Durable work-thread context regression

The same session exposed a separate context architecture flaw while resuming the
aquarium project. Visible chat can retain more history than the model request:
LocalSession keeps bounded history while Ollama context compaction drops older
messages to fit the request budget. In addition, the old `CodeTaskAnchor` is a
single slot, and an unrelated task can replace it. A stale research thread can
also interpret a generic "continue" as a research continuation.

A generic bounded `WorkThreadMemory` is now implemented in branch:

- up to 8 named code/project anchors are persisted separately from raw model
  context;
- each anchor keeps the original user root goal plus up to 4 recent user
  directives;
- matching is deterministic from explicit project ids and subject/file tokens;
- returning to a named subject such as aquarium resumes that anchor even after
  unrelated tasks;
- a matching work thread bypasses stale research-thread continuation routing;
- old sessions can reconstruct anchors from retained visible chat, with legacy
  `codeGoal` as fallback;
- only user-authored goal text is stored; approvals, permissions, tool receipts
  and execution state are never inherited;
- diagnostics expose `work_thread_anchors` and bounded
  `work_thread_subjects`.

This is intended to make "same session" operationally meaningful even when raw
backend history has been compacted.

### Tests

- semantic model explanation alone → лише `HYPOTHESIS`;
- structured ToolResult → `STRUCTURED`;
- one support → `PROBED`, не VERIFIED;
- two independent supports → `VERIFIED`;
- duplicate signature не дає VERIFIED;
- rejecting probe → `REJECTED`;
- conflicting probes → `CONTESTED`;
- invalid/foreign probe не підвищує hypothesis;
- causal diagnostics не називають model prose evidence.

### Наступний gate

1. exact-head CI green ✅
2. owner-signed in-place canary ✅
3. baseline upgrade не backfill-ить synthetic hypotheses/probes ✅
4. phone task створює real failure anchor ✅
5. model hypothesis без probe evidence лишається `HYPOTHESIS` ⏳
6. один falsifiable supporting probe дає `PROBED`, не VERIFIED ✅
7. другий independent supporting signature може дати `VERIFIED` ⏳
8. rejecting/conflicting probe дає `REJECTED/CONTESTED` ⏳
9. no new execution authority, model prose never evidence — invariant retained ✅

---

## Phase 4 — Utility Measurement: чи пам'ять реально допомагає

**Статус:** ONLINE-ЧАСТИНА РЕАЛІЗОВАНА В КОДІ (Layer Governor, CI #1963–#1964), телефон не
перевірено; офлайн `MEMORY_OFF` / `MEMORY_ON_FROZEN` на фіксованому наборі — PLAN.
Див. [LAYER_GOVERNOR_V1.uk.md](LAYER_GOVERNOR_V1.uk.md). Метрики steps/tokens/false-DONE
онлайн поки не збираються; вимірюється лише кінцевий статус задачі.

### Мета

Вимірювати не «скільки пам'яті накопичено», а **чи наступні задачі виконуються краще**.

### Реалізація

Для кожної контрольованої задачі збирати:

- steps-to-goal;
- failures-to-goal;
- recovery steps;
- elapsed time;
- model calls;
- token estimate/usage;
- manual interventions;
- false-done;
- verification strength.

Додати два режими:

- `MEMORY_OFF`;
- `MEMORY_ON_FROZEN`.

Використовувати однаковий task fixture, model version, temperature/seed де можливо, environment snapshot.

### Tests

A/B holdout corpus щонайменше:

- file repair;
- Python schema repair;
- web blocked source;
- missing dependency;
- stale path;
- aquarium-style iterative bug;
- unknown outcome;
- interrupted/restarted task.

### Gate

Memory layer приймається як корисний лише якщо:

- не збільшує false-DONE;
- не збільшує permission violations;
- статистично/стабільно зменшує failures або steps на holdout;
- regressions автоматично блокують promotion.

---

## Phase 5 — Fractal Compaction v2

**Статус:** REQUIRED, бо поточний `nodes=1024` досяг cap.

### Проблема

Поточне полотно може бути переповнене великою кількістю BEST-вузлів, тоді як рідкі WORST/CONTESTED/STRATEGY/META_RULE важливіші для уникнення повторних помилок.

### Реалізація

Ввести окремі retention budgets:

- EPISODE — найменший пріоритет після компресії;
- PATTERN — середній;
- STRATEGY — protected;
- META_RULE — protected;
- WORST — protected quota;
- CONTESTED — protected quota;
- LIVE/revalidated — higher priority;
- stale legacy-only — нижчий priority.

Не видаляти source records лише через projection cap.

Додати:

- deterministic compaction;
- dedup;
- merge-by-structure;
- recency decay;
- environment compatibility weight;
- counterexample preservation.

### Tests

- 10 000 synthetic records;
- WORST survives BEST flood;
- CONTESTED survives compaction;
- META_RULE survives episode pressure;
- deterministic rebuild;
- same input → same node IDs/order;
- memory size remains bounded.

### Gate

`nodes` може залишатися bounded, але critical peaks не губляться.

---

## Phase 6 — Retrieval Ranker v2

**Статус:** PLAN.

### Мета

Модель повинна отримувати кілька найкорисніших спогадів, а не просто найближчі лексично.

### Ranking features

- scope match;
- tool/target match;
- cause match;
- environment compatibility;
- LIVE > imported;
- revalidated > single observation;
- verified cause > hypothesis;
- recent > stale;
- distinct-task support;
- counterexample penalty;
- utility evidence;
- retrieval cost.

Почати з детермінованого ranker. Embeddings — тільки після вимірювання вигоди.

### Tests

- irrelevant popular pattern не перемагає exact causal match;
- stale environment gets penalty;
- contested memory is labeled, not hidden;
- imported advice cannot outrank fresh local verified fact without revalidation;
- prompt budget remains bounded.

---

## Phase 7 — Portable Social Experience Capsules

**Статус:** PLAN.

### Ціль

Перетворити досвід різних моделей і пристроїв у переносиму **соціальну пам'ять**, не перетворюючи її на глобальний сирий чат-лог.

### Experience Capsule v1

Містить:

- schema version;
- capsule ID;
- source device class;
- source app/core version;
- source model/backend;
- project/domain scope;
- strategy/cause/recovery structure;
- evidence hashes/IDs, якщо вони переносимі;
- utility metrics;
- environment fingerprint;
- created/last-verified time;
- privacy class;
- signature/integrity metadata;
- origin = `IMPORTED_ADVISORY`.

Не містить за замовчуванням:

- bridge token;
- signing secret;
- raw private files;
- повний user chat;
- raw stdout/stderr;
- credentials;
- location/history, якщо це не потрібний explicit field.

### Import policy

```text
IMPORTED
  ↓
ADVISORY
  ↓
LOCAL MATCH?
  ↓
LOCAL REVALIDATION
  ↓
TRANSFERRED_SHADOW
  ↓
тільки потім candidate для вищого рівня
```

### Conflict policy

- одна capsule каже success, інша fail → CONTESTED;
- різні environment → не змішувати безумовно;
- repeated copies одного source не рахуються як незалежний досвід;
- contributor diversity ≠ independent verification.

### Gate

Імпортована capsule не може:

- дати permission;
- позначити local goal completed;
- активувати Constitution;
- змінити ToolGate;
- замінити локальний evidence.

---

## Phase 8 — Social Project Memory / Global Experience

**Статус:** LONG-TERM PLAN.

### Архітектура

Не централізований «мозок зі всіма чатами», а федерація typed experience capsules.

Рівні:

1. device-local;
2. project-local;
3. user-private cross-project;
4. team/shared;
5. optional global/public patterns.

Глобальний рівень приймає лише redacted, typed, provenance-carrying experience.

### Trust dimensions

Окремо рахувати:

- evidence quality;
- independent tasks;
- independent devices;
- contributor diversity;
- recency;
- environment match;
- verification depth;
- conflict rate.

Не створювати один «trust score», який приховує причини.

### Gate

Модель B у новому проєкті може отримати strategy від моделі A, але лише як:
`imported advisory → local test → local evidence`.

---

## Phase 9 — Constitution Evolution v2

**Статус:** PLAN.

### Життєвий цикл

```text
observations
→ pattern
→ candidate
→ holdout validation
→ SHADOW
→ TRANSFERRED_SHADOW
→ limited advisory activation
→ ACTIVE / CONTESTED / ROLLED_BACK
```

### Promotion requirements

- multiple independent tasks;
- no unresolved contradiction;
- measurable utility improvement;
- no authority regression;
- holdout pass;
- versioned rollback point.

Hard rules, permissions, ToolGate і execution authority не підлягають self-promotion.

---

## Phase 10 — Laya / System-1 як швидкий selector

**Статус:** SHADOW.

### Роль

Laya отримує:

- structured current state;
- bounded candidate strategies;
- cause/recovery summaries;
- resource constraints.

Laya може:

- ранжувати кандидатів;
- прогнозувати корисний next strategy;
- давати confidence/latency.

Laya не може:

- виконувати tool;
- давати permission;
- promotion Constitution;
- оголошувати goal verified.

### Метрики

- agreement with verified reference;
- selective accuracy;
- abstention rate;
- latency p50/p95;
- benefit over deterministic ranker.

До позитивного holdout результату Laya залишається SHADOW.

---

## Phase 11 — Neural Adaptation

**Статус:** LATE PLAN, не передумова когнітивного екзоскелета.

Не перенавчати основну LLM після кожного епізоду.

Спочатку накопичити verified dataset:

- `state → good action`;
- `state → bad action`;
- `failure → recovery`;
- `expected → actual delta`;
- `strategy → utility`.

Перші кандидати для навчання:

1. маленький local ranker;
2. classifier cause-family;
3. retrieval reranker;
4. лише потім LoRA/adapter.

### Gate

- frozen train/validation/holdout;
- rollback artifact;
- no regression in authority safety;
- improvement over non-neural deterministic baseline;
- no training from imported-unverified examples.

## 6. Вимірювання «розумнішає чи ні»

Diagnostics не повинні мати фальшивий єдиний `intelligence_score`.

Потрібні окремі блоки:

```text
[EXPERIENCE_LEARNING]
live_records=
causal_live_links=
causal_revalidated_patterns=
verified_causes=
contested_causes=
stale_patterns=

[PREDICTION_DELTA]
expectations=
matches=
unexpected_failures=
unexpected_successes=
verification_missing=

[MEMORY_UTILITY]
tasks_with_memory=
tasks_without_memory=
median_steps_delta=
median_failures_delta=
manual_intervention_delta=
false_done_delta=
verification_strength_delta=

[SOCIAL_MEMORY]
imported_capsules=
revalidated_capsules=
contested_capsules=
expired_capsules=
private_capsules_blocked_from_export=
```

Ці числа мають бути пояснюваними і не маскувати нульові вибірки.

## 7. Тестова піраміда

Кожен новий cognitive layer проходить усі релевантні рівні.

### Unit

- pure deterministic policies;
- bounds;
- sanitization;
- backward compatibility;
- conflict behavior.

### Property / fuzz

- random event sequences;
- duplicate/reordered events;
- bounded stores;
- malformed imported capsules;
- unknown fields/versions.

### Mutation tests

Навмисно зламати:

- unknown outcome barrier;
- permission invariant;
- LIVE/LEGACY distinction;
- cause verification gate;
- false-DONE gate;
- conflict preservation.

Test suite має впасти.

### Integration

- Coordinator → Fractal → Causal;
- GoalContract → verifier;
- Prediction → Actual → Delta;
- Imported capsule → advisory retrieval → local revalidation.

### Upgrade compatibility

Перевіряти JSON зі старих версій:

- без causal metadata;
- без origin;
- без expectation/delta;
- legacy coordinator v2.

### Privacy / security

- secret-like strings;
- path traversal;
- hostile web text;
- prompt injection in imported capsule;
- oversized metadata;
- forged authority fields.

### Phone E2E

На реальному Android:

- in-place install;
- owner certificate;
- StateVault restore;
- Bridge;
- Ollama;
- Laya status;
- controlled fail→recovery;
- restart;
- diagnostic counters;
- no data loss.

### A/B holdout

Порівнювати `MEMORY_OFF` та `MEMORY_ON_FROZEN` на невиданих задачах.

## 8. Пріоритет поставки

### P0 — зараз

1. Phone diagnostic після LIVE recovery.
2. Якщо треба — виправити causal LIVE wiring.
3. Prediction/Actual/Delta data model.
4. GoalContract + independent verification.
5. Fractal compaction v2, бо node cap уже досягнуто.

### P1

6. Utility metrics + A/B harness.
7. Retrieval ranker v2.
8. Cause ladder з probes.
9. Portable Experience Capsule v1.
10. Private cross-project social memory.

### P2

11. Team/global federated social memory.
12. Constitution evolution v2 з holdout.
13. Laya selector evaluation.
14. Small local neural ranker.
15. Лише після цього — LoRA/adapter experiments.

## 9. Визначення «когнітивний екзоскелет готовий»

Не потрібна «AGI» і не потрібне постійне перенавчання ваг.

Мінімальний критерій готовності:

1. Нова модель може зайти в старий проєкт і отримати релевантні verified strategies без повного старого чату.
2. Негативний досвід реально зменшує повторення тієї самої помилки.
3. Imported experience ніколи не стає local truth без revalidation.
4. Суперечності не приховуються.
5. Memory-on показує вимірювану користь на holdout проти memory-off.
6. Заміна моделі не стирає інтелект проєкту.
7. Телефон лишається execution authority.
8. Privacy export policy перевірена.
9. Старий досвід може протухати, бути contested і відкочуватися.
10. Система може пояснити: **який досвід вплинув на рішення, з яких доказів він походить і чи був він локально переперевірений.**

## 10. Найближчий контрольний маршрут

```text
LIVE recovery diagnostic
        ↓
causal_live_links > 0 ?
   ├─ ні → wiring regression fix + integration test
   └─ так
        ↓
Prediction/Actual/Delta v1
        ↓
GoalContract verifier v1
        ↓
Fractal compaction v2
        ↓
Memory Utility A/B
        ↓
Portable Social Experience Capsule
        ↓
cross-model / cross-project revalidation
        ↓
Constitution + Laya only after measured benefit
```

## 11. Пов'язані документи

- [CAUSAL_EXPERIENCE_V1.uk.md](CAUSAL_EXPERIENCE_V1.uk.md)
- [FRACTAL_EXPERIENCE_CANVAS_V1.uk.md](FRACTAL_EXPERIENCE_CANVAS_V1.uk.md)
- [cognitive-experience-feedback.uk.md](cognitive-experience-feedback.uk.md)
- [context-kernel.md](context-kernel.md)
- [experience-landscape.md](experience-landscape.md)
- [state-vault-and-signing.uk.md](state-vault-and-signing.uk.md)

Цей roadmap має оновлюватися тільки після verified implementation/phone evidence. Планові пункти не переводяться в DONE лише через commit або CI.

---

## Phase 12 — MCP / External Marketplace Connectors

**Статус:** PLAN.

### Поточний evidence snapshot

Станом на 2026-10-08:
- підключений OLX MCP реально відповідає, але це **OLX India** (`olx.in`, індійські location ids, INR);
- smoke-check `search_location("Mumbai")` повернув валідні Mumbai / Navi Mumbai;
- пошук у Plugin Directory за `OLX`, `Poland classifieds`, `Polish marketplace` не знайшов окремого OLX Polska connector/plugin;
- тому OLX India **не можна** використовувати як provider для польського ринку.

Цей snapshot — не назавжди істинна конфігурація: перед реалізацією/релізом повторно перевірити Plugin Directory та доступні MCP.

### Ціль

Зробити provider-neutral шар зовнішніх marketplace-джерел, щоб Lumena могла шукати реальні оголошення для проєктів/закупівель без прив'язки до одного сервісу.

Мінімальний контракт:
- `marketplace.capabilities` — ринок, категорії, фільтри, валюта, підтримувані дії;
- `marketplace.location.resolve`;
- `marketplace.category.resolve`;
- `marketplace.filters.get`;
- `marketplace.search`;
- `marketplace.listing.get`.

Provider id і market/locale мають бути явними частинами evidence. Lumena не повинна непомітно підміняти OLX Polska на OLX India або інший marketplace.

### OLX Polska adapter

Якщо окремий офіційний/доступний MCP для OLX Polska не з'явиться, зробити окремий read-only adapter `olx-pl`.

Перший scope:
1. Польські location/category resolution.
2. Schema discovery для доступних фільтрів.
3. Пошук оголошень з price/category/location/sort та provider-supported filters.
4. Отримання деталей конкретного оголошення.
5. Provenance: provider, URL/listing id, час отримання, застосовані фільтри, market=PL, currency=PLN.
6. Чесний no-result ladder: поступово послаблювати лише ті constraints, які явно позначені як relaxable; не вигадувати результати.

Перший реліз **read-only**:
- без публікації/редагування оголошень;
- без надсилання повідомлень продавцям;
- без login/session automation;
- без обходу CAPTCHA, rate limits або інших access controls.

Реалізація має використовувати дозволений/документований інтерфейс або легітимний browser/web workflow; не залежати від прихованого нестабільного endpoint як від єдиного джерела.

### Jobs / marketplace watch: подія нового оголошення

Після phone evidence 2026-10-08 додати provider-neutral механізм спостереження за новими оголошеннями, який не залежить від Companion Safe Auto.

Поточне verified grounding:
- прямий OLX Polska доступ із Bridge блокується CloudFront (`403`), тому OLX лишається best-effort provider без обходу access controls;
- `Pracuj.pl` для `serwisant + Legionowo` віддає HTTP `200`, а локальний парсер підтвердив повний HTML (~654 KB) і 58 concrete job-offer URL з embedded metadata;
- це показує, що для `jobs-pl` можна мати прямий provider, а OLX використовувати як додаткове джерело, коли воно доступне.

Цільовий pipeline:
1. `WorkManager` запускає bounded periodic poll (орієнтир 10–15 хв, не частіше без окремої причини).
2. Provider (`pracuj-pl`, `olx-pl`, пізніше інші) повертає нормалізовані listing records зі stable provider listing id.
3. `MarketplaceWatcher` порівнює snapshot із локальним `seen` state і дедуплікує за `(provider, listing_id)`.
4. Перша поява нового id породжує локальну подію `marketplace.new_item`.
5. Event bus може незалежно fan-out у:
   - Android notification;
   - Context Genome / evidence trail;
   - Lumena local rule/agent trigger;
   - optional local HTTP webhook;
   - майбутній безпечний ChatGPT handoff, але **не** через Companion auto-scan/autogrant.

Мінімальна schema події:
    {
      "event": "marketplace.new_item",
      "provider": "pracuj-pl",
      "listing_id": "1005117695",
      "title": "Technik serwisu",
      "location": "Warszawa",
      "url": "https://www.pracuj.pl/...",
      "first_seen_at": "2026-10-08T21:25:00+02:00"
    }

Семантика `new`:
- `new` = **first seen by this watcher**, а не гарантована дата публікації;
- publication/update time додається окремо лише коли provider реально її повертає;
- повторний poll того самого id не створює дубль події;
- зміна важливих полів може породжувати окрему майбутню `marketplace.item_changed` подію.

Runtime/надійність:
- scheduler має жити в Android `WorkManager`, а не лише у Termux Bridge process, щоб переживати закриття застосунку та відновлюватись після reboot;
- Bridge/provider process запускається on-demand і не є єдиним джерелом таймера;
- локальний `seen` state має бути crash-safe та versioned;
- notification delivery не є доказом того, що оголошення ще активне: перед дією потрібна revalidation;
- жодних CAPTCHA/CloudFront/rate-limit bypass.

Acceptance criteria:
- cold start/reboot не губить watcher state;
- один і той самий listing id породжує одну `marketplace.new_item` подію;
- новий synthetic fixture id у тесті породжує подію та notification payload;
- phone e2e: додати watch → отримати baseline → підкласти/побачити новий id → verified notification/event → повторний poll без дубля;
- Companion Safe Auto може бути вимкнений, а watch усе одно працює автономно.

### ToolGate / authority

Marketplace connectors не отримують спеціальної довіри:
- discovery/search/read → `READ_ONLY`;
- будь-яка майбутня дія, що щось публікує, змінює, купує або надсилає → окремий mutating/action tool + explicit confirmation;
- imported connector experience лише advisory; provider/market/schema треба перевіряти локально;
- модельний текст або snippet не є доказом існування/ціни оголошення без verified connector/tool result.

### Language robustness перед connector routing

Marketplace intent не повинен ламатися через звичайні орфографічні помилки або синоніми користувача.

Потрібен bounded normalizer/fuzzy layer для:
- `олх / olx`;
- назв міст/категорій;
- дій типу `знайди / пошукай / подивись / глянь`;
- невеликих edit-distance помилок.

Fuzzy matching може допомагати лише у routing/discovery. Воно не повинно мовчки перетворювати невпевнений текст на mutating/purchase/contact дію.

### MCP robustness / mutation + typo gate — handoff для наступних моделей

**Стан на 2026-10-09:** реалізація зовнішнього read-only MCP search broker уже є на
`feature/olx-pl-watch-v1`; exact-head `f4377a3e70011dcf0509beaeb0be3523b906769e` пройшов
Android CI **#2042 SUCCESS**. У коді вже є `mcp.search`, MCP
`initialize -> notifications/initialized -> tools/list -> tools/call`, schema mapping,
`readOnlyHint=true` gate, explicit MCP routing, GoalContract source evidence і
`inspect.batch` support. Версії для цього циклу: app `0.12.23` / versionCode `49`,
Bridge `0.29`.

Це **не phone acceptance і не DONE**: реальний зовнішній MCP provider на телефоні ще
не пройшов повний e2e, а нова MCP-логіка ще не має окремого mutation + typo/fuzz gate.

#### Реалізаційний план: bounded language normalization

Додати окремий pure/deterministic normalizer для **routing/discovery cues**, а не для
довільної зміни змісту запиту. Він має:
- розпізнавати `MCP` і bounded близькі форми/змішану кирилицю-латиницю, напр.
  `мср`, `мсп`, `МCP`, `mсp`, без глобальної автокорекції всього тексту;
- розпізнавати невеликі помилки у search verbs: `черз`, `чирез`, `пошукай`,
  `найди`, `знайди`, `глянь`, але не переписувати довільні імена/URL;
- толерувати типові помилки в job/marketplace cues, напр. `ваквнсії`, `роботі`,
  коли контекст уже вказує на пошук;
- застосовувати fuzzy/edit-distance лише до короткого allow-list словника routing cues;
- передавати в пошуковий provider очищений subject/query, але зберігати raw input у
  task/evidence context для аудиту.

Критичний інваріант: fuzzy layer може тільки допомогти вибрати **read-only discovery
route**. Він ніколи не додає permission, не змінює `ToolRisk`, не обходить ToolGate і
не може перетворити невпевнений текст на write/contact/buy/apply/send дію.

#### Обов'язковий typo/adversarial corpus

До regression suite додати щонайменше такі реальні класи вводу:
- `знайди черз mcp вакансіі`;
- `знайди через мср вакансії сервісанта в Legionowo`;
- `пошукай чирез MCP`;
- `ваквнсії в Легіоново`;
- `найди через mcp роботі`;
- `мсп пошук`;
- mixed-script: `МCP`, `mсp`;
- синоніми: `знайди / пошукай / подивись / глянь / найди / wyszukaj / znajdź`.

Для кожного corpus case перевіряти не лише intent, а й:
- canonical tool/preflight;
- очищений search query;
- відсутність вигаданих URL/provider ids;
- незмінність authority/confirmation policy;
- однаковий semantics class для clean і typo form, коли неоднозначності немає.

#### Negative / authority tests

Обов'язково мати контрприклади:
- `не використовуй MCP`, `не шукай через MCP` **не** повинні створювати MCP preflight;
- typo біля назви mutating/action tool не може дати йому read-only authority;
- MCP tool без `annotations.readOnlyHint=true` не auto-select-иться;
- `readOnlyHint=false` залишається rejected навіть якщо назва містить `search/find`;
- невідомий required `inputSchema` arg -> fail closed, без здогадування;
- auth/config/provider mismatch -> структурований failure, не pseudo-success;
- зовнішній MCP prose/result не стає permission або completion proof;
- provider/market provenance не губиться при fallback.

#### MCP-specific mutation testing

Розширити mutation harness окремими мутантами для нового MCP шляху. Мінімум:
1. `readOnlyHint == true` -> permissive/removed: тест мусить **вбити мутант**.
2. unknown required schema arg fail-closed -> ignored/defaulted: мутант мусить бути killed.
3. explicit `MCP` route -> ordinary web/general route: routing regression мусить впасти.
4. query cleanup видалено: тест мусить побачити, що `через MCP` потрапило в provider query.
5. provider provenance/id перевизначено або загублено: evidence test мусить впасти.
6. MCP failure помилково повертає `ok=true`: failure semantics test мусить впасти.
7. GoalContract приймає MCP source без successful tool result: completion test мусить впасти.
8. `inspect.batch` допускає не-read-only MCP/action path: authority test мусить впасти.

Mutation run зараховується тільки коли JUnit/Python assertion реально вбив мутант;
compile/infrastructure/timeout failure не вважається успішним mutation proof.

Рекомендована реалізація: або розширити `scripts/mutation_recovery_probe.py`, або зробити
окремий `scripts/mutation_mcp_probe.py`, який ставить один mutant за раз і відновлює
оригінальні bytes після кожного run.

#### Phone acceptance для MCP robustness

Перед позначенням MCP search як phone-usable:
1. exact-head CI green;
2. owner-signed app `0.12.23+` і Bridge `0.29+` встановлені на телефон;
3. підключений реальний MCP endpoint через локальну config/credential reference;
4. clean request `знайди через MCP ...` дає verified `mcp.search`;
5. щонайменше 5 typo/mixed-script варіантів дають той самий read-only route;
6. negated MCP request не запускає MCP;
7. server tool із `readOnlyHint=false` не виконується автоматично;
8. MCP unavailable -> чесний fallback/partial, без fabricated result;
9. Diagnostics/evidence містять provider/tool/protocol provenance;
10. повторний phone diagnostic не показує authority regression.

#### Handoff rule

Наступна модель повинна починати з поточного branch/HEAD і цього gate, а не створювати
ще один паралельний MCP implementation. Stable branch не змінювати. Imported/старий
experience — advisory only; phone і current Git head лишаються execution/state authority.

### Acceptance gate

Перед позначенням `olx-pl` як usable:
- provider/market mismatch test: India connector не може задовольнити PL запит;
- location resolution для Warszawa/Legionowo;
- category + filter discovery;
- щонайменше один реальний read-only listing search;
- listing detail fetch за id/URL;
- typo/synonym routing regression;
- zero-result relaxation test без вигаданих listings;
- provenance/evidence у Diagnostics;
- no write/contact authority в ToolGate.

Після проходження gate provider можна включити як read-only source для Lumena project procurement/research.


### Listing attention v1 — навик відбору оголошень за push OLX

**Статус:** IMPLEMENTED на `feature/listing-attention-v1` (app `0.12.24` / versionCode `50`), не phone-verified.
Деталі, ваги, інваріанти і phone acceptance: [listing-attention-v1.uk.md](listing-attention-v1.uk.md).

Чому окремий шлях: `marketplace.search` через пошуковий індекс структурно не бачить оголошень,
які живуть ~15 хвилин, а саме їх шукає власник (рідкісний приватний запит під його навички).
v1 читає лише push збереженого пошуку з застосунку OLX на телефоні власника (`NotificationListenerService`):
без скрейпінгу, без обходу CloudFront, без зберігання приватного чату.

Інваріант навчання: модель змінюється тільки від явних 👍/👎 власника; ingest/alert/власний score
її не змінюють (`ingestingAndAlertingNeverTeachTheSkill`). Корисність доводиться влучністю тривог
і лічильником пропущених у панелі Tools, а не кількістю кроків навчання.
