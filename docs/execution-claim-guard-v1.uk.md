# Lumena Execution Claim Guard v1 — реальне пояснення попередніх дій

## Зафіксований телефонний контрприклад

OnePlus LE2123 / Lumena 0.12.30-ci2152 / Bridge 0.29:

```text
expect_ok: true
confidence: 1.0
web.search(query="bitcoin price chart current")
```

Після запитання **«що це за результат?»** модель сказала, що вже використала пошуковий інструмент і отримала дані, але діагностика показала:

```text
status=DONE
step=0/4
kernel_observed=0
tool_result_count=0
goal_contract_criteria=0
```

Це не гарантує, що жоден пошук не відбувався будь-коли у попередніх сесіях, але **в активній локальній задачі немає запису про виконаний пошук**. `last_web_search_status=success` із Bridge.health — глобальна телеметрія, яку не можна прив'язувати до конкретної задачі без її власного receipt.

## Причина

`TaskExecutionRecap.contradictions` ловив деякі **заперечення** записаних викликів, але не помилкові **позитивні заяви** моделі («я виконала пошук») за відсутності локального доказу. Фраза «що це за результат?» не вважалася `ShortTurnCue.isExplain`, отже не прив'язувалася до попередньої задачі.

## Реалізація v1

1. `ShortTurnCue.isExecutionEvidenceQuestion` вузько розпізнає питання про попередній результат / пошук українською, англійською і польською. Це не універсальний семантичний парсер.
2. `WorkflowChatScreen` прив'язує до такого нового питання `PreviousExecutionReceipt`, отриманий **лише** з app-owned `ContextKernel` попередньої задачі; не копіює дозволи, token, raw stdout або секрети.
3. `PreviousTaskOutcomeContext.snapshot` явно відділяє `verified_tool_result_count` від `last_result` (останній може містити не підтверджений текст моделі).
4. `AgentController.interpretDecision` для цієї вузької категорії повертає детерміноване пояснення від `ExecutionClaimGuard`; текст моделі, її `confidence`, `expect_ok`, або навіть новий запропонований `web.search` не можуть підробити історичний факт.
5. Попередній реальний receipt дозволяє сказати «інструмент викликано, є результат», але **не** «повну мету досягнуто».
6. Для `inFlight` / unknown — результат не перевірений, повторювати мутацію автоматично не можна. `DONE` у новій *пояснювальній* задачі означає завершену відповідь, не успішний попередній пошук.

## Тести та обмеження

`ExecutionClaimGuardTest`: повторення інциденту Bitcoin; plain reply; фальшивий `done`; новий model tool proposal; real success; failure; pending mutation; звичайна розмова; старе завдання без журналу.

Цей механізм **не** доводить якість інтернет-пошуку, не додає універсального semantic claim verifier, не перевіряє достовірність пікселів, не переносить user approvals і не встановлюється на телефон автоматично.

Після Kotlin unit tests, mutation probes та exact-head CI потрібні owner-signing, backup/rollback і телефонний E2E з тим самим сценарієм — лише за прямим дозволом власника. Не merge без дозволу.
