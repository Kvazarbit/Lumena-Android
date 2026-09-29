# LUMENA NERVOUS SYSTEM PLAN v1

Статус: canonical implementation plan  
Гілка-основа: `fix/companion-streaming-race-v1` @ `aa261a23956c7ac74de326976c38f329ba1f2d47`  
Мета: дати Lumena системне самовідчуття власних дій і їхніх наслідків без перетворення спостережень на дозвіл, істину або автоматичне переписування Конституції.

## 0. Концепція, від якої не відхилятися

Lumena Nervous System — це не ще один planner і не ще одна модель. Це шар:

```
SENSE -> INTERNAL STATE -> SELF ACTION -> EFFECT -> INCIDENT/REWARD
      -> CAUSAL HYPOTHESIS -> VERIFIED EXPERIENCE -> SHADOW RULE
      -> A/B VALIDATION -> existing Constitution promotion gates
```

Система має відповідати на питання:
1. Що відбувалося зі мною і середовищем перед дією?
2. Що саме зробила Lumena?
3. Що змінилося після дії?
4. Чи є причинний зв'язок лише кореляцією, локально перевіреним ефектом, чи повтореним причинним патерном?
5. Що треба загальмувати зараз?
6. Що можна лише запропонувати як SHADOW-правило?
7. Що ще треба перевірити перед навчанням Конституції?

## 1. Незмінні інваріанти

Ці принципи важливіші за зручність реалізації.

### N1. Нервова система не надає дозволів
Вона не може обходити ToolRegistry, ToolGate, task grants, confirmation rules або телефон як execution authority.

### N2. Спостереження не є доказом причинності
"Після моєї дії сталося X" спочатку означає лише `CORRELATED`. Підвищення до `VERIFIED_EFFECT` потребує детермінованого verifier або повторюваного локального тесту.

### N3. Proposer != Judge
Модель може запропонувати пояснення інциденту. Верифікатор, policy kernel або тест вирішують, чи є доказ.

### N4. Model prose не змінює активну Constitution
Інцидент може породити лише observation/candidate/shadow evidence. Активне правило проходить існуючі ConstitutionGenome promotion gates.

### N5. Локальний verified evidence має вищу вагу за імпортований
Portable/imported experience — advisory source-device evidence only. Він не підтверджує локальний стан і не дає дозволів.

### N6. Біль не означає паніку
Один негативний інцидент може активувати локальний fail-safe, але не глобальне "ніколи". Узагальнення вимагає кількох контекстів і A/B/контрфактуального підтвердження.

### N7. Reflex має бути вузьким і оборотним
Hard stop дозволений тільки для безпосередньо спостережуваної небезпечної умови, наприклад:
`CHATGPT_GENERATING -> NO_CHATGPT_UI_MUTATION`.
Такий reflex не означає, що ширше причинне пояснення вже доведене.

### N8. Нервова система зберігає provenance
Кожен запис містить timestamp, subsystem, action kind, before/after state, evidence IDs/refs, causal grade і source.

### N9. Нервова система не бреше про невідоме
Немає after-state -> `OUTCOME_UNKNOWN`. Немає verifier -> не називати ефект verified.

### N10. Бounded by design
Події, кандидати, контексти й retention обмежені. Нервова система не повинна сама стати безкінечним журналом або прихованим контекстним смітником.

## 2. Органи системи

### 2.1 Interoception
Знімає внутрішній стан Lumena:
- Companion busy/idle
- active tool fingerprint
- pending auto-return
- task/grant state
- current bridge/model/runtime visibility
- restore/startup state
- self-action in flight

### 2.2 Exteroception
Знімає стан зовнішнього середовища, потрібний для оцінки наслідку:
- ChatGPT active
- ChatGPT generating
- ChatGPT UI last-change timestamp
- composer available
- send control available
- tool outcome / verifier observation

### 2.3 SelfActionTrace
Явно фіксує дії самої Lumena:
- TOOL_EXECUTE
- CHATGPT_SCAN
- CHATGPT_INSERT
- CHATGPT_SEND
- CHATGPT_ACTIVITY_OPEN
- AUTO_RETURN_QUEUE
- REFLEX_BLOCK
- RESTORE / RECOVERY action

### 2.4 Nociception
Виділяє "біль":
- action attempted while forbidden state was present
- outcome unknown
- verifier mismatch
- stream/UI transition suspiciously follows self action
- repeated failed recovery
- state invariant violation

### 2.5 ReflexKernel
Виконує тільки вузькі локальні гальма, підтверджені безпосереднім станом.
Перший reflex:
```
if chatgpt.generating:
    forbid INSERT / SEND / START_ACTIVITY side-effect
```

### 2.6 CausalMonitor
Будує causal hypothesis з grade:
- `CORRELATED`
- `REPRODUCED`
- `VERIFIED_EFFECT`
- `CONTESTED`
- `SUPERSEDED`

Жоден grade сам по собі не є дозволом.

### 2.7 NervousMemory
App-private bounded store:
- recent events
- unresolved incidents
- incident hypotheses
- verified reflex outcomes
- shadow rule candidates

### 2.8 Constitution Bridge
Тільки адаптер до існуючого ConstitutionGenome:
```
incident -> shadow candidate -> verified evidence refs -> existing calibration/promotion
```
NervousSystem ніколи не записує ACTIVE rule напряму.

## 3. Causal ladder

### Level 0 — OBSERVED
Є before/after, але дія Lumena не була між ними.

### Level 1 — CORRELATED
Self action була між before/after у часовому вікні.

### Level 2 — REPRODUCED
Той самий патерн відтворений повторно в контрольованому тесті.

### Level 3 — VERIFIED_EFFECT
Є незалежний verifier, детермінований simulator або paired A/B, що відділяє ефект self action.

### Level 4 — TRANSFERRED
Ефект підтверджено щонайменше у двох різних контекстах.

Тільки Level 3+ може бути promotion-eligible evidence; Level 4 потрібен для сильних загальних правил.

## 4. Incident lifecycle

```
OBSERVATION
 -> INCIDENT_CANDIDATE
 -> SHADOW
 -> AB_VALIDATED
 -> Constitution CANDIDATE/LEARNED via existing policy
```

Rollback:
```
SHADOW/AB_VALIDATED -> CONTESTED -> SUPERSEDED
```

Active Constitution ніколи не редагується "на місці" через nociception.

## 5. Перший вертикальний зріз: Companion stream race

### Sensors
Before:
- ChatGPT active
- generating
- last UI update
- pending auto-return

Action:
- queue result
- insert
- send
- activity open

After:
- generation state
- UI update timestamp
- send success/failure

### Reflex
`generating == true -> UI mutation blocked/queued`

### Incident
Якщо фактична UI mutation відбулась під час `generating=true`, це прямий verified invariant violation.

Якщо generation зникла одразу після self action без нормального end-of-turn proof — тільки `CORRELATED_STREAM_ABORT`, не verified fact.

### Shadow rule
`never mutate ChatGPT UI while generation is active`

Це правило може мати локальний hard reflex у Companion, але узагальнення "будь-яка UI дія під час streaming шкідлива" лишається SHADOW до broader tests.

## 6. Реалізаційні етапи

### Phase A — Pure kernel
Файл: `agent/core/NervousSystem.kt`
- typed frames/actions/incidents
- pure reducer
- causal grade
- bounded policy
- no Android dependencies

Acceptance:
- deterministic JVM tests
- no permission APIs
- no Constitution writes

### Phase B — Persistent nervous memory
Файл: `settings/NervousSystemStore.kt`
- AtomicFile
- bounded events/incidents
- fail-closed decode
- app-private
- include in StateArchive allowlist

Acceptance:
- corrupt file does not silently erase history
- bounded retention
- vault round-trip

### Phase C — Companion instrumentation
- record before/action/after around auto-return UI operations
- record reflex blocks
- never include secrets/composer payload text
- no tool args or tokens in nervous events

Acceptance:
- streaming UI is never mutated
- queued auto-return waits for stable turn
- tests prove event classification

### Phase D — Incident-to-shadow bridge
- derive SHADOW candidate only
- evidence requires locally verified incident outcome
- no direct ACTIVE transition

Acceptance:
- one incident never promotes
- correlated-only incident is not promotionEligible
- imported incident never promotionEligible locally

### Phase E — Causal A/B
- controlled regression tests for stream race
- with/without guard on synthetic UI policy state machine
- record regression/safety metrics

### Phase F — Generalize sensors
Apply same grammar to:
- tool recovery
- code/test loop
- web evidence loop
- local model/runtime
- Snake world-model

The concept remains identical; only environment adapters change.

## 7. Metrics

Track:
- incidents per 100 self actions
- reflex blocks
- false-positive reflex blocks
- outcomeKnown rate
- verifier acceptance rate
- reproduced incident rate
- mean time to detection
- mean time to resolution
- regressions after candidate rule
- calibration of causal confidence

Do not optimize only for fewer incidents; a system that stops doing anything is not healthy.

## 8. Anti-drift checklist

Before every nervous-system PR ask:

1. Does this code only sense/record/verify, or did it silently gain permission authority?
2. Are correlation and causality clearly separated?
3. Can model prose mutate state?
4. Is the verifier independent of the proposer?
5. Is the rule SHADOW before activation?
6. Are negative and positive outcomes both retained?
7. Is there rollback/contested handling?
8. Is storage bounded?
9. Are secrets and user message payloads excluded?
10. Does the implementation preserve phone execution authority?

If any answer is wrong, the implementation has deviated from this plan.

## 9. v1 Definition of Done

v1 is complete when:
- pure NervousSystemKernel exists and passes JVM tests;
- app-private bounded NervousSystemStore exists and is archived;
- Companion records stream-race interoception/self-actions/reflex blocks;
- no UI mutation can happen while ChatGPT is generating;
- at least one deterministic test proves a self-action incident is classified correctly;
- correlated incident cannot become promotionEligible;
- active Constitution cannot be mutated by NervousSystem directly;
- CI is green on exact head SHA.

This file is the conceptual anchor. Future implementation changes must update this plan explicitly if they change any invariant.
