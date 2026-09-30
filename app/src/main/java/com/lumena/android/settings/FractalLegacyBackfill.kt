package com.lumena.android.settings

data class FractalLegacyBackfillResult(
    val state: FractalExperienceCanvasState,
    val sourceExamples: Int,
    val importedRecords: Int,
    val alreadyApplied: Boolean
)

/**
 * One-way, deterministic migration from already verified coordinator memory
 * into the Fractal Experience Canvas.
 *
 * The migration creates advisory SHADOW records only. It does not mutate the
 * source coordinator state and does not expose any permission/authority API.
 */
object FractalLegacyBackfillPolicy {
    fun migrate(
        canvas: FractalExperienceCanvasState,
        coordinator: CoordinatorEpisodeState
    ): FractalLegacyBackfillResult {
        if (canvas.legacyBackfillVersion >= 1) {
            return FractalLegacyBackfillResult(
                state = canvas,
                sourceExamples = 0,
                importedRecords = 0,
                alreadyApplied = true
            )
        }

        val examples = CoordinatorExperiencePolicy.allVerifiedExamples(
            state = coordinator,
            limit = CoordinatorExperiencePolicy.MAX_LEARNED_EXAMPLES
        )
        val beforeIds = canvas.records.map { it.id }.toSet()
        val migrated = FractalExperienceCanvasPolicy.backfillLegacy(
            state = canvas,
            examples = examples
        )
        val imported = migrated.records.count {
            it.origin == FractalExperienceOrigin.LEGACY_BACKFILL &&
                it.id !in beforeIds
        }

        return FractalLegacyBackfillResult(
            state = migrated,
            sourceExamples = examples.size,
            importedRecords = imported,
            alreadyApplied = false
        )
    }
}
