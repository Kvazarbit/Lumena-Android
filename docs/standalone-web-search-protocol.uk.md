# Lumena — standalone web.search model call normalization v1

## Фактичний інцидент

На OnePlus / Lumena ci2152 (Bridge v0.29) Gemini-подібний model output:

```text
expect_ok: true
confidence: 1.0
web.search(query="bitcoin price chart current")
```

було показано як звичайний текст, а не виконано через ToolGate. Важливо: `expect_ok` та `confidence` — це тільки модельний прогноз, **не доказ виконання або ціни BTC**. Окреме виправлення неправдивого пояснення попередніх пошуків знаходиться в Draft PR #119.

## Реалізація

- `ProtocolNormalizer` розпізнає лише **один точний standalone** `web.search(query="...")` з необов'язковим `limit=1..8` та максимум двома службовими рядками `expect_ok: boolean`, `confidence: 0..1`. Аргумент запиту має бути **валідним JSON string**; максимум 240 символів, без керівних символів, без довільного виконання коду.
- `AgentController` допускає нормалізацію тільки якщо застосунок відніс поточне завдання до вебпошуку або користувач прямо вимагав `web.search`. Вся подальша перевірка проходить через **ToolRegistry → EffectiveTaskPolicy → ToolGate**.
- `TaskIntentRouter` розпізнає актуальні числові ринкові запити загально, не тільки Bitcoin: current/live price, графік/курс/chart тощо. Звичайні навчальні пояснення про графіки залишаються GENERAL.
- Текстові `file.write(...)`, `python.run(...)`, приклади коду в прозі, сторонні поля, подвійні інструменти та некоректні значення прогнозів **не** конвертуються у виклики.
- Після нормалізації `kernel.observed=0` доти, доки інструмент реально не поверне `TOOL_RESULT`. `web.search` дає тільки посилання та snippets: для поточних фактів потрібен `web.read` й відповідні критерії `GoalContract`.

## Валідація

- `StandaloneWebSearchProtocolTest`: позитивний Gemma-snippet, router live market, негативні й зловмисні формати, explicit deny, prediction ≠ tool evidence.
- `WorkflowRunnerStandaloneWebSearchTest`: справжній Android-код Coordinator/ToolGate/WorkflowRunner із **синтетичною моделлю та fake ToolExecutor**. Немає реального звернення до інтернету або реальної котировки Bitcoin.
- Exact-HEAD Kotlin tests, mutation guards, Android build, native-smoke у GitHub Actions. До green CI зміну не вважати готовою.

## Межі

Це вузька сумісність з observed text style, а не дозвіл виконувати довільні Python-style функції з тексту. Не передає приватний контекст на GitHub. Не змінює Bridge, не додає фінансових операцій. Повний phone E2E потребує власного дозволу, backup/signing/rollback. **Не merge і не встановлювати без явного дозволу власника.**

## Додатковий телефонний контрприклад (2026-10-15)

Нова діагностика все ще встановленої \`ci2152\` показала **другу** форму того самого дефекту: на емоційну репліку користувача модель відповіла довгим вибаченням та закінчила його словами «Ось реальний запит прямо зараз», додавши \`expect_ok: true confidence: 1.0 web.search(query="current bitcoin price usd")\`. Реальний журнал поточної задачі:

\`\`\`text
status=DONE
step=0/4
kernel_observed=0
tool_result_count=0
last_tool=
goal_contract_criteria=0
\`\`\`

Це **не** standalone tool call, тому його не можна виконувати лише тому, що він схожий на код. Додатковий вузький захист у \`TaskExecutionRecap.unverifiedInlineSearchClaim\` та \`AgentController\` виявляє модельну заяву «ось реальний запит» + псевдокоманду \`web.search(query=...)\` **без успішного current-task receipt**:

- GENERAL (зокрема вибачення/розмова) → детермінований \`PARTIAL\` без запуску інструмента або удаваних свіжих даних;
- PUBLIC_WEB → одна спроба виправити відповідь до коректного JSON \`tool\` через штатний ToolGate; повторення того самого псевдовиклику → \`PARTIAL\`;
- успішне \`system.time\`, старий \`lastTool\`, \`Bridge.last_web_search_status\` чи лише \`confidence=1.0\` не є доказом пошуку;
- звичайний **приклад синтаксису** без твердження «я щойно запустила» залишається текстовим поясненням.

Новий offline regression: \`InlineSearchClaimSafetyTest\` та сценарій у \`WorkflowRunnerStandaloneWebSearchTest\` вичерпують саме цей новий виявлений клас, не претендуючи на універсальне розпізнавання всіх мовних тверджень про дії.

**Поточний ризик:** словесні твердження про виконання інших інструментів ще можуть потребувати окремої типізованої перевірки. Новий захист не замінює семантичних oracle та phone E2E.
