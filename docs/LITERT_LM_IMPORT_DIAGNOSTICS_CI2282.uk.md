# Реальний інцидент: LiteRT-LM імпорт EmbeddingGemma 2 у Lumena ci2282

## Доказ власника

Власник встановив Lumena ci2282, versionCode 60. Android OpenDocument picker обирає
справжній файл embeddinggemma-2-text-270m.litertlm, 157 MiB,
у Download/My_Python. Окремо існує 740M, 463 MiB. Для імпорту UI показує
NOT_READY: IllegalArgumentException; вибраної моделі немає (NOT_INSTALLED).
Це не доказ проблеми розміру: обидві моделі < 1,2 GB.
Справжній CPU inference не відбувся, тому READY і успіх мети не підтверджені.

## Що саме змінено

1. SemanticLiteRtModelStore.inspect перевіряє тип Embedding, наявність текстової модальності,
   числову розмірність і відсутність явної заборони CPU. Не відхиляє пакет через
   додаткові прапорці vision/audio/video: індексується тільки InputData.Text.
   SDK ModelInfo читає прапорці з metadata/section, а не сам по собі встановлює готовність.
   Джерело upstream: https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/schema/model_info/model_info.cc
2. Порожній список supportedBackends класифікується UNKNOWN, а не CPU=TRUE.
   Якщо CPU відсутній у непорожньому списку, застосовується відмова CPU_UNSUPPORTED.
3. LiteRtImportCode дає замкнені причини: BAD_URI, PICKER_FAILURE, NO_FILENAME, BAD_EXTENSION,
   STORAGE_FAILURE, SOURCE_READ_FAILURE, TOO_LARGE, TOO_SMALL, METADATA_UNAVAILABLE,
   NOT_EMBEDDING, NO_TEXT_ENCODER, INVALID_DIMENSION, CPU_UNSUPPORTED,
   CONFLICTING_DESTINATION, COMMIT_FAILED, NOT_INSTALLED, ENGINE_INIT_FAILED,
   INFERENCE_FAILED, VECTOR_DIM_MISMATCH.
   UI друкує лише код та заздалегідь визначену підказку, не raw native exception/шлях/текст.
4. Оригінальні файли в Download незмінні; модель спершу імпортується тимчасово в приватну
   папку, перевіряється, потім атомарно позначається як обрана. Попередня вибрана копія
   не перезаписується після помилки. Немає автозавантаження ваг чи cloud fallback.
5. Лише фактичний виклик LiteRT-LM 0.18.0 EmbeddingEngine з Backend.CPU і числовим
   ненульовим вектором правильної розмірності може дати READY.
   INDEX не запускається після відмови, жоден tool privilege не додається.

## Приймання

- Draft від HEAD ci2282, новий versionCode 61, owner-sign лише після exact-head CI SUCCESS
  (всі mutation tests, 8+ нових JVM тестів, native smoke, assembleRelease та unsigned artifact).
- Встановити без uninstall після збереження StateVault, незмінним власницьким сертифікатом.
- Обрати 270M через Android picker. Зафіксувати LITERT_... код або IMPORT_OK + digest.
- Якщо IMPORT_OK, натиснути CPU inference smoke — REAL та зафіксувати READY/ERROR.
- Якщо все READY, лише потім ручна індексація з перевіркою незмінності Fractal Evidence Ledger.
- Прогін на OnePlus і саме конкретні 270M ваги не підтверджені CI.
