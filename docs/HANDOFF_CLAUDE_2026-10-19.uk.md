# Передача роботи: що зробив Claude 19.10 (для аналізу зовнішньою моделлю)

Автор: Claude (Opus 5.5) на запит власника (Kvazar). Документ — **для рецензії**. Будь ласка, перевіряйте код, а не цей текст: нижче є точні коміти, файли й рядки.

Актуальна гілка: **`feature/experience-loop-fixes-v1` @ `5d470539`**, яка містить увесь код `codex/verified-experience-loop` @ `412935e7` (CI #2331). На момент запису `codex/verified-experience-loop` після `412935e7` не змінювався, тож злиття має бути fast-forward.

## 1. Карта комітів

| Коміт | Гілка | Що | CI |
|---|---|---|---|
| `22805a89`, `d7916dba` | `feature/lumena-exoskeleton-plan-v1` | План `docs/LUMENA_EXOSKELETON_PLAN_V1_UK.md`: аналіз когнітивних помилок (1A/1B), кроки 1–8, біблійна преамбула (чернетка) | #2319 success |
| `0d44f527`, `43068fd6` | `feature/claim-check-v1` | `ClaimCheck.kt` — детермінована перевірка вставленого тексту моделі проти квитанцій; блок у `CollaborationPanel` | #2327 red через тест, зламаний у базі `1666f5b6` (#2324 так само) |
| `26e10446` | `feature/claim-check-v1` | Злиття виправлення бази `c5120f35` | — |
| `96c8e820` | обидві | План, розділ 7: «найпростіший повний контур досвіду» (FixCase, 50/50, C1–C5, 7.6–7.8) | #2329 success |
| `07802e50`, `e9525730`, `412935e7` | `codex/verified-experience-loop` | **Ваша** реалізація розділу 7 (RepairCases, RepairLab, межа перевірки, відновлення) | #2331 success |
| `d8c709d1` | `feature/experience-loop-review-v1` | План, розділ 7.9: рецензія CI2331, 4 логічні помилки (лише документ) | skip ci |
| `376420d6` | `feature/experience-loop-fixes-v1` | Виправлення 1–4 з 7.9 (код + тести + мутанти), версія 0.12.34 (69), bridge 0.34 | — |
| `5d470539` | `feature/experience-loop-fixes-v1` | CI: регресія пам'яті запускається і на цій гілці | **Android #2333 success**, **регресія #27 success** |

Артефакт для підпису: `UNSIGNED-Lumena-ci2333-signing-input`, run `39306963538`, sha256 `c63c1a16d181ecc251c6b65e8c937984c091bc28c37aa4901a99c8a261a9ba08`. Телефон — **NOT_RUN**.

## 2. Чому виправлення 1–4 (коротко з розділу 7.9)

1. **Пошук випадку був водночас надто суворим і надто широким.**
   - Надто суворий: `advice`/`assign` вимагали точного збігу `scope` (включно з версією мети), `check` (хеш байтів **усіх** файлів тестів) і `signature`. Будь-який новий тест або зміна мети робили випадки недосяжними назавжди.
   - Надто широкий: `signature` = sha256(тип винятку + повідомлення першого падіння, цифри → `#`), без імені тесту. Різні тести з `AssertionError|assert # == #` вважалися одним випадком.
2. **`STEP_LIMIT` 8 рахував і читання.** Правило «прочитай перед зміною» (CAS) саме вимагає читань, тож метрика карала обережну модель.
3. **Межа тестів за замовчуванням порожня**, і тоді випадки мовчки не створюються.
4. **Дослід працював лише з Ollama digest**, а вбудована модель GGUF не атестувалась.

Ваші поправки до мого розділу 7 я **приймаю**:
- 80 епізодів ≠ 80 незалежних задач;
- `LayerGovernor` не ділить задачі на 8 окремих груп;
- уроки між проєктами можуть нести секрети, тому лишаються в межах проєкту.

## 3. Що саме змінено (з місцями в коді)

### 3.1 Bridge 0.34 — `termux/bridge.py`
- `python.tests` з `project_receipt=true` у `verification` віддає `failedTestId` — `file::classname::name` **першого** тесту, що впав (рядки ~4375, ~4391). Для PASS — `None`.
- `health`: `version=0.34`, нова ознака `failed_test_id_v1`.
- Сумісність:
  - застосунок #2331 + bridge 0.34 — ок, бо `TermuxBridgeClient.resultAdapter` не має `failOnUnknown`;
  - застосунок 0.12.34 + bridge 0.33 — ок, `failedTestId` = null, і діє старе точне правило.
- Тести:
  - `termux/test_verification_boundary.py::test_failed_test_identity_is_reported_only_for_a_failure`;
  - `termux/test_file_tools.py` (health 0.34).

### 3.2 Модель доказу — `agent/local/ToolModels.kt`
- `VerificationEvidence.failedTestId: String? = null`.
- `valid()`: довжина 1..300, без керівних символів.

### 3.3 Пошук і кроки — `collaboration/RepairCases.kt`

**Нові поля з типовими значеннями** (старий JSON декодується):
- `RepairCase.project/goal/testId`;
- `RepairEpisode.project/goal/testId/failure/reads`.
- `project` = hash(projectId, root) **без версії мети**.

**`Scanner.accept` (рядок 88)** — **правила створення випадку не змінено** (`check()`, `boundaryUnchanged`, UNKNOWN, `INTERLEAVED_WRITER`, `UNBOUNDED_MUTATION` — як було). Змінено лише лічильники:
- `steps` збільшують тільки `file.write`, `file.patch`, `python.tests`;
- `reads` — інструменти, що не пишуть;
- `git.*` не рахуються ні туди, ні туди;
- `MAX_STEPS = 8`, `MAX_READS = 30` → причина `READ_LIMIT`.

**`eligible(library, episode)` (рядок 157)** — нова функція. Спершу той самий `project`; для старих записів без `project` — той самий `scope`. Ранги:
- **0** — той самий `testId`;
- **1** — той самий файл тесту (`testId` до `::`) і той самий тип винятку (`failure` до `|`);
- **2** — старий точний збіг `scope`+`check`+`signature`, **лише якщо хоча б в одного запису немає `testId`**. Без цієї умови `assert # == #` з різних тестів знову об'єднувались би.
- У межах рангу — Jaccard слів повідомлення, потім свіжість; максимум 2.

**`advice` (рядок 177)** будується з `eligible`. Якщо змінились тести — дописується `; changed since: tests`, якщо мета — `goal` (або обидва). Ліміт 400 символів, захист від `[LUMENA_` і позначка DATA лишились.

### 3.4 Дослід — `collaboration/RepairLab.kt`, `RepairLabStore.kt`
- `RepairLabPolicy.assign` використовує `RepairCasePolicy.eligible`. Прив'язка досліду до `config.scope` (поточна версія мети) **збережена**: зміна мети посеред досліду — як і раніше, змінені умови.
- `start()`: бібліотека бере випадки з тим самим `scope` **або** тим самим `project`, тобто зі старих версій мети. Далі вона фіксується, як і раніше.
- Атестація вбудованої моделі: `ggufDigest` (`RepairLabStore.kt:43`) рахує потоковий SHA-256 файлу з `LumenaPreferences.ggufPath`. Розмір береться з `OpenableColumns.SIZE` або `AssetFileDescriptor`.
  - Кеш у prefs `repair_lab_gguf` за ключем `modelFileKey(path, size)`.
  - `start()` хешує завжди (`force = true`), а кожен запит бере кеш.
  - Маршрут `embedded|…` тепер дає digest; Ollama — без змін.
- Звіт: «медіана дієвих кроків (запис/тест)».

### 3.5 Межа тестів — `collaboration/VerificationBoundaryProposal.kt` + UI
- `inventory(result, root)` читає `files` з `project.inspect`. Перевіряються `schema`, `cwd` і `exists`; `partial` = `truncated` або `files_count` > довжини списку. Bridge віддає ≤ 80 файлів.
- `propose(files)` повертає `tests/` і `test/` як каталоги, плюс `test_*.py`, `*_test.py`, `conftest.py` поза ними; максимум 16. Відкидаються шляхи, які `ProjectWorkspacePolicy.root` не прийме (пробіли, `:`, `..`, `@`, порожні сегменти).
- `ExperienceLoopPanel`:
  - кнопка «Запропонувати межу з файлів проєкту» (рядок 58) лише заповнює поле, **зберігає власник** окремою кнопкою;
  - червоне попередження, якщо межа порожня.
- Виклик bridge: `WorkflowChatScreen.kt:2343` → `onInspectProject` → `ProjectWorkspacePanel` → `ExperienceLoopPanel`. Це прямий read-only `project.inspect` через `bridgeOrNull()`, як у `CollaborationStateCard.observe`.

## 4. Перевірки

**Kotlin.** Нові тести додано в список критичних у `android.yml`:
- `RepairCasePolicyTest`:
  - `sameFailedTestStillFindsItsCaseAfterTheSuiteGrows`;
  - `aGenericMessageInAnotherTestFileNoLongerJoinsUnrelatedCases`;
  - `sameTestRanksAboveSameFileAndOtherProjectsNeverMatch`;
  - `changedGoalKeepsTheCaseWithALabel`;
  - `readsBeforeAPatchDoNotConsumeEffectfulSteps`;
  - `ninePatchesWithoutPassHitTheEffectfulStepLimit`;
  - `endlessReadingIsUnresolvedWithItsOwnReason`.
- `RepairLabPolicyTest`:
  - `sameFailedTestIsAssignedAcrossAChangedVerifier`;
  - `modelFileIdentityIsAStreamingHashKeyedByFileAndSize`.
- `VerificationBoundaryProposalTest` — 3 тести.
- Старі тести не змінювались і пройшли: старий точний збіг працює для записів без `testId`.

**Мутанти** (`scripts/collaboration_probe.py`), обидва KILLED_EXPECTED у регресії #27:
- `generic-message-joins-tests`: умова рангу 2 → `true`. Ловиться маркером `UNRELATED_TEST_JOINED`.
- `reads-consume-repair-steps`: усі квитанції знову рахуються як кроки. Маркер `CAREFUL_READING_PENALIZED`.

**Bridge** — локально на Windows: `test_verification_boundary.py` 9/9, `test_file_tools.py` 9/9.

**Не перевірено:**
- телефон;
- Robolectric і Kotlin локально: на цій машині немає JDK, лише CI;
- реальне хешування великого GGUF на телефоні (час, SAF-провайдери);
- поведінка `OpenableColumns.SIZE` на конкретних файлових менеджерах.

**Процес.** Тести й код закомічено **разом**, без окремого червоного коміту. Роль доказу, що тести ловлять повернення помилки, виконують два мутанти. Регресію пам'яті довелося ввімкнути для цієї гілки окремим комітом `5d470539`; перший Android-запуск через це перезапущено.

## 5. Що прошу перевірити (можливі мої помилки)

1. **Ранг 1** (той самий файл + тип винятку). Чи не підтягує він усе ще слабко пов'язані випадки у великих файлах тестів? Можливо, потрібен поріг Jaccard.
2. **Бібліотека зі старих версій мети** входить у дослід. Чи не порушує це вашу умову «бібліотека фіксується до рандомізації і не змішує умови»? Я вважаю, що ні: вона фіксується на старті. Але позначки `changed since` можуть по-різному впливати на плечі.
3. **Лічильник читань.** `ollama.generate` тепер рахується як «читання», бо він не writer. `git.*` не рахуються взагалі. Чи правильно це для кейсу «модель зациклилась на git»?
4. **`MAX_READS = 30`** — число з голови. Можна виміряти на lumena-lab.
5. **Кеш GGUF за (шлях, розмір), без mtime.** Заміну файлу тим самим розміром між стартом і запитами кеш не помітить до наступного старту. Хешування 2–4 ГБ іде в `start()` на IO-потоці, а UI показує busy. Чи прийнятно це?
6. **`failedTestId` береться лише з першого тесту, що впав**, як і `signature`. При кількох падіннях випадок прив'язується до першого.
7. **Пропозиція межі** бачить лише ≤ 80 файлів `project.inspect`. У великому проєкті вона неповна; це показується, але тести можна пропустити.
8. **Межу ще не прив'язано до кнопки «Продовжити»** чи старту досліду: якщо власник не зберіг пропозицію, випадків як і раніше 0. Це навмисно (суверенітет), але, можливо, варто нагадувати частіше.

## 6. Правила спільної роботи, яких я дотримувався

- **Одна папка — одна модель.** Моя робоча папка — `Lumana_APK_Android\Lumena-claim-check` (окремий worktree). Вашу `Lumena-Android-trust-gate` я не чіпав.
- **Не пушити, поки повна CI гілки не завершилась**: `cancel-in-progress` скасовує попередню збірку. Документи — з `[skip ci]`.
- Нових підсистем безпеки не додавав. `CHARTER_LOCK` і Хартію не змінював.

## 7. Що далі (пропозиція)

1. Власник ставить #2333 і проходить чек-лист `docs/experience-loop-fixes-v1.uk.md`. Знайдене на телефоні виправляється першим.
2. Лише після цього — «Почати дослід 50/50» на lumena-lab. У lab-задачах ті самі тести мають падати на різних сіяних помилках, щоб пошук за `testId` давав справжній «схожий випадок», а не копію відповіді.
3. Якщо ваша наступна робота стосується `RepairCases.kt` / `RepairLab*.kt`, починайте від `5d470539`, щоб не розійтися.
