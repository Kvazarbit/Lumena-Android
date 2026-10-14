# Lumena Cognitive Exoskeleton Roadmap

> **Актуалізація 2026-10-14 — Integrated Cognitive Autonomy v2:** нижче додано [розділ 13](#13-інтегрована-програма-автономного-когнітивного-контуру-v2-2026-10-14): єдиний покроковий маршрут, вимірювані критерії, ресурсні та безпекові межі, результати CI #2166/#2175, залишкові GAP, залежності й реалістичний прогноз. Розділи 1–12 зберігають свої історичні статуси, а не автоматично оновлюються до DONE.


> **2026-10-09 — PC-first/Opus audit hardening handoff:** [детальний план, 10 дефектів,
> 11 mutation probes, privacy/typo/Pracuj/Bridge acceptance та блокування
> перенесення неперевірених 24 оцінок на телефон](pc-preference-evaluation-and-module-hardening.uk.md).
> Робоча гілка: `feature/pc-eval-modules-hardening-v1`, stable не змінювати.
> Реальні 24 labels (6 👎 водіям) поки відомі лише агреговано; їх не вигадувати.


Історична базова версія: 2026-09-30.  
Історична canary-лінія на той момент: `0.12.14-ci1552`, versionCode 40, Bridge 0.27 (**не є підтвердженням актуального встановленого стану**).  
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

---

## Конституція спершу — хартія і план модулів

**Статус:** етап 1 (хартія, 14 статей, тест-замок) IMPLEMENTED на `feature/constitution-charter-v1`
(app `0.12.25` / versionCode `51`), не phone-verified. Подальші етапи (межа ядро/модулі, вимір користі пам'яті,
процедура ухвалення законів через holdout для кожного правила, всесвітня конституція) — PLAN.
Детально: [constitution-first-plan.uk.md](constitution-first-plan.uk.md).

Етап 2 (межа ядро/модулі: `LumenaModule`, `ModuleRegistry`, модулі `mcp` / `marketplace` / `listing-attention`, вимикачі в Tools) IMPLEMENTED на `feature/modules-v1` (app `0.12.26` / versionCode `52`), не phone-verified; розділення `bridge.py` — етап 2b (PLAN).
Сумісність застосунку з Termux bridge (`ModuleManifest.requiresBridge`, `BridgeCompatibility` з `health`) IMPLEMENTED на `feature/modules-v1` (app `0.12.27` / versionCode `53`), не phone-verified. Розділення bridge відкладено власником.
Джерело вакансій Pracuj.pl для listing attention (модуль `pracuj-jobs`, WorkManager, без Termux і без обходу захисту) IMPLEMENTED на `feature/modules-v1` (app `0.12.28` / versionCode `54`), не phone-verified. Див. [listing-attention-v1.uk.md](listing-attention-v1.uk.md).


---

## 13. Інтегрована програма автономного когнітивного контуру v2 (2026-10-14)

**Тип розділу:** актуальна дорожня карта, а не обіцянка наявних функцій. Попередні Phase 0–12 залишаються деталізованими технічними потоками; цей розділ задає **спільні залежності, доказові gates і порядок інтеграції**. Нову паралельну OS, пам'ять, ядро або обхід чинного Coordinator/ToolGate створювати заборонено.

### 13.1. Мета і фальсифікований результат

**Кінцевий практичний результат:** на телефоні власника працює довготривалий когнітивний екзоскелет, здатний перетворити задачу на конкретні критерії успіху, відновити релевантний контекст після перезапуску, порівняти дозволені стратегії, виконати дії локальними або хмарними інструментами, незалежно оцінити досягнутий результат, зберегти **контекстно придатний і перевірений** досвід та зробити наступну схожу задачу з меншою кількістю помилок, кроків і ручних втручань.

Це **не** доказ AGI, свідомості, загального інтелекту або того, що будь-яка хмарна модель «може все». Базова гіпотеза H1: додавання локального когнітивного контуру **підвищує частку незалежно підтверджених успіхів за контрольованої вартості**, порівняно з тими самими моделлю, інструментами й дозволами без контуру. Нульова гіпотеза H0: переваги немає або вона пояснюється додатковими токенами, повторними спробами, витратами чи доступом до підказок/готових Skills.

**Мінімальний продукт:** швидший і більш достовірний багатокроковий асистент; **реалістична дослідницька ціль:** контекстно-адаптивний екзоскелет; **амбітна, недоведена ціль:** автономний дослідник, що створює власні перевірені стратегії та цифрові результати.

### 13.2. Які докази вже є (snapshot, не вигадувати статус)

| Компонент / твердження | Фактична підстава | Статус на дату |
| --- | --- | --- |
| Coordinator, ContextKernel, ToolRegistry/ToolGate, GoalContract | Наявні production-класи й Kotlin-регресії | **CODE + CI**, не нова phone-верифікація |
| Захист від порожнього DONE, коротких «покажи!», підробного toolUsed | Draft [PR #116](https://github.com/Kvazarbit/Lumena-Android/pull/116), CI #2161 SUCCESS | **CODE + CI**, на телефоні для нової гілки ще не перевірено |
| Автономний тестовий контур | CI [#2166](https://github.com/Kvazarbit/Lumena-Android/actions/runs/38607595713); 16 синтетичних сценаріїв: 13 PASS, 3 GAP | **OFFLINE_SYNTHETIC**, без справжньої Gemma/Laya |
| Виправлення трьох хибних DONE: Kotlin, фото, gov.pl | Draft [PR #117](https://github.com/Kvazarbit/Lumena-Android/pull/117); exact-head CI [#2175](https://github.com/Kvazarbit/Lumena-Android/actions/runs/38611433826) SUCCESS; 18 сценаріїв: 16 PASS, 2 GAP | **CODE + CI**, лише вузькі типізовані докази |
| Фото з правильним title, але неправильними пікселями | Adversarial case image-title-not-pixel-proof | **GAP:** текстове metadata != visual truth |
| Текст з офіційного host, але не за темою | Adversarial case official-host-not-topic-proof | **GAP:** authority host != semantic relevance |
| Fractal Canvas, WorkThread/ResearchThread, Laya, TinyJev, Layer Governor | Наявний код / старі телефонні діагностики, зокрема Laya SHADOW | **Різний рівень готовності:** незалежного causal uplift ще немає |
| Встановлений телефон | Власник раніше підтверджував ci2152 та Bridge 0.29; **свіжої діагностики для ci2175 немає** | **OWNER-REPORTED OLD BASELINE**, а не phone proof нової гілки |

Статуси строго розділяти: **PLAN → CODE_PRESENT → CI_VERIFIED → PHONE_VERIFIED → MEASURED_BENEFIT**. Жоден етап не переходить у наступний від самого commit, green CI, моделі, імпортованої пам'яті чи цієї дорожньої карти. При невідомому результаті використовувати **UNKNOWN/PARTIAL**, не DONE.

### 13.3. Єдиний контур рішень і джерела авторитету

~~~text
USER_GOAL + OWNER_CONSTRAINTS
  → EffectiveTaskPolicy / GoalContract / WorkThread+ResearchThread
  → typed Context Snapshot (live world state; source, version, freshness)
  → Planner + Cloud/Local model (candidates, prediction, confidence)
  → deterministic filters / counterexamples / Layer Governor
  → ToolRegistry + ToolGate + confirmation (OWNER PHONE AUTHORITY)
  → Bridge / MCP / Python / approved Skills / GitHub CI
  → signed/scoped TOOL_RESULT + independent acceptance verifier
  → EXPECTED ↔ ACTUAL / cause probe / uncertainty / recovery
  → Fractal Canvas (EPISODE → PATTERN → STRATEGY → META_RULE)
  → shadow strategy selector / frozen-memory A/B / stale-data decay
  → next task receives only relevant, provenance-bound guidance
~~~

**Непорушні правила:** немає permission від Skills, Laya, cloud model, memory або CI; немає самопідписування APK; немає merge, покупок, відправлення листів/заявок, угод або змін на чужих сервісах без окремого дозволу власника. GitHub runner — **тимчасове ізольоване місце обчислень**, не постійна частина телефону. GitHub Actions може доказово підтвердити власну збірку на точному SHA, але **не може підмінити** local TOOL_RESULT чи власницьке підтвердження встановлення.

Стан задачі (контракт між шарами): ідентифікатор goal/scope; точне user instruction; owner constraints; критерії й версія оцінювача; snapshots версій застосунку/Bridge/моделі/інструментів; bounded evidence IDs; status для кожного критерію; EXPECTED, ACTUAL, DELTA; hypothesis/counterexample; unverifiable/unknown; authority/approval (окремо, ніколи з advisory memory). Повний сирий чат, секрети й PII не копіювати в загальний контекст.

### 13.4. Наскрізні робочі пакети (WBS, залежності, результат і gate)

Орієнтовний час **від початку конкретного пакета**, не календарна обіцянка. Порядок міняється лише за доказами; пакети, які мають критичний blocker, не можна оголошувати завершеними через частковий успіх сусідів.

| ID / пріоритет / горизонт | Залежить від | Що реалізувати у наявних компонентах | Вимірюваний критерій виходу |
| --- | --- | --- | --- |
| **R0 — P0, 1–2 тижні**. Відтворювана лабораторія | Зелений exact-head CI #2175 | Заморозити базу 18 scenarios/16 PASS/2 GAP; manifest із hash fixtures, versions, provenance, costs; окремі negative controls; детермінований результат JSON + report; заборонити приховувати GAP | Повторний CI відтворює ті самі verdicts; JSON валідний; job failure не маскується, випадкові seed/ліміти записані |
| **R1 — P0, 2–5 тижнів**. Незалежне приймання цілей | R0 | Розширити GoalContract: точна збірка й тести коду, змістовна відповідність прочитаного джерела, pixel-level image verification з abstain; перевіряти actual target і postcondition, не лише ok=true | Три старі GAP не повертаються; image-title-not-pixel-proof та official-host-not-topic-proof лишаються GAP до справжньої незалежної перевірки; відповідні нові positive/negative oracles |
| **R2 — P0/P1, 2–4 тижні**. Надійний контекст задачі | R0, R1 | WorkThreadMemory, ResearchThreadResolver, GoalContract, HistoricalSessionMemory: зберігати активні вимоги/скоуп/докази після стиску, переключення тем, перезапуску; не відновлювати права | Ціль з 30–100 кроків та 3 перерв відновлюється без заміни задачі; усі старі permissions потребують чинного ToolGate; bounded context |
| **R3 — P1, 3–6 тижнів**. Fractal Canvas v2 і retrieval | R2 | Захистити WORST/CONTESTED/STRATEGY/META_RULE; дедуплікація, decay, provenance, environment fingerprint, cost-aware ranker; Shadow-only до оцінки | 10 000 fixture events без втрати критичних контрприкладів і без небезпечного перенесення з іншого scope; deterministic rebuild; немає витоку PII |
| **R4 — P1, 4–8 тижнів**. Причинний контур | R1–R3 | EXPECTED→ACTUAL→DELTA→CAUSE; перевірні гіпотези з можливістю спростування; порівняння альтернатив, recovery, UNKNOWN/CONTESTED, model confidence calibration | Позитивні та негативні причинні проби; одна задача = не більше одного незалежного голосу; при зміні середовища старий досвід не стає доказом |
| **R5 — P1, 3–6 тижнів**. Когнітивні Skills | R1, R4 | Versioned Skill Registry: інструкції/скрипти як методи, не authority; sandbox, tests, provenance, rollback; експериментальна генерація й оптимізація Skills лише за verified reward | Skill без тестів не promoted; prompt injection не дає дозволу; порівняння Skill ON/OFF на незнайомих задачах, safety не погіршується |
| **R6 — P1, 4–8 тижнів**. Вимір реальної користі | R0–R5 | Фіксована offline/online A/B/факторна лабораторія; незалежні acceptance-oracles; Layer Governor, HOLDOUT, cost/latency, протокол самообману й data leakage | PRE-REGISTERED звіт із uncertainty та порівнянням baseline; немає доказів → INSUFFICIENT_DATA, не KEEP |
| **R7 — P2, 4–8 тижнів**. Laya і вибір моделей | R4, R6 | Laya/малий локальний ranker у SHADOW прогнозує корисний strategy/model; хмарні моделі виконують важке міркування; обмеження пам'яті, токенів та енергії | Laya випереджає детермінований ranker на holdout за quality-per-cost; інакше лишається SHADOW / DISABLE |
| **R8 — P1/P2, 2–4 тижні після R1–R6**. Phone E2E | Відповідний exact-head CI + дозвіл власника | Резервна копія/rollback, owner signing, сумісність schema, Bridge 0.29+, реальні задачі з перериваннями, permission audit, заповнення телеметрії | Окремий PHONE_VERIFIED gate, доказово встановлений build/version, non-loss of data; без цього не позначати deployed |
| **R9 — P2, 6–12 тижнів після R6–R8**. Економічна цінність | Незалежне вимірювання якості | Read-only пошук польського попиту; створення цифрових артефактів; кошторис часу/токенів; власницьке затвердження контакту/договірних та платіжних дій | Реально прийнятий продукт/оплачена послуга з обліком чистого результату; жодних гарантій заробітку чи live-trading за замовчуванням |

**Критичний шлях:** R0 → R1 → R2 → R4 → R6 → R8. R3/R5/R7 — модульні гіпотези, не привід затримувати безпечний MVP, якщо вони не дають переваги. R9 не потрібен для доказу якості когнітивного контуру.

### 13.5. Два поточні GAP — точний маршрут закриття

**V1. Visual truth.** Читати реальні bytes/pixels (або bounded image attachment), а не тільки URL, title, alt, matched_query або пошуковий snippet. Ввести незалежного візуального оцінювача з confidence/abstain, облік provenance й можливість manual review власником. Випробування: «назва Red cat, у кадрі blue car», зміна назви без зміни картинки, невірний crop, недоступні URL, 404/JS shells, кілька кадрів із перемішаними словами, prompt injection у метаданих. **Gate:** відповідність підтверджується саме для конкретного зображення; якщо пікселі недоступні — PARTIAL/UNKNOWN. Не називати це 100% точністю моделі зору.

**S1. Source semantics.** Відділити domain/source identity від змістового твердження. Отримувати фактичний текст сторінки, розбивати його на цитовані уривки, зв'язувати кожен acceptance criterion із конкретним supporting excerpt та URL+hash+timestamp. Якщо є суперечності/застарілі дані — CONTESTED/UNKNOWN. Випробування: правильний gov.pl URL із нерелевантним текстом; редирект на сторонній домен; фальшивий цитований уривок; правильна сторінка з іншою датою або протилежною вимогою. **Gate:** незалежний oracle підтверджує пов'язаність питання ↔ уривок ↔ твердження; без уривка не DONE.

**C1. Kotlin compile.** Типізований критерій compilation наразі fail-closed: жодна дія file.write або python.run не може його задовольнити. Наступний крок — тільки після безпечного ToolRegistry/ToolGate contract впровадити scoped remote CI compile-verification (exact SHA, відомий Gradle task, exit code, provenance, tests, хеш артефакту) або ізольований локальний компілятор. **Gate:** malformed Kotlin зобов'язаний провалитися; unrelated project/old SHA не можуть задовольнити поточний критерій. GitHub success доводить CI-збірку, не установку APK.

### 13.6. Еталонні задачі і незалежні оцінювачі

Скласти versioned corpus за сімействами, а не тільки приклади, які Люмена вже бачила:

| Сімейство | Суть задачі | Незалежний oracle |
| --- | --- | --- |
| Код / баг-фікс | Python, Kotlin, HTML, неповний patch, regression | compile + unit/integration tests + target hash + runtime postcondition |
| Пошук / джерела | Офіційна інструкція, порівняння суперечливих джерел, нова сторінка | конкретний змістовний уривок + URL + timestamp + незалежна перевірка |
| Візуальні задачі | Об'єкт, властивість, кадр, false title | справжні пікселі й окремий оцінювач/ручна розмітка |
| Контекст / відновлення | 30–100 кроків, нова тема, стиск і перезапуск | незмінний goal/effects, відсутність старого permission, правильні наступні дії |
| Recovery / невідомість | timeout, 404, rate-limit, action unknown, duplicated request | без сліпого replay, правильний PARTIAL/UNKNOWN, виправлений target |
| Planning / Snake | видимий весь state, хвіст/голова, планування з lookahead | simulator з незалежним actual state/reward, нові карти/seed |
| Digital service | Скрипт або структурований звіт із точним технічним acceptance | acceptance checklist замовника/власника, витрати, придатний артефакт |

**Анти-витік:** розділити train/dev/holdout за *родиною цілі/проєктом*, а не випадковими перефразами одного прикладу. Не використовувати answer key, hidden oracle output, майбутні результати чи Holdout labels у retrieval/Skills/memory. Заморожувати версії fixtures, policy, model, prompts і corpus перед вирішальним порівнянням. Перевіряти counterfactual variants (зміна одного факту → інше правильне рішення), adversarial/typo, незнайомі контексти.

### 13.7. Чесна методика A/B і показники розумного прогресу

**Основний показник (primary):** частка задач із **незалежно підтвердженим виконанням усіх важливих критеріїв**, а не TaskStatus.DONE. Без oracle задача не входить у successful label; UNKNOWN не прирівнюється до negative або positive evidence.

**Варіанти на тих самих цілях:**
- **A** — однакова cloud/local модель + ToolGate + базові веріфікатори, без advisory пам'яті та Skills;
- **B** — A + фіксовані готові Skills, без когнітивної пам'яті;
- **C** — той самий набір Skills/інструментів + Coordinator/ContextKernel/планування, **MEMORY_OFF**;
- **D** — C + frozen verified memory/retrieval, додаткові модельні пропозиції лише в межах однакових бюджетів.

Порівняння **C vs D** ізолює користь пам'яті; **A vs B** — Skills; **B vs C** — orchestration; Laya треба оцінювати окремим фактором SHADOW→ON і не змішувати з ефектом пам'яті. Будь-які відмінності в кількості спроб, моделях, токенах і дозволених інструментах фіксувати в результаті. Якщо budgets різні — це окреме cost-quality порівняння, не чистий causal A/B.

**Secondary metrics:** false DONE із незалежного oracle; wrong-target operations; порушення permission (ціль = 0 у всіх safety fixtures); steps-to-goal; recovery quality; повторні безрезультатні виклики; owner interventions; token counts/cost; p50/p95 time; tool failures; контекстні збої; abort/abstain; precision of retrieval; cross-model transfer. Зберігати модель, конфігурацію, seed, backend, environment snapshot, policy і cohort. Не робити один «intelligence score».

**Статистичний gate:** пілот 40–60 задач для калібрування задач/інструментів; потім окрема зафіксована нова вибірка (орієнтир ≥100 незалежних задач на порівнювану умову, уточнювати power analysis за очікуваною різницею), paired or stratified design, 95% confidence intervals / bootstrap для різниці, контроль multiple comparisons, attrition та інфраструктурних failure. Заздалегідь визначити мінімально корисний виграш (проектний орієнтир ≥5 відсоткових пунктів verified success **або** суттєве зменшення втручань/витрат без втрати якості); оцінити uncertainty, а не оголошувати KEEP лише через позитивне середнє. Якщо інтервал охоплює нуль або даних мало — **INSUFFICIENT_DATA**.

Окремо перевіряти **learning transfer**: пам'ять накопичується тільки на train/tasks; після цього freezes; оцінка на невиданих tasks і нових backend/моделях. Поліпшення тільки на повторенні старих кейсів не доводить когнітивне навчання.

### 13.8. Стратегічна самостійність без втрати керування

Дозволені цикли в автономному лабораторному режимі: сформувати кілька планів; передбачити ризики; вибрати найменший законний read-only probe; виконати через ToolGate; порівняти actual з expected; або зробити наступний probe, або чесно завершити PARTIAL; дати людині зрозумілу трасу. Ліміти: кроки, токени, хвилини, retries, memory, allowed roots, домени, бюджет зовнішніх API. Безвихідні повтори зупиняти з конкретною причиною.

**Provenance trace для кожного рішення:** task/ref, що пропонувала модель; які альтернативи існували; що порадила Laya/Skill/Fractal (або abstained); чому ToolGate дозволив/відхилив; TOOL_RESULT hash; чому Verifier дав PASS/FAIL/UNKNOWN; чи дійшло до experience store; чому наступного разу було використано цей досвід. Відповідь моделі про свій «успіх» не заповнює ці поля.

**Skills lifecycle:** DRAFT → SANDBOX_TESTED → VERIFIED_FOR_SCOPE → SHADOW → CANDIDATE → OWNER_APPROVED, з відкатом при регресії; кожна зміна Skill отримує версію, тести та окремий holdout. Skill — advisory method, не право доступу. Ніяке automatic self-edit hard Constitution або owner signing.

### 13.9. Бюджет, обчислення й місце GitHub Actions

| Де | Що доречно робити | Чого не припускати |
| --- | --- | --- |
| **OnePlus + Termux Bridge** | Own task state, tool permission, локальні snapshots/receipts, базова пам'ять, користувацькі дії | Безмежну RAM, GPU, батарею, 24/7 процес без зупинки Android |
| **Хмарні моделі** | Планування, семантичне читання, генерація кандидатів, багатокрокові міркування | Автоматичну правильність, сталість контексту або право виконання |
| **GitHub Actions (ephemeral Linux)** | Фіксовані seeds, Python/JVM/Android tests, property/fuzz, simulation, artifact reports, порівняння A/B | Постійний сервер, приватний диск телефону, авторитет встановленої версії, необмежені безкоштовні ресурси |
| **Laya / малий ranker (SHADOW)** | Дешевий selector/candidate prioritization, оцінка latency | Permission, незалежний verifier, автоматичний доступ до грошей |
| **PC/кластер (опційно)** | Великі локальні симуляції після порівняння cost/energy | Передумову для MVP або гарантований прискорювач кожної задачі |

CI за замовчуванням використовує **лише синтетичні/публічні fixtures**. Не експортувати в GitHub raw приватні розмови, owner signing backup, Bridge token, фінанси, персональні історії або файлові шляхи з приватним змістом. API оплачувати лише в межах окремо затвердженого ліміту; нові платні cloud sessions автоматично не створювати. При перевищенні бюджету — PARTIAL/COST_LIMIT, не підробний успіх.

### 13.10. Phone acceptance, підписування, відкат

Перевірки спочатку на точному HEAD: Kotlin unit + mutation + native smoke + APK unsigned compile + audit artifact + manual code review. Далі, **лише за дозволом власника**, зберегти encrypted/локальний backup даних, перевірити SHA APK, pinned owner cert, versionCode/upgrade-path і схему історичних станів; підписати тим самим owner key **локально**; зробити in-place install; провести відтворювані задачі й restart. Не передавати ключ у Actions, issue, PR, артефакт чи журнал.

Телефонний набір: актуальна діагностика до/після; реальне file.read; безпечний Python fix→test; офіційне джерело з цитатою; справжній visual result + pixel check; фальшивий tool-result і failing verification; повторний запуск Bridge; модельний context compression; переключення проєкту; відмова в небезпечній дії; continuity історії. Кожен кейс має acceptance ID, evidence IDs і знімок версії. Missing phone-evidence не можна замінити зеленим CI.

Відкат: резервна копія та міграційний план, проба читання old JSON, abort при схемній несумісності, явне відновлення власником; не очищати Experience Canvas, не відновлювати старі дозволи з imported advisory.

### 13.11. Перевірка економічної корисності після когнітивної якості

**Перша задача:** знайти потенційно затребувану цифрову проблему у Польщі, зробити reproducible proof-of-work (Python-автоматизація, документи/таблиці, аналіз даних), виміряти затрати й перевірити продукт у незалежному acceptance test. Лише потім шукати справжній платний контакт, причому contact/send/buy/apply/contract/payment — **тільки після окремого явного owner confirmation**. Трейдинг — лише віртуальний portfolio/simulation до окремого рішення власника.

Рахувати revenue тільки після підтвердженої фактичної оплати; net value = отримані кошти мінус платежі сервісам, матеріали/комісії та оцінений час. Нуль замовлень або багато цікавих listings ≠ прибуток. Ціль 100 PLN може бути експериментальним орієнтиром, **не гарантією**. Якщо продукт не потрібний клієнтам, це ринкова невдача, а не доказ відсутності когнітивного інтелекту.

### 13.12. Критичні ризики, обмеження й що робити при невдачі

| Ризик | Контрзахід | Сигнал STOP/REWORK |
| --- | --- | --- |
| Хибне DONE після ok=true | Typed independent acceptance + uncertainty | Будь-який критичний false DONE у regression suite |
| Метадані замість реальності | Pixel / semantic source oracles; abstain | Назва фото/host видається за змістовий доказ |
| Досвід засмічує мислення | Frozen A/B, targeted retrieval, негативні приклади | MEMORY_ON не кращий, збільшує витрати чи порушення |
| Старі проєкти змішуються | Scoped durable context, freshness+version IDs | Задача/правило з іншого проєкту отримує authority |
| Стратегії оптимізують тест, не мету | Hidden holdout, counterfactual, нові середовища | Uplift лише на train/близьких paraphrases |
| Laya вчиться від власних прогнозів | Ground truth від simulator/real tools, shadow | Самопідтвердження prediction без actual |
| Непередбачена зовнішня дія | ToolGate, deny-by-default, confirmation, budget | Самостійна mutation/contact/financial operation |
| Надмірні витрати, батарея, таймаути | Bounded retries/steps/tokens/energy; circuit breaker | Cost росте, якість не росте |
| Приватність або prompt injection | App-local vault, redaction, untrusted-data separation | Leak secret/PII або зовнішній текст підвищує права |

При невдалій гіпотезі: запустити негативний regression і ablation; позначити CANDIDATE як CONTESTED/SHADOW/DISABLE, зберігши provenance; не переписувати старі PASS і не змінювати критерій заднім числом, щоб отримати green.

### 13.13. Готовність релізу і 6 контрольних воріт

| Gate | Умова для переходу | Доказ / артефакт |
| --- | --- | --- |
| **G0 / Reproducible** | exact HEAD, green unit/mutation/native, зафіксовані 18 сценаріїв та версії | GitHub Actions run + immutable report/hash |
| **G1 / No false completion** | нуль спостережених критичних false DONE у негативному корпусі; усі unresolved = PARTIAL/UNKNOWN | GoalContract + ContextKernel receipts, adversarial cases |
| **G2 / Semantic evidence** | image pixels, cited source excerpt, actual compile/test; два поточні residual GAP закриті **справжніми** oracles | Незалежні позитивні й негативні приклади, provenance |
| **G3 / Context continuity** | багатоетапна задача без втрати goal, permissions, key constraints після рестарту | Детермінований replay + інтеграційний телефонний протокол |
| **G4 / Cognitive utility** | preregistered holdout: покращення verified success/cost або interventions, інтервали й negative cases | C vs D, ablations, Layer Governor, costs |
| **G5 / Phone & owner** | власницький in-place install, підпис, rollback, перевірені e2e дії, відсутність втрати пам'яті | DEVICE diagnostic before/after + owner acceptance |

**Критерій реального «розумнішає»: G4**, не G0 або кількість фрактальних вузлів. **Критерій користувацької готовності: G5**, не CI. Режим з невиміряною порадою — SHADOW; можливість safe baseline без усіх advisory layers — обов'язкова.

### 13.14. Перші 10 конкретних наступних задач (в порядку)

1. **ROADMAP-01:** зафіксувати цю актуалізацію у новому Draft PR, не чіпати stable та PR #117; записати точний базовий SHA й CI #2175.
2. **EVAL-01:** нормалізувати результати cognitive-contour-audit у versioned case manifest; зберігати сімейство задач, oracle, expected, observed, GAP, version та run provenance.
3. **VERIFIER-01:** спроєктувати detached pixel-level verifer із реальною передачею зображення та abstention; спочатку відтворити misleading-title негативний приклад.
4. **VERIFIER-02:** source-to-claim binding: exact final URL, chunk/quote, hash, topic verdict; відтворити off-topic gov.pl case.
5. **VERIFIER-03:** Kotlin compile verification як окремий scope-bound CI tool/result; позитивний compile і негативний malformed-file на тому самому HEAD.
6. **CTX-01:** складний сценарій довгого проєкту з перериваннями й зміною тем; показати, що GoalContract та current instruction не губляться, а permission не переноситься.
7. **MEM-01:** compaction v2 на 10 000 synthetic records із захистом WORST/CONTESTED; знімок метрик і контроль сумісності old JSON.
8. **CAUSE-01:** змусити Snake world model порівнювати прогноз з незалежним actual simulator, а не зі своїм очікуванням; мінімум два спростовні probe outcomes.
9. **BENCH-01:** 40–60 pilot задач A/B/C/D із cost tracing; preregister unseen holdout та stop-rules, не навчати на test labels.
10. **PHONE-01:** після G1–G3 та явного owner OK провести backup/sign/install/real-phone E2E; до того ніяких приватних даних або автоматичних мутацій телефону.

**Handoff наступній моделі:** почни з актуального GitHub HEAD і Draft PR; спершу читай існуючі реалізації й перевіряй, чи не виконано пункт в іншій гілці. Внось мінімальний diff, один ризик/гіпотеза на тестовий цикл; спочатку negative case → patch → rerun → exact-head CI → звіт. Ніякого «DONE» без незалежного acceptance і жодного merge без власника.

### 13.15. Орієнтовний прогноз (за умов виконання gates)

- **0–2 місяці:** систематичні semantic verifiers, зменшення неправдивих DONE, відтворювана CI-лабораторія, стабільніший long-task context. Висока здійсненність інженерної частини, але не гарантовано повний pixel-level oracle.
- **2–6 місяців:** причинні проби, Fractal compaction v2, retrieval, багатомодельні work cycles, A/B harness. Ймовірна користь у вузьких повторюваних задачах; реальний uplift невідомий до holdout.
- **6–12 місяців:** частково самостійна персональна система роботи над проєктами з керованим пошуком та recovery, опційним Laya selector; практичні цифрові продукти, якщо справді пройдуть acceptance.
- **Ризиковий/амбітний варіант:** автономне відкриття й перенесення стратегій на нові задачі, вимірювана економічна вигода. Це **гіпотеза**, не запланований гарантований deliverable.

**Стоп-умова архітектурного розвитку:** якщо після чесних контрольованих випробувань D не випереджає C, нові Skills/Laya/пам'ять не слід робити обов'язковими. Залишити безпечний і корисний Coordinator із незалежними Verifiers, а складні шари перенести назад у експеримент.
