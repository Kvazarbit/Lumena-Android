# Lumena Native LiteRT-LM / EmbeddingGemma2 Text 270M — owner acceptance

Джерела: [офіційний Google Kotlin EmbeddingEngine 0.18.0](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/EmbeddingEngine.kt), [ModelInfo.kt](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/ModelInfo.kt), [Google Gallery pinned SDK](https://github.com/google-ai-edge/gallery/blob/main/Android/src/gradle/libs.versions.toml), [HF Text270M LiteRT variant](https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm).

**Режим: SHADOW / ручний.** Вся зовнішня модель залишається не встановленою за замовчуванням. АПК містить лише SDK/адаптер, не ваги.

### Послідовність

1. Перевірити валідну копію StateVault і зберегти встановлену ci2270 до підписування нової ci.
2. Підписати unsigned GitHub artifact тільки власницьким зашифрованим signing backup і лише після SHA-256 provenance check, з `--allow-pr`; не змінювати підпис або пакет.
3. В новій APK **Модулі → semantic-memory**: явно дозволити, потім у панелі Semantic Memory вибрати **LiteRT CPU**.
4. Скачати `.litertlm` окремо із HF link у Downloads через власний браузер; зіставити точний HF release/checksum/ліцензію. Немає автоматичного pull у Lumena; дані Google Gallery приватні та не копіюються.
5. Натиснути `Імпортувати .litertlm` → вибрати через системний picker. `IMPORT_OK` підтверджує лише SHA256 + нативні метадані. Збережені модельні файли не входять у StateVault, оскільки великі.
6. Натиснути `CPU inference smoke — REAL`: очікувати **READY**, дійсну числову розмірність, digest конкретного файлу. Виміряти latency у користувача; якщо `RUNTIME_UNAVAILABLE` або `NOT_READY`, індексацію НЕ запускати.
7. Індексувати **до 48** принципів вручну; заміряти `SemanticMemoryStore.stats`, нові/старі source revisions. Перевірити кілька різних запитів: `невиконаний код з DONE`, `підміна мети`, `не повторюй тупик`; результати є advisory лише, без ToolGate повноважень.
8. Вимкнути модуль; перевірити, що Fractal Canvas і негативні записи лишилися, жодного Ollama vector cache не змішано. Вмикати лише вручну.

### Поріг зупинки

- Не завантажувати другий рушій за помилкою без запису відтворюваного diagnostic.
- Ніякого cloud fallback або автоматичної заміни іншою embedding-моделлю.
- Пошук із високою cosine схожістю **не** доводить, що Gemini/Gemma виконає завдання правильно.
- STOP скасовує наступні batch, але завершення native виклику може бути невідкладним.
- Найнижчий безпечний крок після помилки — повідомити тип помилки без повторного запуску.

**Вага без власника / performance uplift НЕ доведені.**
