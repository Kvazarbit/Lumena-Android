# Causal Experience v1 — причинне навчання поверх Fractal Canvas

## Мета

Перетворити перевірену пам'ять Lumena з набору BEST/WORST прикладів на причинні зв'язки виду:

BAD_PATH -> WHY_FAILED -> RECOVERY -> RESULT

v1 навмисно не вигадує семантичну причину помилки. Поточний CoordinatorExperienceStore зберігає перевірені tool outcome та порядок кроків, але не зберігає перевірений root-cause label. Тому WHY_FAILED у v1 має значення UNKNOWN_NOT_CAPTURED.

Це краще за постфактум пояснення моделі, яке могло б бути правдоподібним, але неперевіреним.

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

CAUSAL VERIFIED RECOVERY · BAD_PATH=python.run[failed] · WHY_FAILED=UNKNOWN_NOT_CAPTURED · RECOVERY=file.read[ok] -> python.run[ok] · RESULT=RECOVERED · origin=LIVE · advisory only; revalidate current state

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
