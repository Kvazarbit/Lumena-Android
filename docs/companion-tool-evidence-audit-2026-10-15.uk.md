# Lumena — аудит взаємодії Companion, Bridge та доказів (2026-10-15)

Статус: зміни лише в окремій гілці GitHub; Phone E2E та CI ще не підтверджені. Дані телефону не змінено.

## Факти з реальних LUMENA_RESULT

- art-photo-01: image.search=false, немає придатних зображень у Wikimedia/Openverse; це не доказ відсутності Android-дозволів.
- art-photo-05, 09, 13: web.search=true, але Bing/DuckDuckGo дали сторонні результати, включно зі сторінками Poczta Polska замість фотосесії.
- art-photo-10: web.read=HTTP 404; art-photo-17: human verification, не обходити.
- art-photo-11: внутрішня сторінка пошуку читається. art-photo-12: http.get=200, HTML_BYTES=52773.
- art-photo-14: file.write=true. art-photo-15: python.run=false, ValueError int('30.0'); скрипт не запустився.
- art-photo-16: python.run=true, скрипт вилучив 3 URL; це підтверджує корисність адаптивної зміни стратегії.
- Повний зміст зображень та відповідність кількох сторінок одній сесії не доведені.

Примітка: MAX_OUTPUT Bridge = 128 КіБ, тому не доведено, що саме Bridge обрізав 52-КіБ HTML. Обмеження показу могло виникнути пізніше.

## Причини та зміни

1. CompanionProtocol перетворював числові JSON-значення в текст типу 30.0. Канонізація цілочисельних числових значень перед передаванням Bridge.
2. Bridge без перевірки викликав int(args.timeout), що падало на рядку 30.0. Сувора валідація цілих тайм-аутів; 30.0 приймається, 30.5/NaN/0/надмірні значення відхиляються до запуску. Спільний розбір цілих чисел також виправляє параметри limit, depth, max_chars та file.read start_line/end_line для старого Companion.
3. HTML важко читати в чаті. Додано необов'язковий режим http.get mode=links, path_contains та limit — список перевірених за синтаксисом HTTPS-посилань без великого HTML. Жодних автоматичних переходів, обходів human verification чи виконання HTML.
4. Зовнішній пошуковик може ігнорувати site: і фрази у лапках. Додано консервативне відхилення явно нерелевантних результатів; воно не доводить змістовної відповідності. Відсутність збігів має статус SEARCH_NO_MATCH / NO_RELEVANT_RESULTS, а недоступність провайдерів — SEARCH_EXHAUSTED / DEPENDENCY_EXHAUSTED. Ці ситуації не можна плутати.
5. Bridge health оголошує конкретні features. Companion застосовує новий режим лише після перевірки їх на телефоні. Не припускати, що старий Bridge підтримує новий аргумент.
6. Перейменовано відображувані локальні підказки пам'яті: TOOL_OK verified; goal_not_verified, TOOL_FAILED unresolved/recovered_by_tool_ok. Збережені історичні valence та evidence ID не змінено. TOOL_SUCCESS не дорівнює GOAL_SUCCESS; лише локальний TOOL_RESULT доводить дію.

## Регресії

- termux/test_companion_transport.py: python.run і python.tests timeout, некоректні значення, HTML links, default body, human verification.
- termux/test_search_constraint_relevance.py: точні лапки, site:, сторонні результати, позитивний пошук, збереження загальної поведінки.
- CompanionNumericArgsTest.kt: integer/fraction/string та fingerprint.
- GitHub CI: unittest, Kotlin JVM unit, native-smoke, mutation; лише фактичні результати CI можуть бути доказом.

## Невирішені задачі

- Загальний семантичний GoalVerifier: підтвердити, що зміст сторінки відповідає конкретній меті; типізований GoalContract поки гарантує лише обмежені операційні критерії.
- Довести Phone E2E з реальною Gemma, Coordinator, ContextKernel, ToolGate і kernel_observed/tool_result_count після контрольованого оновлення.
- Зберегти позитивні результати лише з локальним доказом. UNKNOWN не є training label.
- A/B експеримент пам'яті ON/OFF: перевірене досягнення мети, частота хибних DONE, кількість викликів, витрати часу.
- Не змінювати встановлену APK/Bridge чи ключі без окремого підтвердження власника. Не робити merge.
