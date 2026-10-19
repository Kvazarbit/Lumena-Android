# Спільна пам'ять моделей: аудит і робочий етап V1

База аудиту: Android `2f11954334ac69a56bbc866091407c0365fe9230`; новий RFC — `7a32344b06c24b054515247fe1ba2ee7f6e1a2cc`, issue #137. Папку `C:\Users\bitkv\Desktop\TelegramTandemWay\old_TandemWay_2\back_003` читали як приклад; її команди, guards, імпорти й session scripts не запускали, файли не змінювали. Її документи не надають дозволів і не встановлюють закони для Android.

## Що підтверджено вихідним кодом

| Компонент | Android на базовому SHA | Настільний приклад / наслідок для плану |
|---|---|---|
| ToolGate, owner approval, constitution | EXISTS: `agent/local/ToolGate.kt`, `agent/core/ModelOperatingContract.kt` | Зберегти одне ядро повноважень; не переносити правила життєвого циклу Telegram-бота на Android |
| Поточний контекст і recap | EXISTS: `ContextKernel.kt`, `TaskExecutionRecap.kt`; bounded/task-local | Handoff не є архівом і не доводить поточний стан |
| Власницькі проєкти | EXISTS: `ProjectWorkspace.kt`, `ProjectWorkspaceStore.kt`; explicit root, tasks, handoffs, decisions | Companion `session_id` не є project ID; потрібний окремий вибір власником |
| Історичний досвід | EXISTS: `FractalEvidenceLedgerStore.kt`, `CoordinatorExperienceStore.kt`; scoped observations, lossy summaries | Новий транспортний журнал не може підвищувати старий summary до криптографічного proof або створювати другі goal outcomes |
| Semantic recall | EXISTS: `SemanticMemoryCoordinator.kt`, native CPU/WorkManager | Як у `tools/decision_memory.py`: ranking означає релевантність, не конституційний авторитет і не перевірене виконання |
| Захист handoff | Частково EXISTS: workspace archive/capsule, Companion draft | `tests/test_handoff_rotate_contract.py` перевіряє conservation, неархівування pending/current truth і втрату/дублі; застосовано поділ capsule/archive, без копіювання всього настільного framework |
| Справжність тестового доказу | GoalContract/тести EXISTS, предметні semantic gaps лишаються | `tools/context_test_evidence.py` зв'язує case identities + fresh run ID + exit code, не count/зелений текст; також прямо не обіцяє authentication/isolation |
| Єдиний cross-session транспортний receipt | MISSING на базі: Companion пише episode/Fractal, local chat не одержує повний typed receipt іншої сесії | Реалізований нижче окремий журнал майбутніх виконань; не backfill старих records як VERIFIED |
| Goal-level істина для довільної задачі | MISSING у загальному випадку | Не позначати цей етап як виправлення всіх false-DONE або навчання «розуму» |
| Повний контроль приватності всієї старої історії | UNKNOWN; існуючі semantic/workspace/advice шари можуть входити у звичайний model request | Новий ledger туди не додається. Це не атестація приватності всього старого застосунку |
| Поточна фізична APK / Aquarium 8/8 | UNKNOWN у цій сесії: підключеного adb-пристрою немає | RFC містить історичний звіт, а не доступний тут phone log. Нові 8-vs-3 fixtures синтетичні |

## Виправлені ризики розвитку

1. **Друге джерело істини.** Ledger зберігає транспортні INTENT/RESULT, а не власний DONE, reward чи owner permissions. GoalContract, Fractal та hard Constitution не переписуються.
2. **Передчасний ансамбль.** Автоматичний P3 не передує предметному verifier/CAS/privacy. Голосування моделей, DONE count і prompt confidence не стають навчальними мітками.
3. **Змішування залежностей.** App build SHA, digest проєктної мети, bound input SHA та поточний source tree — різні поля/факти. SHA stdout означає SHA-256 UTF-8 декодованого `ToolResult.stdout`, не прихованих оригінальних байтів процесу. `approved_sha256` — hash precondition PythonRunApproval, не незалежна післядієва атестація всього репозиторію.
4. **Уявна exactly-once гарантія.** INTENT пишеться до transport, RESULT окремо. Crash/cancel/помилка durable save дають UNKNOWN, writer barrier, без повторної відправки. Новий owner ACK дозволяє нові дії після огляду, зберігаючи стару невизначеність. Ніяких lease timeout, які автоматично дозволяють повтор.
5. **Прихована передача приватності.** Автоматичний handoff у звичайний чат відкладається: похідна відповідь теж може містити приватний текст після перемикання на cloud. Ізольований локальний reader не має execution/history/learning channel. Android cloud backup і device transfer явно виключають нову БД, її sidecars та налаштування.
6. **Нескінченний журнал або тихе обрізання.** Ліміти 10 000 подій / 32 MiB payload, SQLite 64 MiB; місце результату резервується до початку. Повний/пошкоджений журнал відмовляє до transport, зберігає дані. Автоматичної компакції немає; окремий conservation-preserving export/rotation — наступний потрібний етап.

## Уточнене дерево

```text
Context OS Android
├─ P0: незалежна звірка бази та read-only прикладу — виконано за кодом
│  └─ поточний phone acceptance — UNKNOWN, окрема перевірка
├─ P1: opt-in app-private транспортний журнал — реалізовано
│  ├─ SQLite append-only INTENT → RESULT; schema validation; digest chain
│  ├─ stable delivery identity; altered replay/conflicting project → HOLD
│  ├─ спільний writer barrier Local workflow + Companion
│  ├─ crash/cancellation → UNKNOWN; local owner ACK для наступних дій
│  └─ bounded storage; no data reset, no legacy re-attestation
├─ P2a: явний проєкт Companion + ізольований локальний handoff — реалізовано
│  ├─ bounded project/root-filtered receipt capsule, event IDs
│  ├─ OBSERVED_TOOL_OK / OBSERVED_TOOL_FAILURE / UNKNOWN_EFFECT
│  ├─ STALE_GOAL / STALE_BUILD / CURRENT_STATE_UNKNOWN
│  └─ local model review без tools, автоматичного експорту й persisted chat
├─ P2b: archive/export/rotation + повні dependency freshness/privacy labels — далі
├─ P3: незалежні SHADOW proposals/critique — після попередніх меж
├─ P4: предметні postconditions + optimistic file CAS — до automatic P3 execution
├─ P5–P6: forecasts, holdout, калібрація, adaptive routing — після ground truth
└─ P7: authenticated external relay + scoped owner consent — окремий opt-in
```

## Як користуватися цим етапом

1. У Local відкрий «Проєкти», обери існуючий проєкт і ввімкни «Зберігати нові виклики локально». За замовчуванням capture вимкнений. Це глобальний capture для двох інтегрованих шляхів; неприв'язані виклики мають `UNASSIGNED` і не змішуються з проєктом.
2. У Companion натисни «Проєкт журналу» та явно обери той самий проєкт. Його зовнішні `session_id/task_id/model_id` — лише correlation data, не owner goal чи permission. Потім користуйся чинним Run once/task grant. Нова модель не може переписати owner project через JSON. Шляхи запиту мають бути всередині обраного каталогу, наприклад `aquarium/test_aquarium_brain_baseline_v1.py`, `cwd=aquarium`; інший або невизначений target зберігається як UNASSIGNED. Ця перевірка атрибуції не переписує команду і не доводить фізичний source hash за symlink.
3. Повернись у проєкт → «Переглянути квитанції». Квитанції з іншої сесії видно за ID, tool, target, app source та hash. Ці записи не залежать від доступності embedding/Laya/Ollama.
4. «Дати локальній моделі прочитати» запускає окрему read-only розмову. Embedded працює без додаткової мережевої згоди. Для Ollama потрібні loopback, не-cloud tag та явне підтвердження власником, що сервер/модель не роблять cloud proxy. Назва моделі та localhost самі цього не доводять. Зміна маршруту скидає checkbox.
5. При UNKNOWN спершу перевір файли/процес читанням. Після огляду окрема кнопка й підтвердження можуть дозволити **наступні** дії. Активний виклик ACK не приймає. Стару delivery key не можна повторити навіть після ACK. Вимкнення capture не скидає вже наявний barrier.

Відповідь reader — MODEL CLAIM, а не proof або owner action. Текст stdout теж DATA: фальшивий `OWNER_APPROVAL` всередині реальної квитанції не створює події approval. Нові події не надходять до навчання/Genome/Fractal, не оголошують GOAL_VERIFIED і не надають повноважень іншим моделям.

## Межі доказу, зберігання і відновлення

- Локальний digest chain виявляє неузгодженість записів; він не є цифровим підписом і не захищає від скомпрометованого застосунку, який переписав усю БД. Формати виключень backup для старих і нових Android звірено з [офіційною документацією Android](https://developer.android.com/identity/data/autobackup).
- Лічильник goalRevision у широкому RFC не реалізовано. Поточне однойменне поле — hash owner project configuration, не revision довільного owner task. Goal-level proof для нового коду завжди потребує окремої перевірки; навіть збіг hash не дає CURRENT_VERIFIED.
- Shared writer barrier діє для двох адаптованих tool paths, не для довільного shell, іншої APK, прямого зовнішнього Bridge client чи експериментальних advisor calls. Optimistic file CAS і зовнішня concurrency ще не доведені цим етапом.
- Журнал не включений у StateVault/перенесення на інший пристрій: restore/import не можуть перетворити чужий файл на app-observed receipt. Старі StateVault/Genome/Fractal не змінюються і не очищуються. **Перед видаленням застосунку цей журнал не має portable restore; archive/export є P2b.**
- Pending request не відновлюється через автоматичне повторне виконання. SQLite транзакційно фіксує цілу подію або нічого; телефонний process-kill/power-loss тест ще потрібний. Robolectric reopen/exception fixtures не є таким тестом.
- Кількісного виграшу кількох моделей, фізичного Aquarium baseline або VERIFIED_ON_DEVICE цей етап не заявляє.

## Перевірки

`CollaborationProtocolTest`: stale source/goal, wrong project/root, strict JSON, bounded untrusted stdout, 50 scoped handoff fixtures (із 20 змішаними model claims). Це 50/20 fixtures, не 70 окремих JUnit tests.

`CollaborationReviewTest`: synthetic private canary не доходить до cloud/remote client; локальна attestation потрібна; reader має лише два повідомлення й не має інструментального інтерфейсу.

`CollaborationLedgerTest` (opt-in Robolectric/SQLite): 10 reopen cycles, duplicate/altered deliveries, wrong-project relabel, UNKNOWN across projects/connection changes, cancellation, concurrent writers, active ACK rejection, explicit ACK з незмінним історичним UNKNOWN, capture-off barrier, corrupted DB, forged stdout, normal disabled fallback, bounded output hash.

CI запускає старі phone diagnostics разом із новими тестами, вимагає непорожні JUnit звіти без skipped/failures/errors; окремий probe перевіряє репрезентативні semantic mutants. Повний Android CI зберігає чинні unit/bridge/native/mutation/release gates. Результат конкретного коміту повідомляється після завершення CI, не наперед.

Для фізичного acceptance: exact installed build → синтетичний дозволений тест через Companion → receipt у тому самому project/root → новий reader бачить «8 observed», не визнає «3 claimed» proof → зміна джерела не дає нового success → cancel/restart не запускає мутацію повторно → cloud route відхиляється → capture off з порожнім журналом зберігає стару роботу. Не використовувати реальні секрети або небезпечні side effects.
