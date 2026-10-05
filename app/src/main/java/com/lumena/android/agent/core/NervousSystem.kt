package com.lumena.android.agent.core

import java.security.MessageDigest

enum class NervousSubsystem {
    COMPANION,
    TOOLING,
    RUNTIME,
    RECOVERY,
    WEB,
    CODE,
    SNAKE
}

enum class NervousEventKind {
    SENSE,
    SELF_ACTION,
    EFFECT,
    REFLEX_BLOCK,
    INCIDENT
}

enum class SelfActionKind {
    CHATGPT_SCAN,
    CHATGPT_INSERT,
    CHATGPT_SEND,
    CHATGPT_ACTIVITY_OPEN,
    AUTO_RETURN_QUEUE,
    TOOL_EXECUTE,
    RECOVERY_ACTION
}

enum class CausalGrade {
    OBSERVED,
    CORRELATED,
    REPRODUCED,
    VERIFIED_EFFECT,
    TRANSFERRED,
    CONTESTED,
    SUPERSEDED
}

enum class NervousEvidenceSource {
    LOCAL_OBSERVATION,
    LOCAL_VERIFIER,
    LOCAL_TEST,
    IMPORTED
}

data class NervousFrame(
    val chatGptActive: Boolean? = null,
    val chatGptGenerating: Boolean? = null,
    val companionBusy: Boolean? = null,
    val uiUpdatedAt: Long? = null,
    val outcomeKnown: Boolean? = null
)

data class NervousEvent(
    val id: String,
    val at: Long,
    val subsystem: NervousSubsystem,
    val kind: NervousEventKind,
    val action: SelfActionKind? = null,
    val before: NervousFrame? = null,
    val after: NervousFrame? = null,
    val incidentCode: String? = null,
    val causalGrade: CausalGrade = CausalGrade.OBSERVED,
    val source: NervousEvidenceSource = NervousEvidenceSource.LOCAL_OBSERVATION,
    val locallyVerified: Boolean = false,
    val contextKey: String? = null
) {
    fun promotionEligible(): Boolean =
        locallyVerified &&
            source in setOf(
                NervousEvidenceSource.LOCAL_VERIFIER,
                NervousEvidenceSource.LOCAL_TEST
            ) &&
            causalGrade in setOf(
                CausalGrade.VERIFIED_EFFECT,
                CausalGrade.TRANSFERRED
            )
}

data class NervousSystemState(
    val version: Int = 1,
    val events: List<NervousEvent> = emptyList()
)

data class NervousIncidentAssessment(
    val event: NervousEvent,
    val reflexRequired: Boolean,
    val explanation: String
)

/**
 * Pure nervous-system policy.
 *
 * This layer observes and classifies. It grants no permissions, performs no
 * tools, and has no API that can activate Constitution rules.
 */
object NervousSystemPolicy {
    const val MAX_EVENTS = 1024

    fun uiMutationAllowed(
        action: SelfActionKind,
        before: NervousFrame
    ): Boolean {
        require(action in setOf(
            SelfActionKind.CHATGPT_INSERT,
            SelfActionKind.CHATGPT_SEND,
            SelfActionKind.CHATGPT_ACTIVITY_OPEN
        ))
        return before.chatGptGenerating != true
    }

    fun record(
        state: NervousSystemState,
        event: NervousEvent
    ): NervousSystemState {
        require(event.at > 0)
        require(event.id.isNotBlank())
        require(event.id.length <= 128)
        event.contextKey?.let { require(it.length <= 160) }
        event.incidentCode?.let { require(it.length <= 160) }

        if (state.events.any { it.id == event.id }) return state
        return state.copy(
            version = 1,
            events = (state.events + event).takeLast(MAX_EVENTS)
        )
    }

    fun classifyChatGptUiAction(
        action: SelfActionKind,
        before: NervousFrame,
        after: NervousFrame?,
        performed: Boolean,
        source: NervousEvidenceSource,
        now: Long,
        contextKey: String? = null
    ): NervousIncidentAssessment? {
        require(action in setOf(
            SelfActionKind.CHATGPT_INSERT,
            SelfActionKind.CHATGPT_SEND,
            SelfActionKind.CHATGPT_ACTIVITY_OPEN
        ))

        if (before.chatGptGenerating != true) return null

        if (!performed) {
            return NervousIncidentAssessment(
                event = event(
                    now = now,
                    subsystem = NervousSubsystem.COMPANION,
                    kind = NervousEventKind.REFLEX_BLOCK,
                    action = action,
                    before = before,
                    after = after,
                    code = "CHATGPT_GENERATING_UI_MUTATION_BLOCKED",
                    // The block itself is an observed self-action. The effect
                    // of an action that did not happen cannot be observed, so
                    // a reflex firing is never VERIFIED_EFFECT evidence.
                    grade = CausalGrade.OBSERVED,
                    source = source,
                    locallyVerified = source != NervousEvidenceSource.IMPORTED,
                    contextKey = contextKey
                ),
                reflexRequired = true,
                explanation = "Generation was directly observed; UI mutation was blocked."
            )
        }

        val generatingEnded = after?.chatGptGenerating == false
        return NervousIncidentAssessment(
            event = event(
                now = now,
                subsystem = NervousSubsystem.COMPANION,
                kind = NervousEventKind.INCIDENT,
                action = action,
                before = before,
                after = after,
                code = if (generatingEnded) {
                    "SELF_ACTION_CORRELATED_STREAM_ABORT"
                } else {
                    "CHATGPT_GENERATING_UI_MUTATION"
                },
                grade = if (source in setOf(
                        NervousEvidenceSource.LOCAL_VERIFIER,
                        NervousEvidenceSource.LOCAL_TEST
                    )
                ) {
                    CausalGrade.VERIFIED_EFFECT
                } else {
                    CausalGrade.CORRELATED
                },
                source = source,
                locallyVerified = source in setOf(
                    NervousEvidenceSource.LOCAL_VERIFIER,
                    NervousEvidenceSource.LOCAL_TEST
                ),
                contextKey = contextKey
            ),
            reflexRequired = true,
            explanation =
                "UI mutation occurred while ChatGPT generation was directly observed."
        )
    }

    fun event(
        now: Long,
        subsystem: NervousSubsystem,
        kind: NervousEventKind,
        action: SelfActionKind? = null,
        before: NervousFrame? = null,
        after: NervousFrame? = null,
        code: String? = null,
        grade: CausalGrade = CausalGrade.OBSERVED,
        source: NervousEvidenceSource = NervousEvidenceSource.LOCAL_OBSERVATION,
        locallyVerified: Boolean = false,
        contextKey: String? = null
    ): NervousEvent {
        val material = listOf(
            now.toString(),
            subsystem.name,
            kind.name,
            action?.name.orEmpty(),
            code.orEmpty(),
            grade.name,
            source.name,
            contextKey.orEmpty(),
            before.toString(),
            after.toString()
        ).joinToString("|")
        return NervousEvent(
            id = sha256(material).take(24),
            at = now,
            subsystem = subsystem,
            kind = kind,
            action = action,
            before = before,
            after = after,
            incidentCode = code,
            causalGrade = grade,
            source = source,
            locallyVerified = locallyVerified,
            contextKey = contextKey
        )
    }

    fun transferable(
        events: List<NervousEvent>,
        incidentCode: String,
        minVerifiedEvidence: Int = 3,
        minDistinctContexts: Int = 2
    ): Boolean {
        val verified = events.filter {
            it.incidentCode == incidentCode &&
                it.promotionEligible()
        }
        val contexts = verified.mapNotNull { it.contextKey }.toSet()
        return verified.size >= minVerifiedEvidence &&
            contexts.size >= minDistinctContexts
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
