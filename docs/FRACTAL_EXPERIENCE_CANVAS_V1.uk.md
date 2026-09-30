# Fractal Experience Canvas v1 — план реалізації

## Мета

Додати до Lumena локальне фрактальне полотно досвіду, яке стискає перевірені інструментальні події у рівні:

1. EPISODE
2. PATTERN
3. STRATEGY
4. META_RULE

На кожному рівні вершина класифікується як BEST, WORST, UNKNOWN або CONTESTED. Полотно є локальним, bounded, app-private і advisory-only. Воно не надає дозволів, не обходить ToolRegistry/ToolGate, не активує Constitution правила і не змінює Laya authority.

## Непорушні інваріанти

- тільки відомі TOOL_RESULT можуть давати позитивну/негативну мітку;
- unknown outcome не стає training label;
- model prose не стає verified evidence;
- cross-model досвід дозволений лише як advisory social memory з contributor IDs;
- BEST/WORST не є дозволом на виконання;
- CONTESTED не стискається у одне правило;
- Laya залишається SHADOW;
- Constitution promotion залишається окремим незалежним механізмом;
- existing ci1437/#107 state та owner signing не змінюються.

## Покрокові етапи і gate

### Step 0 — стабілізувати kernel-hardening baseline
- #108 має бути exact-head CI green.
- Якщо fail: виправити тільки failing regression і повторити CI.

Gate: Android CI green.

### Step 1 — pure FractalExperienceCanvasPolicy
- типи NodeLevel, Peak, Stage;
- deterministic projection з CoordinatorExecutionExample;
- hierarchy EPISODE -> PATTERN -> STRATEGY -> META_RULE;
- BEST/WORST/UNKNOWN/CONTESTED;
- counterexample links;
- distinct-task і contributor-model diversity;
- bounded retention.

Gate: pure JVM tests для positive, negative, contested, unknown, hierarchy, bounds.

### Step 2 — локальний AtomicFile store
- lumena_fractal_experience_canvas_v1.json;
- fail-closed decode;
- idempotent ingest;
- rebuild/advisory query;
- raw stdout/stderr та secrets не зберігаються.

Gate: round-trip, corruption fail-closed, duplicate ingest, bounded file projection.

### Step 3 — social cross-model advisory retrieval
- ingest тільки verified coordinator examples;
- aggregation across contributor models у тому самому project scope;
- prompt packet: BEST + WORST + CONTESTED/UNKNOWN;
- жодної execution authority.

Gate: cross-model examples combine; unrelated scope does not leak; prompt is bounded.

### Step 4 — shadow language-experience layer
- зберігати тільки короткі normalized language cues, які вже були детерміновано розв'язані;
- raw long user messages не копіювати;
- cue -> canonical intent observation;
- repeated consistent cues стають TRANSFERRED_SHADOW, conflicts -> CONTESTED;
- v1 не переписує CodeTaskAnchor автоматично.

Gate: multilingual variants, typo normalization, conflicts, privacy bounds.

### Step 5 — wiring
- onToolExperience: CoordinatorExperienceStore -> FractalExperienceCanvasStore;
- relevantMemoryProvider: bounded canvas packet;
- Diagnostics: [FRACTAL_EXPERIENCE_CANVAS];
- StateVault allowlist включає новий store;
- Nervous/Constitution/Laya authority без змін.

Gate: integration JVM tests + StateArchive tests + diagnostic formatter tests.

### Step 6 — full CI
- complete testDebugUnitTest;
- mutation recovery probe;
- native smoke;
- release assemble;
- signature/alignment/native-library checks.

Gate: exact-head Android CI green.

### Step 7 — signed canary APK
- тільки pinned owner certificate;
- certificate SHA-256 має збігатися з signing/certificate.sha256;
- APK SHA-256 фіксується;
- artifact має бути signed, не UNSIGNED input.

Gate: apksigner verify + CI artifact metadata.

### Step 8 — phone validation після оновлення
- in-place install;
- diagnostics;
- перевірка старого Experience/Constitution/Nervous state;
- новий canvas local + SHADOW;
- повторити transfer_recovery_lab;
- перевірити, що DONE gate, ToolRegistry і permission boundaries не регресували.

Цей етап не можна вважати виконаним до реального LUMENA_RESULT з телефона.
