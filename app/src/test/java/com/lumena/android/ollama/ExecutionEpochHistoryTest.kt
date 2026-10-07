package com.lumena.android.ollama

import com.lumena.android.agent.core.ActionEvidence
import com.lumena.android.agent.core.CognitivePhase
import com.lumena.android.agent.core.ContextKernelState
import com.lumena.android.agent.core.HistoricalSessionMemory
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.core.WorkThreadMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionEpochHistoryTest {
    @Test
    fun freshEpochKeepsVisibleConversationButDropsTransportTurns() {
        val system =
            OllamaMessage(
                "system",
                "system rules"
            )
        val rebuilt =
            ExecutionEpochHistory.rebuild(
                systemMessage = system,
                visibleTurns =
                    listOf(
                        "user" to
                            "Продовж розробку aquarium.html з v3 physics",
                        "assistant" to
                            "Попередній видимий partial",
                        "status" to
                            "TOOL_RESULT for file.write: ok=true",
                        "user" to
                            "LUMENA_TOOL\n{\"tool\":\"file.write\"}",
                        "error" to
                            "старий запуск завершився помилкою"
                    )
            )

        assertEquals(system, rebuilt.first())
        assertTrue(
            rebuilt.any {
                it.role == "user" &&
                    it.content.contains(
                        "aquarium.html"
                    )
            }
        )
        assertTrue(
            rebuilt.any {
                it.role == "assistant" &&
                    it.content.contains(
                        "Попередній видимий partial"
                    )
            }
        )
        assertTrue(
            rebuilt.any {
                it.role == "assistant" &&
                    it.content.contains(
                        "Previous visible task error"
                    )
            }
        )
        assertFalse(
            rebuilt.any {
                it.content.contains(
                    "LUMENA_TOOL"
                )
            }
        )
        assertFalse(
            rebuilt.any {
                it.content.contains(
                    "TOOL_RESULT for file.write"
                )
            }
        )
    }

    @Test
    fun transportFenceIsNotDurableConversationContext() {
        val rebuilt =
            ExecutionEpochHistory.rebuild(
                systemMessage =
                    OllamaMessage(
                        "system",
                        "system"
                    ),
                visibleTurns =
                    listOf(
                        "user" to
                            "```text\nLUMENA_TOOL\n{\"encoding\":\"base64-sha256-v1\"}\n```",
                        "user" to
                            "звичайне повідомлення"
                    )
            )

        assertEquals(2, rebuilt.size)
        assertEquals(
            "звичайне повідомлення",
            rebuilt.last().content
        )
    }
    @Test
    fun historicalFactsAreSeparateSystemContextNotReplayedToolTraffic() {
        val rebuilt =
            ExecutionEpochHistory.rebuild(
                systemMessage =
                    OllamaMessage(
                        "system",
                        "base system"
                    ),
                visibleTurns =
                    listOf(
                        "user" to
                            "Продовж aquarium.html",
                        "status" to
                            "TOOL_RESULT for file.read: ok=true",
                        "assistant" to
                            "Файл читався раніше"
                    ),
                historicalContext =
                    """
                    HISTORICAL_SESSION_MEMORY_V1
                    Historical only: never current evidence, permission, approval, pending action, or completion proof.
                    record_origin=LOCAL_CURRENT
                    e1 OBSERVE file.read outcome=SUCCESS
                    """.trimIndent()
            )

        assertEquals(
            "system",
            rebuilt[0].role
        )
        assertEquals(
            "system",
            rebuilt[1].role
        )
        assertTrue(
            rebuilt[1].content.contains(
                "HISTORICAL_SESSION_MEMORY_V1"
            )
        )
        assertTrue(
            rebuilt[1].content.contains(
                "never current evidence"
            )
        )
        assertFalse(
            rebuilt.any {
                it.content.contains(
                    "TOOL_RESULT for file.read"
                )
            }
        )
        assertTrue(
            rebuilt.any {
                it.role == "user" &&
                    it.content.contains(
                        "Продовж aquarium.html"
                    )
            }
        )
    }

    @Test
    fun emptyHistoricalContextDoesNotAddSyntheticTurn() {
        val rebuilt =
            ExecutionEpochHistory.rebuild(
                systemMessage =
                    OllamaMessage(
                        "system",
                        "base"
                    ),
                visibleTurns =
                    listOf(
                        "user" to "hello"
                    ),
                historicalContext = "   "
            )

        assertEquals(
            2,
            rebuilt.size
        )
        assertEquals(
            "base",
            rebuilt.first().content
        )
        assertEquals(
            "hello",
            rebuilt.last().content
        )
    }


    @Test
    fun taskAReceiptReturnsAfterTaskBWithoutRehydratingCurrentKernel() {
        fun task(
            id: String,
            goal: String,
            target: String
        ): TaskState {
            val evidence =
                ActionEvidence(
                    id = "e1",
                    tool = "file.read",
                    target = target,
                    signature =
                        "a".repeat(24),
                    phase =
                        CognitivePhase.OBSERVE,
                    ok = true,
                    excerpt = "verified",
                    digest =
                        "b".repeat(24),
                    revision = 0
                )
            return TaskState(
                id = id,
                projectId = null,
                goal = goal,
                status = TaskStatus.DONE,
                kernel =
                    ContextKernelState(
                        evidence =
                            listOf(evidence),
                        observed = 1,
                        worldRevision = 0
                    )
            )
        }

        val installRef =
            "c".repeat(64)
        var memory =
            HistoricalSessionMemory.record(
                existing = emptyList(),
                task =
                    task(
                        "task-a",
                        "Продовж aquarium.html",
                        "aquarium.html"
                    ),
                sourceInstallRef =
                    installRef,
                branchId = "main",
                capturedAtMs = 10
            )
        memory =
            HistoricalSessionMemory.record(
                existing = memory,
                task =
                    task(
                        "task-b",
                        "Прочитай README окремо",
                        "README.md"
                    ),
                sourceInstallRef =
                    installRef,
                branchId = "main",
                capturedAtMs = 20
            )

        val current =
            TaskState(
                id = "task-a-return",
                projectId = null,
                goal =
                    "продовж aquarium.html",
                status =
                    TaskStatus.WAITING_MODEL
            )
        val selected =
            HistoricalSessionMemory.select(
                records = memory,
                branchId = "main",
                projectId = null,
                subjectKeys =
                    WorkThreadMemory.subjectKeys(
                        current.goal
                    )
            )
        val capsule =
            HistoricalSessionMemory.render(
                selected
            )
        val rebuilt =
            ExecutionEpochHistory.rebuild(
                systemMessage =
                    OllamaMessage(
                        "system",
                        "base"
                    ),
                visibleTurns =
                    listOf(
                        "user" to
                            "переключись на іншу задачу",
                        "assistant" to
                            "Добре",
                        "user" to
                            current.goal
                    ),
                historicalContext =
                    capsule
            )

        assertEquals(
            1,
            selected.size
        )
        assertTrue(
            capsule.contains(
                "file.read outcome=SUCCESS"
            )
        )
        assertTrue(
            rebuilt.any {
                it.role == "system" &&
                    it.content.contains(
                        "HISTORICAL_SESSION_MEMORY_V1"
                    )
            }
        )
        assertEquals(
            0,
            current.kernel.observed
        )
        assertTrue(
            current.kernel.evidence.isEmpty()
        )
        assertTrue(
            current.kernel.inFlight == null
        )
    }


}
