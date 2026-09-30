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

**Статус:** BUG CONFIRMED → FIX IN CI.

### Телефонний доказ

Після контрольного Companion recovery:

```text
python.run[failed]
→ file.write[ok]
→ python.run[ok]
```

Coordinator повернув реальний `RECOVERY EXAMPLE`, але наступний phone diagnostic залишив:

```text
live_records=3
causal_live_links=0
causal_revalidated_patterns=0
```

Одночасно Constitution змінилася з `learned_active=1, contested=0` на
`learned_active=0, contested=1`. Це підтвердило, що Companion event доходить до
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
- canary bump до `0.12.15` / versionCode 41.

### Gate

- exact-head Android CI green;
- in-place owner-signed canary install;
- повторити контрольний fail→fix→success через Companion;
- phone diagnostic має показати `causal_live_links > 0`;
- повторний ingest не дублює link;
- FAILED_RECOVERY не стає RECOVERED;
- authority invariants не змінені.

---

## Phase 1 — Prediction / Actual / Delta Ledger

**Статус:** PLAN.

### Мета

Навчити Lumena пам'ятати не лише «що сталося», а **чого вона очікувала і наскільки помилилася**.

### Реалізація

Додати bounded типи:

- `ExpectedOutcome`;
- `ObservedOutcome`;
- `OutcomeDelta`;
- `ExpectationSource`;
- `VerificationStatus`.

Перший rollout повинен підтримувати deterministic expectations для типових інструментів:

- `file.write` → target має бути змінений;
- `file.read` → target має бути читабельний;
- `python.syntax_check` → syntax pass/fail;
- `python.run` → known exit outcome;
- `python.tests` → tests pass/fail/no-tests;
- `web.read` → retrieved/blocked/unknown;
- `health` → bridge responsive/unavailable.

Model-proposed expectation допускається як proposal, але ніколи не як evidence.

### Tests

- expectation survives JSON round-trip;
- old state без expectation полів читається;
- unknown tool outcome не дає fake delta;
- wrong target не рахується MATCH;
- model expectation не підтверджує себе;
- bounded/sanitized fields;
- imported expectation не стає local verified.

### Gate

Diagnostic показує:

- `expectations_total`;
- `deltas_total`;
- `unexpected_failures`;
- `unexpected_successes`;
- `verification_missing`.

---

## Phase 2 — Goal Contract і незалежні Verifiers

**Статус:** PLAN.

### Мета

Відрізнити «інструмент успішно спрацював» від «користувацька мета реально виконана».

### Реалізація

Додати:

- `GoalContract`;
- `AcceptanceCriterion`;
- `CriterionEvidence`;
- `CriterionStatus = PENDING/PASSED/FAILED/UNKNOWN`.

Перші типові критерії:

- файл існує/не існує;
- файл містить очікувану структуру;
- syntax pass;
- tests pass;
- HTTP endpoint віддає актуальний artifact;
- процес живий;
- package/version встановлено;
- signature/certificate збігаються;
- browser/manual confirmation потрібна й ще відсутня.

### Tests

- `done` неможливий без mandatory criteria;
- tool success alone не закриває goal;
- stale evidence invalidates criterion;
- зміна файла після test invalidates стару verification;
- manual/visual criterion не підміняється model prose.

### Gate

False-DONE regression corpus = 0 нових регресій.

---

## Phase 3 — Verified Cause Ladder

**Статус:** частково реалізовано через structured ToolResult metadata.

### Мета

Перейти від `WHY_FAILED=UNKNOWN` до перевірюваної причинності без вигадування пояснень.

### Реалізація

1. Deterministic mapping:
   `failureClass/errorCode/dependency → structured cause family`.
2. Окремий `CauseHypothesis` для неперевірених пояснень.
3. Probe має посилання:
   `hypothesis → probe action → evidence → result`.
4. `VERIFIED_CAUSE` дозволений лише після supporting evidence.
5. Якщо probe суперечить hypothesis — причина стає CONTESTED/REJECTED.

### Tests

- semantic model explanation alone не підвищує cause;
- structured ToolResult може дати STRUCTURED_TOOL_FAILURE;
- probe can promote/reject hypothesis;
- conflicting probes remain contested;
- no raw stderr injection into system prompt.

### Gate

Causal packet явно розрізняє:
`UNKNOWN / STRUCTURED / HYPOTHESIS / PROBED / VERIFIED`.

---

## Phase 4 — Utility Measurement: чи пам'ять реально допомагає

**Статус:** PLAN.

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
