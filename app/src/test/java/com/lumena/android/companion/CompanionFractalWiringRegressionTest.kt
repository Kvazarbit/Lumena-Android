package com.lumena.android.companion

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionFractalWiringRegressionTest {
    @Test
    fun verifiedCompanionEpisodeFansOutIntoFractalCanvas() {
        val source = File(
            "src/main/java/com/lumena/android/companion/CompanionScreen.kt"
        ).readText()

        assertTrue(
            source.contains(
                "CoordinatorExperienceStore.examplesForTask"
            )
        )
        assertTrue(
            source.contains(
                "FractalExperienceCanvasStore.ingest("
            )
        )
        assertTrue(
            source.indexOf("FractalExperienceCanvasStore.ingest(") >
                source.indexOf("CoordinatorExperienceStore.examplesForTask")
        )
        assertTrue(
            source.indexOf("ConstitutionGenomeStore.ingestVerifiedRecoveryExamples") >
                source.indexOf("FractalExperienceCanvasStore.ingest(")
        )
    }
}
