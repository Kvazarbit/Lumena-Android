package com.lumena.android.settings

import android.content.Context
import android.util.AtomicFile
import com.lumena.android.agent.core.ToolRegistry
import com.lumena.android.agent.local.CauseProbeExecutionIntent
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

data class CauseFailureAnchor(
    val id: String,
    val taskHash: String,
    val evidenceId: String,
    val tool: String,
    val target: String = "",
    val failureClass: String? = null,
    val errorCode: String? = null,
    val dependency: String? = null,
    val retryable: Boolean? = null,
    val at: Long
)

data class StoredCauseHypothesis(
    val hypothesis: CauseHypothesis,
    val taskHash: String,
    val failureAnchorId: String,
    val at: Long
)

data class StoredCauseProbe(
    val probe: CauseProbeEvidence,
    val taskHash: String,
    val at: Long
)

data class VerifiedCauseLadderState(
    val version: Int = 1,
    val failures: List<CauseFailureAnchor> = emptyList(),
    val hypotheses: List<StoredCauseHypothesis> = emptyList(),
    val probes: List<StoredCauseProbe> = emptyList()
)

data class VerifiedCauseLadderRuntimeStats(
    val failures: Int = 0,
    val hypotheses: Int = 0,
    val probes: Int = 0,
    val hypothesisStage: Int = 0,
    val probedStage: Int = 0,
    val verifiedStage: Int = 0,
    val contestedStage: Int = 0,
    val rejectedStage: Int = 0
)

/**
 * Bounded, app-private Phase-3 runtime store.
 *
 * It stores only:
 * - hashes of model-proposed causal claims;
 * - structured tool metadata;
 * - registered tool/target evidence references and verdict mappings.
 *
 * Raw model hypothesis prose and raw tool stdout/stderr are never persisted.
 * This store has no ToolGate, approval, execution, Constitution, or Laya API.
 */
object VerifiedCauseLadderStore {
    const val FILE_NAME =
        "lumena_verified_cause_ladder_v1.json"

    const val MAX_FAILURES = 128
    const val MAX_HYPOTHESES = 128
    const val MAX_PROBES = 256

    private val lock = StateVaultLock.monitor

    private val adapter =
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(VerifiedCauseLadderState::class.java)

    fun load(
        context: Context
    ): VerifiedCauseLadderState =
        synchronized(lock) {
            val file = atomicFile(context)
            if (
                !file.baseFile.exists() &&
                !File(file.baseFile.path + ".bak").exists()
            ) {
                return@synchronized VerifiedCauseLadderState()
            }

            val json = try {
                file.openRead()
                    .bufferedReader()
                    .use { it.readText() }
            } catch (failure: Exception) {
                throw IllegalStateException(
                    "Verified cause ladder is unreadable; refusing silent reset.",
                    failure
                )
            }

            val parsed = try {
                requireNotNull(
                    adapter.fromJson(json)
                ) {
                    "Verified cause ladder is empty."
                }
            } catch (failure: Exception) {
                throw IllegalStateException(
                    "Verified cause ladder is corrupt; refusing silent reset.",
                    failure
                )
            }

            validate(parsed)
            parsed
        }

    fun observeToolResult(
        context: Context,
        taskId: String,
        modelId: String,
        request: ToolRequest,
        result: ToolResult,
        intent: CauseProbeExecutionIntent?,
        at: Long = System.currentTimeMillis()
    ): VerifiedCauseLadderState =
        synchronized(lock) {
            require(at > 0L)

            val current = load(context)
            val taskHash =
                FractalExperienceCanvasPolicy
                    .hash(taskId)
                    .take(24)
            val evidenceId =
                evidenceId(
                    taskHash = taskHash,
                    request = request,
                    at = at
                )

            var failures =
                current.failures
            var hypotheses =
                current.hypotheses
            var probes =
                current.probes

            val priorFailure =
                failures
                    .asReversed()
                    .firstOrNull {
                        it.taskHash == taskHash
                    }

            if (
                intent != null &&
                priorFailure != null
            ) {
                val hypothesis =
                    VerifiedCauseLadderPolicy
                        .proposeHashed(
                            anchorId =
                                priorFailure.id,
                            claimHash =
                                intent.hypothesisHash,
                            modelId = modelId,
                            structuredFailureClass =
                                priorFailure.failureClass,
                            structuredErrorCode =
                                priorFailure.errorCode,
                            structuredDependency =
                                priorFailure.dependency
                        )

                if (hypothesis != null) {
                    val stored =
                        StoredCauseHypothesis(
                            hypothesis = hypothesis,
                            taskHash = taskHash,
                            failureAnchorId =
                                priorFailure.id,
                            at = at
                        )
                    hypotheses =
                        (hypotheses + stored)
                            .distinctBy {
                                it.hypothesis.id
                            }
                            .sortedBy { it.at }
                            .takeLast(
                                MAX_HYPOTHESES
                            )

                    val onSuccess =
                        parseVerdict(
                            intent.onSuccess
                        )
                    val onFailure =
                        parseVerdict(
                            intent.onFailure
                        )

                    if (
                        onSuccess != null &&
                        onFailure != null
                    ) {
                        val verdict =
                            if (result.outcomeUnknown) {
                                CauseProbeVerdict
                                    .INCONCLUSIVE
                            } else if (result.ok) {
                                onSuccess
                            } else {
                                onFailure
                            }

                        val probe =
                            StoredCauseProbe(
                                probe =
                                    CauseProbeEvidence(
                                        hypothesisId =
                                            hypothesis.id,
                                        evidenceId =
                                            evidenceId,
                                        tool =
                                            ToolRegistry
                                                .canonicalize(
                                                    request.tool
                                                ),
                                        target =
                                            targetOf(
                                                request
                                            ),
                                        verdict =
                                            verdict,
                                        falsifiable =
                                            setOf(onSuccess, onFailure) ==
                                                setOf(
                                                    CauseProbeVerdict.SUPPORTS,
                                                    CauseProbeVerdict.REJECTS
                                                )
                                    ),
                                taskHash =
                                    taskHash,
                                at = at
                            )

                        probes =
                            (probes + probe)
                                .distinctBy {
                                    it.probe
                                        .hypothesisId +
                                        "|" +
                                        it.probe
                                            .evidenceId
                                }
                                .sortedBy { it.at }
                                .takeLast(
                                    MAX_PROBES
                                )
                    }
                }
            }

            if (
                !result.ok &&
                !result.outcomeUnknown
            ) {
                val canonical =
                    ToolRegistry
                        .canonicalize(
                            request.tool
                        )
                if (
                    ToolRegistry.get(canonical) !=
                    null
                ) {
                    val anchor =
                        CauseFailureAnchor(
                            id =
                                "cause-fail-" +
                                    FractalExperienceCanvasPolicy
                                        .hash(
                                            taskHash +
                                                "|" +
                                                evidenceId +
                                                "|" +
                                                canonical
                                        )
                                        .take(20),
                            taskHash =
                                taskHash,
                            evidenceId =
                                evidenceId,
                            tool = canonical,
                            target =
                                targetOf(
                                    request
                                ),
                            failureClass =
                                sanitize(
                                    result.failureClass,
                                    120
                                ),
                            errorCode =
                                sanitize(
                                    result.errorCode,
                                    120
                                ),
                            dependency =
                                sanitize(
                                    result.dependency,
                                    160
                                ),
                            retryable =
                                result.retryable,
                            at = at
                        )

                    failures =
                        (failures + anchor)
                            .distinctBy { it.id }
                            .sortedBy { it.at }
                            .takeLast(
                                MAX_FAILURES
                            )
                }
            }

            val retainedHypothesisIds =
                hypotheses
                    .map {
                        it.hypothesis.id
                    }
                    .toSet()
            probes =
                probes
                    .filter {
                        it.probe.hypothesisId in
                            retainedHypothesisIds
                    }
                    .takeLast(MAX_PROBES)

            val next =
                VerifiedCauseLadderState(
                    version = 1,
                    failures = failures,
                    hypotheses = hypotheses,
                    probes = probes
                )

            validate(next)
            if (next != current) {
                save(
                    context,
                    next
                )
                StateVault.requestSave(
                    context
                )
            }
            next
        }

    fun stats(
        context: Context
    ): VerifiedCauseLadderRuntimeStats =
        synchronized(lock) {
            val state =
                load(context)
            val assessments =
                state.hypotheses.map {
                    VerifiedCauseLadderPolicy
                        .assess(
                            hypothesis =
                                it.hypothesis,
                            probes =
                                state.probes
                                    .asSequence()
                                    .map {
                                        stored ->
                                        stored.probe
                                    }
                                    .filter {
                                        probe ->
                                        probe.hypothesisId ==
                                            it.hypothesis.id
                                    }
                                    .toList()
                        )
                }

            VerifiedCauseLadderRuntimeStats(
                failures =
                    state.failures.size,
                hypotheses =
                    state.hypotheses.size,
                probes =
                    state.probes.size,
                hypothesisStage =
                    assessments.count {
                        it.stage ==
                            CauseLadderStage
                                .HYPOTHESIS
                    },
                probedStage =
                    assessments.count {
                        it.stage ==
                            CauseLadderStage
                                .PROBED
                    },
                verifiedStage =
                    assessments.count {
                        it.stage ==
                            CauseLadderStage
                                .VERIFIED
                    },
                contestedStage =
                    assessments.count {
                        it.stage ==
                            CauseLadderStage
                                .CONTESTED
                    },
                rejectedStage =
                    assessments.count {
                        it.stage ==
                            CauseLadderStage
                                .REJECTED
                    }
            )
        }

    fun clear(
        context: Context
    ) = synchronized(lock) {
        val file =
            atomicFile(context)
                .baseFile
        if (file.exists()) {
            file.delete()
        }
        File(file.path + ".bak")
            .takeIf(File::exists)
            ?.delete()
    }

    private fun evidenceId(
        taskHash: String,
        request: ToolRequest,
        at: Long
    ): String =
        request.requestId
            ?.takeIf(String::isNotBlank)
            ?.take(160)
            ?: (
                "cause-ev-" +
                    FractalExperienceCanvasPolicy
                        .hash(
                            taskHash +
                                "|" +
                                request.tool +
                                "|" +
                                at
                        )
                        .take(24)
                )

    private fun targetOf(
        request: ToolRequest
    ): String =
        listOf(
            "path",
            "script",
            "cwd",
            "url",
            "query",
            "model"
        )
            .firstNotNullOfOrNull {
                request.args[it]
            }
            .orEmpty()
            .replace(
                Regex("[\\r\\n\\t]+"),
                " "
            )
            .trim()
            .take(240)

    private fun parseVerdict(
        raw: String?
    ): CauseProbeVerdict? =
        raw
            ?.trim()
            ?.uppercase()
            ?.let {
                runCatching {
                    CauseProbeVerdict
                        .valueOf(it)
                }.getOrNull()
            }

    private fun sanitize(
        raw: String?,
        maxChars: Int
    ): String? =
        raw
            ?.replace(
                Regex("[\\r\\n\\t]+"),
                " "
            )
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.take(maxChars)

    private fun validate(
        state: VerifiedCauseLadderState
    ) {
        require(state.version == 1)
        require(
            state.failures.size <=
                MAX_FAILURES
        )
        require(
            state.hypotheses.size <=
                MAX_HYPOTHESES
        )
        require(
            state.probes.size <=
                MAX_PROBES
        )

        state.failures.forEach {
            require(
                it.id.length in 1..160
            )
            require(
                it.taskHash.matches(
                    Regex(
                        "[0-9a-f]{24}"
                    )
                )
            )
            require(
                it.evidenceId.length
                    in 1..160
            )
            require(
                ToolRegistry.get(
                    it.tool
                ) != null
            )
            require(
                it.target.length <= 240
            )
            require(
                it.failureClass
                    ?.length
                    ?.let { length ->
                        length <= 120
                    }
                    ?: true
            )
            require(
                it.errorCode
                    ?.length
                    ?.let { length ->
                        length <= 120
                    }
                    ?: true
            )
            require(
                it.dependency
                    ?.length
                    ?.let { length ->
                        length <= 160
                    }
                    ?: true
            )
            require(it.at > 0L)
        }

        state.hypotheses.forEach {
            require(
                it.hypothesis.claimHash
                    .matches(
                        Regex(
                            "[0-9a-f]{24}"
                        )
                    )
            )
            require(
                it.hypothesis
                    .proposedByModelId
                    .length in 1..160
            )
            require(
                it.failureAnchorId
                    .length in 1..160
            )
            require(it.at > 0L)
        }

        val hypothesisIds =
            state.hypotheses
                .map {
                    it.hypothesis.id
                }
                .toSet()

        state.probes.forEach {
            require(
                it.probe.hypothesisId
                    in hypothesisIds
            )
            require(
                it.probe.evidenceId
                    .length in 1..160
            )
            require(
                ToolRegistry.get(
                    it.probe.tool
                ) != null
            )
            require(
                it.probe.target.length
                    <= 240
            )
            require(it.at > 0L)
        }
    }

    private fun save(
        context: Context,
        state: VerifiedCauseLadderState
    ) {
        val file =
            atomicFile(context)
        val output =
            file.startWrite()
        try {
            output.write(
                adapter.toJson(state)
                    .toByteArray(
                        Charsets.UTF_8
                    )
            )
            file.finishWrite(
                output
            )
        } catch (failure: Exception) {
            file.failWrite(
                output
            )
            throw failure
        }
    }

    private fun atomicFile(
        context: Context
    ): AtomicFile =
        AtomicFile(
            File(
                context.applicationContext
                    .filesDir,
                FILE_NAME
            )
        )
}
