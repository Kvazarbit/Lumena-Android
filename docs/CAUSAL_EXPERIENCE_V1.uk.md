# Causal Experience v1 — причинне навчання поверх Fractal Canvas

## Мета

Перетворити перевірену пам'ять Lumena з набору BEST/WORST прикладів на причинні зв'язки виду:

BAD_PATH -> WHY_FAILED -> RECOVERY -> RESULT

v1 навмисно не вигадує семантичну причину помилки. CoordinatorExperienceStore зберігає перевірені tool outcome, порядок кроків і, коли Bridge/ToolResult реально їх повернув, структуровані failure metadata: `failureClass`, `errorCode`, `retryable`, `dependency`. У такому випадку WHY_FAILED має тип `STRUCTURED_TOOL_FAILURE` і переносить тільки ці перевірені поля. Якщо структурованих metadata немає, WHY_FAILED залишається `UNKNOWN_NOT_CAPTURED`.

Правдоподібне постфактум пояснення моделі саме по собі не підвищує cause status і не стає verified evidence.

## Джерело істини

Causal links детерміновано проєктуються тільки з FractalExampleRecord, які вже пройшли verified-evidence gates.

- RECOVERY -> recovered causal chain.
- FAILED_RECOVERY -> unresolved causal chain.
- VERIFIED_SEQUENCE не перетворюється на причинну recovery-історію.
- evidence-free record не входить у causal memory.
- legacy backfill залишається LEGACY_BACKFILL.
- current runtime evidence залишається LIVE.

## Revalidation

Causal pattern вважається revalidated тільки якщо однакова структурна recovery-схема:

1. є щонайменше у двох різних source tasks;
2. має як LEGACY_BACKFILL, так і LIVE evidence;
3. усі links цієї структурної групи завершилися RECOVERED.

Якщо у групі є FAILED_AGAIN, вона не рахується revalidated.

## Prompt retrieval

FractalExperienceCanvasStore.relevant резервує максимум два слоти для causal memory, а решту залишає існуючим PATTERN/STRATEGY/META_RULE вузлам.

Приклад advisory packet:

CAUSAL VERIFIED RECOVERY · BAD_PATH=python.run[failed] · WHY_FAILED=STRUCTURED_TOOL_FAILURE class=INVALID_INPUT code=PYTHON_SCRIPT_REQUIRED retryable=true dependency=tool-schema · RECOVERY=file.read[ok] -> python.run[ok] · RESULT=RECOVERED · origin=LIVE · advisory only; revalidate current state

## Authority invariants

Causal Experience:

- не виконує інструменти;
- не надає permission/approval;
- не обходить ToolRegistry або ToolGate;
- не активує Constitution;
- не змінює Laya execution authority;
- не перетворює model prose на verified evidence;
- не називає невідому причину відомою.

## Diagnostic telemetry

У [FRACTAL_EXPERIENCE_CANVAS] додаються:

- causal_links
- causal_recovered
- causal_unresolved
- causal_live_links
- causal_revalidated_patterns

Це дозволяє бачити не тільки обсяг пам'яті, а й те, чи старі recovery-патерни реально отримують незалежне LIVE підтвердження.


## Наступний шар

Детальна послідовність розвитку від causal recovery до `STATE -> EXPECTED -> ACTION -> ACTUAL -> DELTA -> CAUSE -> RECOVERY -> VERIFIED -> UTILITY`, а також portable social experience і глобальна модель когнітивного екзоскелета описані в [cognitive-exoskeleton-roadmap.uk.md](cognitive-exoskeleton-roadmap.uk.md).
