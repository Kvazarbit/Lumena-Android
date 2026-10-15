# Lumena Phone E2E — аудит надійності інструментів (2026-10-15)

## Джерело фактів
Цей документ ґрунтується на **переданих власником** локальних `LUMENA_RESULT` із сесії `project-7f3a`.
Виконання на телефоні підтверджує тільки відповідний `LUMENA_RESULT`; зелений CI не підтверджує встановлення на телефоні.
Встановлення APK `0.12.30-ci2188` власник підтвердив раніше; свіжого `LUMENA_DIAGNOSTIC_V1` у цьому аудиті немає.

## Діагностика відтворених подій

| Task | Інструмент | Технічний результат | Goal/обмеження |
| --- | --- | --- | --- |
| lumena-art-photo-01 | image.search | FAILED, no displayable images | Фотосесію не показано; це не збій дозволів Android |
| lumena-art-photo-05 | web.search | SUCCESS, нерелевантні результати про Amanda як ім'я | Якість пошуку не підтверджена |
| lumena-art-photo-09 | web.search | SUCCESS, п'ять результатів Poczta Polska | Неправдива релевантність, не завершувати мету |
| lumena-art-photo-10 | web.read | FAILED HTTP 404 | Невідомо, чи змінено URL |
| lumena-art-photo-11 | web.read | SUCCESS | Сторінка внутрішнього пошуку прочитана; посилання не витягнуті |
| lumena-art-photo-12 | http.get | SUCCESS 200, HTML | Великий HTML не вдалося корисно прочитати в повідомленні |
| lumena-art-photo-13 | web.search | SUCCESS, п'ять галерей не за шуканою моделлю | Сторонні результати |
| lumena-art-photo-14 | file.write | SUCCESS | Скрипт створено, але не запущено |
| lumena-art-photo-15 | python.run | FAILED, `int('30.0')` | Помилка нормалізації числа між Companion і Bridge |
| lumena-art-photo-16 | python.run | SUCCESS, 3 href | Підтверджена робота парсера, але не спільність фотосерій |
| lumena-art-photo-17 | web.read | FAILED, human verification | Не обходити захист сайту |

## Причини, виправлені в окремій гілці

1. `CompanionProtocol` — Moshi Any десеріалізує цілі числа як Double, `toString` переносить `30` у `30.0`. Новий шлях канонізує тільки точно цілі числа.
2. `bridge.py` — `int('30.0')` зупиняв `python.run` до виконання коду. Новий `_execution_timeout` приймає математично цілі скінченні секунди, відхиляє дроби, NaN, від'ємні та понад ліміт.
3. `web.search` — технічний `ok` раніше не відрізняв випадок, коли всі результати не відповідають обов'язковій назві. Опційний `required_text` додає **лексичний** фільтр зі зміною провайдера та помилкою `SEARCH_NO_LEXICAL_MATCH`, якщо доказів немає. Це **не** семантична перевірка мети. Звичайні запити без цього параметра залишаються незмінними.
4. `web.read` — опційний `include_links=true` повертає адреси `href`, лише same-origin HTTPS, обмежено `links_limit=1..50`. `links_path_contains` дозволяє відразу вибрати необхідний шлях без `http.get → file.write → python.run`. Сторінки з CAPTCHA як раніше відхиляються.

Ці можливості Bridge доступні після встановлення **Bridge 0.30**. Поточний телефон мав Bridge 0.29; жодного автоматичного оновлення тут не робили.

## Дефекти, які НЕ оголошуємо закритими

- `GoalContract` перевіряє типізовані операційні свідчення, а не повну смислову відповідність довільної відповіді. `TOOL_SUCCESS != GOAL_SUCCESS` залишається незалежним правилом.
- Джерело може містити фейкові/динамічно підвантажувані або неіндексовані дані. `web.read` не переглядає пікселі зображень.
- Текст пояснення Gemma щодо дозволів Android/Termux не є доказом відмови ToolGate.
- Тест із живою Gemma, `kernel_observed`, `tool_result_count`, GoalContract, layer governor і Fractal Canvas — потрібен після оновлення APK/Bridge; локальний CI не підміняє Phone E2E.
- Навіть два реальні URL однієї моделі не доводять, що вони належать до однієї фотосесії.

## План перевірки перед встановленням

- Kotlin unit: `CompanionProtocolTest.numericToolArgumentsPreserveExactIntegralValues`.
- Python unit: `test_tool_argument_transport.py` і `test_web_tools.py` (тести timeout, кодування, відповідності, same-origin href, CAPTCHA).
- CI: native smoke, Kotlin unit, bridge unit, mutation та Android unsigned build; занотувати фактичний результат запуску.
- Без merge, підпису APK, змін приватних ключів чи автоматичного копіювання на телефон.

## План Phone E2E після схвалення змін

1. `LUMENA_DIAGNOSTIC_V1`: звірити APK і Bridge version; не припускати, що новий Bridge встановлений.
2. Через Companion перевірити `python.run timeout=30` і `timeout=30.0` (ціле число), із дозволом власника.
3. `web.search(query=...,required_text=...)` — перевірити відбір і безпечну негативну відповідь.
4. `web.read(url=...,include_links=true,links_path_contains=...)` — порівняти витягнуті href з HTML.
5. `web.read` на сайті з перевіркою людини — лише очікувана відмова, без обходу.
6. Перевірити `ContextKernel`, `GoalContract`, `tool_result_count`, `kernel_observed` та заборону `DONE` без доказів.
7. Для A/B порівняти кількість інструментальних кроків, релевантність джерел, відсоток помилкових `DONE` та час до перевіреного результату; невідоме не записувати як правильне навчальне маркування.
