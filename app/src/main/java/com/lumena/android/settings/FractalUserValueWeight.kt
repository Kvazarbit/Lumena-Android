package com.lumena.android.settings

import java.security.MessageDigest

enum class FractalUserValueSource {
    EXPLICIT_USER
}

enum class FractalUserValueStatus {
    ACTIVE,
    SUPERSEDED
}

/**
 * Normative user priority attached to one deterministic fractal node.
 *
 * This is deliberately NOT evidence confidence and NOT authority. The user can
 * say that one already-observed pattern matters more or less to them; that
 * preference may reorder equally relevant/equally supported advisory nodes,
 * but it cannot turn failure into success, change BEST/WORST/CONTESTED,
 * authorize a tool, satisfy GoalContract, or promote Constitution rules.
 *
 * recordedBy is provenance of the adapter/model/UI that transcribed the user's
 * explicit instruction. It never changes the weight.
 */
data class FractalUserValueWeight(
    val id: String,
    val nodeId: String,
    val weight: Int,
    val source: FractalUserValueSource =
        FractalUserValueSource.EXPLICIT_USER,
    val sourceTurnHash: String,
    val recordedBy: String,
    val at: Long,
    val status: FractalUserValueStatus =
        FractalUserValueStatus.ACTIVE,
    val supersededBy: String? = null
)

object FractalUserValueWeightPolicy {
    const val MIN_WEIGHT = -3
    const val MAX_WEIGHT = 3
    const val MAX_RECORDS = 256

    private val hex24 = Regex("[0-9a-f]{24}")

    fun recordExplicit(
        state: FractalExperienceCanvasState,
        nodeId: String,
        weight: Int,
        sourceUserTurn: String,
        recordedBy: String,
        at: Long
    ): FractalExperienceCanvasState {
        require(weight in MIN_WEIGHT..MAX_WEIGHT)
        require(at > 0L)

        val cleanNode =
            nodeId.trim().take(128)
        require(
            state.nodes.any {
                it.id == cleanNode
            }
        ) {
            "User-value weight requires an existing fractal node"
        }

        val cleanTurn =
            sourceUserTurn
                .replace('\u0000', ' ')
                .trim()
        require(cleanTurn.isNotBlank()) {
            "Explicit user source is required"
        }

        val recorder =
            recordedBy
                .replace(Regex("[\\r\\n\\t]+"), " ")
                .trim()
                .take(160)
        require(recorder.isNotBlank()) {
            "Recorder provenance is required"
        }

        val sourceHash =
            sha256(cleanTurn).take(24)
        val id =
            "fx-user-value-" +
                sha256(
                    listOf(
                        cleanNode,
                        weight.toString(),
                        sourceHash,
                        recorder,
                        at.toString()
                    ).joinToString("|")
                ).take(20)

        val superseded =
            state.userValueWeights
                .map { previous ->
                    if (
                        previous.nodeId == cleanNode &&
                        previous.status ==
                            FractalUserValueStatus.ACTIVE
                    ) {
                        previous.copy(
                            status =
                                FractalUserValueStatus.SUPERSEDED,
                            supersededBy = id
                        )
                    } else {
                        previous
                    }
                }

        val next =
            FractalUserValueWeight(
                id = id,
                nodeId = cleanNode,
                weight = weight,
                sourceTurnHash = sourceHash,
                recordedBy = recorder,
                at = at
            )

        return state.copy(
            userValueWeights =
                normalize(
                    superseded + next
                )
        )
    }

    /**
     * Current explicit priority for a node. Zero means neutral/revoked.
     */
    fun activeWeight(
        state: FractalExperienceCanvasState,
        nodeId: String
    ): Int =
        normalize(state.userValueWeights)
            .asSequence()
            .filter {
                it.nodeId == nodeId &&
                    it.status ==
                        FractalUserValueStatus.ACTIVE
            }
            .maxByOrNull { it.at }
            ?.weight
            ?: 0

    fun active(
        state: FractalExperienceCanvasState
    ): List<FractalUserValueWeight> =
        normalize(state.userValueWeights)
            .filter {
                it.status ==
                    FractalUserValueStatus.ACTIVE
            }
            .sortedWith(
                compareByDescending<FractalUserValueWeight> {
                    kotlin.math.abs(it.weight)
                }.thenByDescending { it.at }
                    .thenBy { it.nodeId }
            )

    fun normalize(
        records: List<FractalUserValueWeight>
    ): List<FractalUserValueWeight> {
        val valid =
            records.filter {
                it.id.length in 1..128 &&
                    it.nodeId.length in 1..128 &&
                    it.weight in
                        MIN_WEIGHT..MAX_WEIGHT &&
                    it.source ==
                        FractalUserValueSource.EXPLICIT_USER &&
                    hex24.matches(
                        it.sourceTurnHash
                    ) &&
                    it.recordedBy.isNotBlank() &&
                    it.recordedBy.length <= 160 &&
                    it.at > 0L &&
                    (
                        it.supersededBy == null ||
                            it.supersededBy
                                .length in 1..128
                        )
            }

        val deduped =
            valid
                .distinctBy { it.id }
                .sortedWith(
                    compareBy<FractalUserValueWeight> {
                        it.at
                    }.thenBy { it.id }
                )
                .takeLast(MAX_RECORDS)

        // If corrupt/legacy data contains more than one ACTIVE record for a
        // node, only the newest stays active; older ones become superseded in
        // memory rather than competing silently.
        val newestActive =
            deduped
                .filter {
                    it.status ==
                        FractalUserValueStatus.ACTIVE
                }
                .groupBy { it.nodeId }
                .mapValues { (_, group) ->
                    group.maxWithOrNull(
                        compareBy<FractalUserValueWeight> {
                            it.at
                        }.thenBy { it.id }
                    )?.id
                }

        return deduped.map { record ->
            val winner =
                newestActive[record.nodeId]
            if (
                record.status ==
                    FractalUserValueStatus.ACTIVE &&
                winner != null &&
                record.id != winner
            ) {
                record.copy(
                    status =
                        FractalUserValueStatus.SUPERSEDED,
                    supersededBy = winner
                )
            } else {
                record
            }
        }
    }

    /**
     * User priority is only a tie-breaker after semantic relevance and
     * independent-task support. It does not alter evidence confidence.
     */
    fun rankingTuple(
        node: FractalExperienceNode,
        semanticOverlap: Int,
        state: FractalExperienceCanvasState
    ): FractalUserValueRanking =
        FractalUserValueRanking(
            semanticOverlap =
                semanticOverlap,
            distinctTasks =
                node.distinctTasks,
            userWeight =
                activeWeight(
                    state,
                    node.id
                ),
            confidence =
                node.confidence
        )

    fun auditLine(
        record: FractalUserValueWeight
    ): String =
        "node=${record.nodeId} " +
            "weight=${record.weight} " +
            "source=${record.source.name} " +
            "source_turn_hash=${record.sourceTurnHash} " +
            "recorded_by=${record.recordedBy} " +
            "status=${record.status.name} " +
            "at=${record.at}"

    private fun sha256(
        value: String
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                value.toByteArray(
                    Charsets.UTF_8
                )
            )
            .joinToString("") {
                "%02x".format(
                    it.toInt() and 0xff
                )
            }
}

data class FractalUserValueRanking(
    val semanticOverlap: Int,
    val distinctTasks: Int,
    val userWeight: Int,
    val confidence: Double
)
