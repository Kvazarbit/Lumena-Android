# EmbeddingGemma 2 для Lumena Android — план V0–V3 та приймання

**Режим:** експериментальний `SHADOW`; не впливає на автономне виконання.  
**Вихідний репозиторій:** Kvazarbit/Lumena-Android, окрема Draft-гілка поверх PR #130 та #129.  
**Модель:** https://huggingface.co/google/embeddinggemma-2 (назва й короткий уривок від власника).

## 0. Межа знань та ліцензія

Користувач навів уривок Benefits: EmbeddingGemma 2 описана як мультимодальна embedding-модель менш ніж 1 млрд параметрів для edge/on-device. Повна модельна картка та жодні ваги у цій сесії не отримувалися. **Не встановлено** фактичні ліцензійні умови, точну розмірність вектора, квантування, сумісність GGUF/llama.cpp/Ollama, модальності, мультимовну якість, ціну оперативної пам'яті чи можливість fine-tuning. З цього уривка НЕ випливає, що тег `embeddinggemma-2` доступний у `ollama pull` або що Ollama підтримує цю архітектуру.

Ця реалізація додає **текстовий** адаптер до стандартного локального `/api/embed`, а НЕ підтверджене вбудоване виконання саме ваг Google EmbeddingGemma 2. Станом на V0 ваги не завантажуються й не включаються в APK. Якщо модель не встановлена локально або не підтримується Ollama, інтерфейс чесно показує помилку, а не імітує embedding. У разі несумісності адаптер замінюється після окремих тестів архітектури/ліцензії без змін Cognitive Genome.

## 1. Мета та межі

Люмена зберігає першоджерельні записи про успіхи, невдачі, тупики, суперечності й відновлення. Коли модель отримує нову задачу різними мовами чи з друкарськими помилками, семантичний індекс має **знайти кандидати на перенос принципу** навіть без збігу слів. Проте висока схожість не означає ні релевантності, ні істинності, ні права запустити інструмент.

Непорушна межа: `Model/Embedding → ADVISORY_HINT`; лише `ToolRegistry + ToolGate + owner-approved task policy` можуть дозволити дію; `GoalContract + незалежний postcondition-verifier` визначають результат. Зміни в семантичному модулі НІКОЛИ не змінюють ToolGate, Constitution і права.

## 2. V0: що реалізовано в APK-гілці

| Компонент | Реалізація | Межа |
|---|---|---|
| Модуль | `modules/SemanticMemoryModule.kt` у `BuiltInModules`, ID `semantic-memory` | Жодних tool API, автоматичного routing чи підвищення повноважень |
| Opt-in | `ModuleSettings`: модуль вимкнений за замовчуванням і на телефонах зі старими збереженими прапорцями | Увімкнення лише власником, не з StateVault |
| Дані | `SemanticMemoryPolicy.documents()` читає обмежену V0-проєкцію `CognitiveGenomePolicy.derive` | Першоджерела Fractal Canvas незмінні |
| Структура | 4 контракти → негативні → суперечливі → відновлення → позитивні кандидати | До 48 документів за одне ручне індексування |
| Вектори | `LocalEmbeddingClient`: `http://localhost/127.0.0.1/::1`; `/api/tags` + `/api/embed` | Лише ручна операція; жодного remote/cloud fallback або `pull` |
| Model pin | Точний тег, ненульовий розмір локальної моделі, 64-символьний digest | При оновленні ваг вектори не змішуються |
| Сховище | `lumena_semantic_memory_v0.db` із модельним digest, ID принципу, hash ревізії, вектором та метаданими | Кеш 512 записів без автоматичного видалення |
| Збереження | `StateArchive.databases` + наявний StateVault з integrity_check | В старих бекапах файлу немає; нові бекапи містять за наявності |
| UI | `SemanticMemoryPanel` у налаштуваннях; перевірка тегу, ручна індексація й пошук | Без автоматичної подачі результатів у модель |
| Тести | `SemanticMemoryPolicyTest`, `LocalEmbeddingProtocolTest`, модульні інваріанти | JVM fixtures без реального Google embedding |

Не зберігаються: модельні повідомлення, raw stdout/stderr, повні тексти чатів, приватні шляхи файлів, ключі, токени й ACL. Індексуються лише структуровані описи `stage/mechanism/condition/toolPattern/failureClasses`. ID доказів залишаються в авторитетному Canvas, кеш тримає лише кількість та hash ревізії.

## 3. Безпека та режими відмови

- `semantic-memory` за замовчуванням OFF навіть після оновлення APK, якщо старий користувач уже налаштовував модулі. Увімкнення лише у UI.
- Тільки локальний Ollama, перевірка тегу в `/api/tags`, позитивного розміру, digest; заперечення `:cloud`, `-cloud`, remote-імен та зовнішніх URL. Без auto-pull / інтернет-завантаження ваг.
- Відсутність моделі, HTTP-помилка, нечислові/нульові/NaN embeddings, неправильна розмірність, зміна digest → явна відмова. Немає словесного «імітаційного вектора».
- До 4 документів на запит, максимум 700 символів кожен. Розмір відповіді обмежений; тайм-аути. Реалізація не надає ізоляції процесів і не гарантує, що весь ланцюг Ollama внутрішньо лишається локальним — це ще потрібно перевірити на власному runtime.
- Якщо модуль вимкнути, історичні записи не стають пошкодженими. Модель не отримує жодних нових дозволів.
- Пошук за замовчуванням тільки в `GLOBAL` плюс за явним обраним scope в `SemanticMemoryPolicy.rank`; перенесення чужого проєкту без revalidation заборонене.
- Вектор із застарілою ревізією/контрприкладами не повертається як актуальний.
- Ліміт не викликає очищення давніх негативів. При досягненні обсягу модуль відмовляється дописувати.
- Оскільки індексуються лише описові шаблони V0, а не глибокі пояснення завдань, приріст семантичної якості поки невідомий.

## 4. Дизайн індексування

Формат одного кандидата:

```text
id: cg0-negative-...
stage: NEGATIVE_DEAD_END
scope_hash: hashed owner project identifier
text: stage=... mechanism=... condition=... operation=... failure_classes=...
revision: SHA256(version|id|scope|text|evidence IDs|record IDs|counterexamples)
embedding: normalized float vector (validated finite, 2..4096 dimensions)
model_key: exact installed model tag @ exact local model digest
```

Операції: `index` → верифікація локальної моделі → незмінна вибірка до 48 кандидатів з наявного Canvas → пакети по 4 → `/api/embed` → атомарний запис пакета. `search` → локальний vector запиту → нормалізована cosine-схожість → джерела збігаються за revision й scope → top 6 лише для UI. **Ніякого промпту моделі або виконання інструмента**.

## 5. Критерії приймання

**G0 / GitHub.** Draft PR, точний HEAD; Kotlin/JVM тести, у CI обов'язковий XML target suites з `tests > 0` і без errors/failures/skips; Android unsigned APK + native-smoke; відсутність ToolGate/Bridge змін. Зелений CI НЕ доводить, що Google-ваги запустяться на телефоні.

**G1 / власник телефона.** StateVault — `Save now` і перевірена копія; пакет і підписувач звірені; лише власницький підпис на телефоні; `versionCode` має зрости. Відключені модулі й Fractal `records/nodes/negative/recovery` до та після порівняні. `ModuleRegistry.violations=0`. В `[MODULES]` нова секція показує `semantic-memory active=false`, доки власник явно не включить її.

**G2 / локальна модель.** Увімкнути модуль вручну, перевірити точний installed-tag/digest/bytes через `/api/tags`; якщо модельної карти та тегу немає — HALT. `/api/embed` повинен повернути вектори потрібної розмірності й можна вручну перевірити 2–3 тестові тексти. Не підмінювати cloud-моделлю. Зафіксувати latency, RAM, розмір БД, температуру/батарею, вміст StateVault.

**G3 / оцінка цінності.** Заздалегідь зареєстровані щонайменше 60–100 незалежно перевірених запитів у 3 сім'ях: програмування, перевірка фактів, формальні правила. Парні тести current lexical-only `ExperienceMemoryIndex.relevant`, embedding-only, lexical+embedding. Для кожного: Recall@5, nDCG@10, false match, контрприклади/негативи, модельний context tokens, батарея, латентність. Результати повинні переноситися між різними мовами і перефразуваннями без data leakage з training у test. Висока схожість сама по собі не рахується користю.

## 6. V1–V3 (план, ще не реалізовано)

**V1: Provenance Ledger.** Незмінюваний індекс 10 000+ структурованих інцидентів із provenance і позитивними/негативними/суперечливими шляхами. Зокрема, помилки без TOOL_RESULT, `FALSE_DONE` та `GOAL_TOOL_MISMATCH`. Повноцінне визначення області застосування, preconditions, версій і незалежних postconditions; міграції та відкат із StateVault, жодного очищення старих даних. Виправити проблему #131 до допуску семантичних підказок в автономне планування.

**V2: Пошук і перенесення.** Гібрид lexical+embedding+verified-evidence reranking; негативні hard examples; сліпий A/B `memory OFF / frozen embedding ON` зі сталими ToolGate дозволами й оцінюванням правильного user goal. Не надавати прав із пам'яті.

**V3: Донавчання.** Тільки після незалежного підтвердження ліцензії, trainable checkpoint, сумісності й набору розмічених прикладів: контрастивне навчання на трійках `query, correct verified principle, hard negative`, holdout за проєктом і часом, захист від хибних негативів. Навчання за можливості на ПК/GPU, експорт оптимізованої моделі на Android, збереження старого digest/індексу для rollback. Не донавчати на `DONE` як мітці правильності. Мультимодальний inference допускається лише після перевірки дійсного image/audio контракту цього checkpoint.

**Не вважається завершеним у V0:** завантаження/запуск саме EmbeddingGemma 2, донавчання її ваг, інтелектуальний приріст, незалежний goal verifier, масштабування до 1 млн рядків коду.


## Реалізований наступний крок: Android LiteRT-LM V1 (Draft, ПОТРЕБУЄ CI І ТЕСТУ ТЕЛЕФОНА)

- **Офіційний вихідний код API** у `google-ai-edge/LiteRT-LM`, реліз `v0.18.0` (2026-10-06): `EmbeddingEngine`, `EmbeddingEngineConfig`, `EmbeddingOptions`, `InputData.Text`, `EmbeddingResponse.embedding`, `ModelInfo.from(...)`. Реліз включає EmbeddingGemma 2 text, vision, audio. Посилання:
  - https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/EmbeddingEngine.kt
  - https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/EmbeddingConfig.kt
  - https://github.com/google-ai-edge/LiteRT-LM/releases/tag/v0.18.0
- **Версія Maven** `com.google.ai.edge.litertlm:litertlm-android:0.18.0`, підтверджена Google AI Edge Gallery:
  - https://github.com/google-ai-edge/gallery/blob/main/Android/src/gradle/libs.versions.toml
  - https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/build.gradle.kts
- **Реалізація**: `SemanticLiteRtModelStore.kt` копіює вибраний власником Android SAF `.litertlm` в приватні файли застосунку з SHA256 та лімітом 1.2GB; `ModelInfo.Embedding` перевіряє, що це текстова embedding-модель і оголошує розмірність та можливість CPU. Копіювання атомарне; існуюча обрана модель не знищується при помилці. Оригінал з Android Document Picker не редагується.
- `LiteRtLmEmbeddingProvider.kt` реалізує той самий `LocalEmbeddingProvider`, але запускає **справжній** Android `EmbeddingEngine` із `Backend.CPU(threadCount=2)`, в окремому диспетчері, із гарантованим `close()` у кінці ручної операції.
- `SemanticMemoryPanel.kt`: вибір між Ollama (без змін базового генератора) та LiteRT CPU; ручний імпорт/запуск реального smoke; індексація до 48 принципів пакетами до 4 без фонової активності; STOP не може обірвати поточний синхронний JNI виклик, але припиняє наступні; ручний пошук і огляд live-кандидатів.
- **Ідентичність векторного простору** `litertlm-0.18.0-cpu-text-embedding-v1@<full sha256 of imported bundle>`; ніколи не змішувати Ollama, інший digest, текст/vision, runtime, контекстні параметри. Точний текстовий processing/prompt mode треба перевірити на реальному комплекті моделі та freeze до A/B.
- **Немає ваг у APK.** `GGUF` і Ollama-модель `embedding_gemma2` не є файлами `.litertlm`; потрібен саме `litert-community/embeddinggemma-2-text-270m-litert-lm`. Не використовувати приватні ваги встановленої Google Gallery без окремого експорту.
- **StateVault** зберігає первинний Fractal Canvas та вторинний векторний SQLite-кеш; великі перезавантажувані ваги знаходяться в приватному сховищі та **не** входять в StateVault через 64MiB ліміт. Тому окремо збережи оригінальний завантажений пакет.
- **Readiness = справжній вектор**, не успіх `ModelInfo` або `/api/tags`: `smoke()` спочатку ініціалізує native SDK і генерує скінченний ненульовий вектор, перевіряючи розмірність. Якщо ця операція падає або відсутній native SDK, стан `NOT_READY`; у SQLite не записуються вигадані вектори.
- **SHADOW only**: не змінює ToolGate, Constitution чи GoalContract; не підміняє user-goal verifier Issue #131; не вмикається автоматично і не видає similarity за незалежний доказ мети.

### Точне приймання на телефоні

1. **До нового APK:** перевірити StateVault, сімейство/сертифікат власницького підписування, поточні `records=512`, `negative_dead_ends=21`, `kernel_contracts=4`; не робити wipe. Новий `versionCode=59`.
2. **CI:** exact-head Gradle Unit Tests + критичні XML suites + build release APK і native smoke. SDK 0.18.0 може збільшити APK; не пропонувати файл, доки artifacts не успішні.
3. **Завантаження власником:** саме модель `embeddinggemma-2-text-270m-litert-lm` у форматі `.litertlm`, з перевіреним upstream manifest/license. Не завантажувати із застосунку автоматично.
4. **В APK:** активувати `semantic-memory` вручну, переключити LiteRT CPU, імпортувати `.litertlm` через Android picker. Metadata IMPORT_OK означає лише валідний контейнер.
5. Натиснути `CPU inference smoke — REAL`; лише `READY`, ненульовий числовий embedding правильної розмірності та реальна latency дозволяють вручну індексувати. Якщо `NOT_READY`, не переходити до індексу.
6. Виміряти 5–10 повторів, пікову пам'ять і температуру, відновлення після STOP, StateVault backup/restore, незмішаність Ollama/LiteRT кешів, збереження негативного досвіду, battery.
7. Провести blinded A/B проти лексичного пошуку на розмічених українських/польських/англійських питаннях (Recall@5, nDCG@10), без автоматичних висновків про поліпшення.

**Не виконано у Draft:** real Android inference, перевірка фактичного конкретного файлу HF у нашій APK, retrieval gain, fine-tuning LoRA, GPU/NPU, мультимодальне перенесення. G0 прогін CI може залишатися в процесі після першого commit.