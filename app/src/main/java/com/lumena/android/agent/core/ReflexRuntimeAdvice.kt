package com.lumena.android.agent.core

/**
 * Bounded advisory output from a reflex scorer.
 *
 * This DTO intentionally contains no tool call, arguments, permission, approval,
 * or executable text. WorkflowRunner revalidates option membership against the
 * constitutional candidate set before exposing it to the model.
 */
data class ReflexRuntimeAdvice(
    val option: ReflexOption,
    val confidence: Double,
    val evidenceCount: Int,
    val calibrated: Boolean = false,
    val calibratedConfidence: Double? = null,
    val calibrationSamples: Int = 0
) {
    init {
        require(confidence in 0.0..1.0)
        require(evidenceCount >= 0)
        calibratedConfidence?.let {
            require(it in 0.0..1.0)
        }
        require(calibrationSamples >= 0)
        if (calibrated) {
            require(calibratedConfidence != null)
            require(calibrationSamples > 0)
        }
    }
}
