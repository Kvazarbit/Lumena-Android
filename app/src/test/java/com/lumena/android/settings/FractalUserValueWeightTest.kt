package com.lumena.android.settings

import com.lumena.android.agent.core.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FractalUserValueWeightTest {
    private fun node(
        id: String,
        tasks: Int = 2,
        confidence: Double = 0.8,
        model: String = "model-a",
        summary: String = "preserve project code"
    ) =
        FractalExperienceNode(
            id = id,
            level =
                FractalExperienceLevel.PATTERN,
            peak =
                FractalExperiencePeak.BEST,
            stage =
                FractalExperienceStage.SHADOW,
            scopeHash = null,
            key = "pattern:$summary",
            summary = summary,
            supportCount = tasks,
            failureCount = 0,
            distinctTasks = tasks,
            contributorModelIds =
                listOf(model),
            childIds = emptyList(),
            evidenceIds =
                (1..tasks).map { "e-$id-$it" },
            counterexampleIds =
                emptyList(),
            updatedAt = 1_000L,
            confidence = confidence
        )

    @Test
    fun explicitUserWeightKeepsRecorderAsProvenanceOnly() {
        val original =
            node("n1")
        val state =
            FractalExperienceCanvasState(
                nodes =
                    listOf(original)
            )

        val weighted =
            FractalUserValueWeightPolicy
                .recordExplicit(
                    state = state,
                    nodeId = "n1",
                    weight = 3,
                    sourceUserTurn =
                        "Для мене збереження існуючого коду важливіше за швидкість.",
                    recordedBy =
                        "chatgpt:gpt-5.6-sol",
                    at = 2_000L
                )

        val record =
            weighted.userValueWeights
                .single()
        assertEquals(
            FractalUserValueSource
                .EXPLICIT_USER,
            record.source
        )
        assertEquals(
            "chatgpt:gpt-5.6-sol",
            record.recordedBy
        )
        assertEquals(3, record.weight)
        assertEquals(
            24,
            record.sourceTurnHash.length
        )

        val after =
            weighted.nodes.single()
        assertEquals(
            original.confidence,
            after.confidence,
            0.0
        )
        assertEquals(
            original.peak,
            after.peak
        )
        assertEquals(
            original.supportCount,
            after.supportCount
        )
    }

    @Test
    fun newerExplicitWeightSupersedesButDoesNotEraseHistory() {
        val base =
            FractalExperienceCanvasState(
                nodes = listOf(node("n1"))
            )
        val first =
            FractalUserValueWeightPolicy
                .recordExplicit(
                    base,
                    "n1",
                    3,
                    "Це для мене дуже важливо.",
                    "chatgpt:gpt-5.6-sol",
                    1_000L
                )
        val second =
            FractalUserValueWeightPolicy
                .recordExplicit(
                    first,
                    "n1",
                    0,
                    "Тепер це нейтральний пріоритет.",
                    "human-ui",
                    2_000L
                )

        assertEquals(
            2,
            second.userValueWeights.size
        )
        assertEquals(
            1,
            second.userValueWeights.count {
                it.status ==
                    FractalUserValueStatus
                        .SUPERSEDED
            }
        )
        assertEquals(
            0,
            FractalUserValueWeightPolicy
                .activeWeight(
                    second,
                    "n1"
                )
        )
        assertTrue(
            second.userValueWeights
                .first()
                .supersededBy != null
        )
    }

    @Test
    fun strongerIndependentEvidenceOutranksUserPreference() {
        val strong =
            node(
                id = "strong",
                tasks = 3,
                confidence = 0.7,
                summary = "verify project"
            )
        val weak =
            node(
                id = "weak",
                tasks = 2,
                confidence = 0.9,
                summary = "verify project"
            )
        var state =
            FractalExperienceCanvasState(
                nodes =
                    listOf(strong, weak)
            )
        state =
            FractalUserValueWeightPolicy
                .recordExplicit(
                    state,
                    "strong",
                    -3,
                    "Цей патерн для мене менш пріоритетний.",
                    "chatgpt:gpt-5.6-sol",
                    1_000L
                )
        state =
            FractalUserValueWeightPolicy
                .recordExplicit(
                    state,
                    "weak",
                    3,
                    "Цей патерн для мене більш пріоритетний.",
                    "chatgpt:gpt-5.6-sol",
                    2_000L
                )

        val relevant =
            FractalExperienceCanvasPolicy
                .relevant(
                    state = state,
                    query =
                        "verify project",
                    scopeHash = null,
                    limit = 2
                )

        assertEquals(
            "strong",
            relevant.first().id
        )
    }

    @Test
    fun userWeightBreaksTieWithoutChangingEvidence() {
        val a =
            node(
                id = "a",
                tasks = 2,
                confidence = 0.8,
                summary =
                    "preserve project"
            )
        val b =
            node(
                id = "b",
                tasks = 2,
                confidence = 0.8,
                summary =
                    "preserve project"
            )
        var state =
            FractalExperienceCanvasState(
                nodes = listOf(a, b)
            )
        state =
            FractalUserValueWeightPolicy
                .recordExplicit(
                    state,
                    "b",
                    2,
                    "Для мене цей шлях важливіший.",
                    "chatgpt:gpt-5.6-sol",
                    2_000L
                )

        val relevant =
            FractalExperienceCanvasPolicy
                .relevant(
                    state,
                    "preserve project",
                    null,
                    2
                )

        assertEquals("b", relevant[0].id)
        assertEquals("a", relevant[1].id)
        assertEquals(
            0.8,
            relevant[0].confidence,
            0.0
        )
        assertEquals(
            0.8,
            relevant[1].confidence,
            0.0
        )
    }

    @Test
    fun contributorModelIdentityCannotCreateWeight() {
        val highName =
            node(
                id = "opus",
                model = "opus-5.5",
                summary = "same pattern"
            )
        val lowName =
            node(
                id = "local",
                model = "tiny-local",
                summary = "same pattern"
            )
        val state =
            FractalExperienceCanvasState(
                nodes =
                    listOf(highName, lowName)
            )

        assertEquals(
            0,
            FractalUserValueWeightPolicy
                .activeWeight(
                    state,
                    "opus"
                )
        )
        assertEquals(
            0,
            FractalUserValueWeightPolicy
                .activeWeight(
                    state,
                    "local"
                )
        )
    }

    @Test
    fun nonexistentNodeCannotReceiveNormativeWeight() {
        val state =
            FractalExperienceCanvasState()

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            FractalUserValueWeightPolicy
                .recordExplicit(
                    state,
                    "invented-node",
                    3,
                    "Зроби це важливим.",
                    "chatgpt:gpt-5.6-sol",
                    1_000L
                )
        }
    }

    @Test
    fun thereIsNoModelToolThatCanSelfAssignFractalWeight() {
        assertEquals(
            null,
            ToolRegistry.get(
                "fractal.user_weight"
            )
        )
        assertEquals(
            null,
            ToolRegistry.get(
                "fractal.weight"
            )
        )
    }


    @Test
    fun reprojectionCanDropNodeWithoutErasingUserValueAudit() {
        val base =
            FractalExperienceCanvasState(
                nodes =
                    listOf(
                        node(
                            id = "temporary-node"
                        )
                    )
            )
        val weighted =
            FractalUserValueWeightPolicy
                .recordExplicit(
                    base,
                    "temporary-node",
                    2,
                    "Для мене цей патерн важливий.",
                    "chatgpt:gpt-5.6-sol",
                    1_000L
                )

        val reprojected =
            FractalExperienceCanvasPolicy
                .normalize(weighted)

        assertTrue(
            reprojected.nodes.isEmpty()
        )
        assertEquals(
            1,
            reprojected.userValueWeights.size
        )
        assertEquals(
            "temporary-node",
            reprojected.userValueWeights
                .single()
                .nodeId
        )
    }

@Test
    fun realCanvasRoundTripPreservesUserValueAudit() {
        val example =
            CoordinatorExecutionExample(
                id = "v1",
                kind =
                    CoordinatorExampleKind
                        .VERIFIED_SEQUENCE,
                sourceSessionHash =
                    "task-a",
                tools =
                    listOf(
                        "file.patch",
                        "python.tests"
                    ),
                targets =
                    listOf(
                        "path=demo.py",
                        "cwd=demo"
                    ),
                evidenceIds =
                    listOf("evidence-v1"),
                updatedAt = 1_000L,
                surprise = 0.8,
                text = "fixture",
                contributorModelIds =
                    listOf("model-a"),
                scopeHash = null,
                outcomes =
                    listOf(true, true)
            )
        var state =
            FractalExperienceCanvasPolicy
                .ingest(
                    FractalExperienceCanvasState(),
                    listOf(example)
                )
        val target =
            state.nodes.first {
                it.level ==
                    FractalExperienceLevel
                        .META_RULE
            }
        state =
            FractalUserValueWeightPolicy
                .recordExplicit(
                    state,
                    target.id,
                    2,
                    "Для мене цей принцип важливий.",
                    "chatgpt:gpt-5.6-sol",
                    2_000L
                )

        val encoded =
            FractalExperienceCanvasCodec
                .encode(state)
        val decoded =
            FractalExperienceCanvasCodec
                .decode(encoded)

        assertEquals(
            1,
            decoded.userValueWeights.size
        )
        assertEquals(
            2,
            FractalUserValueWeightPolicy
                .activeWeight(
                    decoded,
                    target.id
                )
        )
        assertEquals(
            "chatgpt:gpt-5.6-sol",
            decoded.userValueWeights
                .single()
                .recordedBy
        )
    }
}
