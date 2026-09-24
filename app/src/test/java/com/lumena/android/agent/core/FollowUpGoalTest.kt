package com.lumena.android.agent.core

import org.junit.Assert.*
import org.junit.Test

class FollowUpGoalTest {
    @Test fun repeatRetainsActualGoalAcrossRepeatedRetries() {
        val goal = "Знайди новини в інтернеті"
        val retry = FollowUpGoal.resolve("повтори!", goal)
        assertEquals(goal, retry)
        assertEquals(goal, FollowUpGoal.resolve("Продовжуй", retry))
    }

    @Test fun narrowNewsFollowUpsRetainPreviousResearchGoal() {
        val goal =
            "Знайди в інтернеті останні новини Python сьогодні і коротко підсумуй їх"

        for (followUp in listOf(
            "а друга новина?",
            "ще новина?",
            "наступна новина",
            "another news item?",
            "kolejna wiadomość?"
        )) {
            assertTrue(FollowUpGoal.isReference(followUp))
            assertEquals(goal, FollowUpGoal.resolve(followUp, goal))
        }
    }

    @Test fun researchAnchorSurvivesInterveningMetaChat() {
        val research =
            "Знайди в інтернеті останні новини саме про мову програмування Python"

        val first = ResearchGoalAnchor.resolve(
            text = research,
            previousGoal = null,
            researchGoal = null
        )
        assertEquals(research, first.goal)
        assertEquals(research, first.researchGoal)

        val meta = ResearchGoalAnchor.resolve(
            text = "там немає ліміту, бо ти працюєш",
            previousGoal = first.goal,
            researchGoal = first.researchGoal
        )
        assertEquals("там немає ліміту, бо ти працюєш", meta.goal)
        assertEquals(research, meta.researchGoal)

        val contextComplaint = ResearchGoalAnchor.resolve(
            text = "ти що забув контекст, я шукав в інтернеті",
            previousGoal = meta.goal,
            researchGoal = meta.researchGoal
        )
        assertEquals(
            "ти що забув контекст, я шукав в інтернеті",
            contextComplaint.goal
        )
        assertEquals(research, contextComplaint.researchGoal)

        val followUp = ResearchGoalAnchor.resolve(
            text = "а друга новина?",
            previousGoal = contextComplaint.goal,
            researchGoal = contextComplaint.researchGoal
        )
        assertEquals(research, followUp.goal)
        assertEquals(research, followUp.researchGoal)
    }

    @Test fun newsReferenceDoesNotInventResearchGoalWithoutHistory() {
        val result = ResearchGoalAnchor.resolve(
            text = "а друга новина?",
            previousGoal = null,
            researchGoal = null
        )
        assertEquals("а друга новина?", result.goal)
        assertNull(result.researchGoal)
    }

    @Test fun failureExplanationReferencesPreviousGoalAcrossLanguages() {
        val goal = "Працюй у проєкті demo_project і виконай перевірку"

        for (followUp in listOf(
            "чому?",
            "що сталося?",
            "почему?",
            "why?",
            "what happened?",
            "dlaczego?",
            "co się stało?"
        )) {
            assertTrue(FollowUpGoal.isOutcomeReference(followUp))
            assertTrue(FollowUpGoal.isReference(followUp))
            assertEquals(goal, FollowUpGoal.resolve(followUp, goal))
        }
    }

    @Test fun failedOutcomeCapsuleIsBoundedHistoricalContextOnly() {
        val call = AgentDecision.ToolCall(
            tool = "web.search",
            args = mapOf("query" to "python pathlib mkdir")
        )
        val kernel = ContextKernel.record(
            state = ContextKernel.before(
                ContextKernelState(),
                call
            ),
            call = call,
            ok = true,
            output = "verified search result"
        )
        val task = TaskState(
            id = "task-8-2",
            projectId = "demo_project",
            goal = "Research and apply pathlib mkdir",
            status = TaskStatus.FAILED,
            lastTool = "web.search",
            lastResult = "ok=true",
            kernel = kernel
        )

        val capsule = PreviousTaskOutcomeContext.failure(
            task = task,
            message =
                "Protocol-looking output is not one valid JSON envelope"
        )

        assertTrue(capsule.startsWith("PREVIOUS_TASK_OUTCOME"))
        assertTrue(capsule.contains("status=FAILED"))
        assertTrue(capsule.contains("project_id=demo_project"))
        assertTrue(capsule.contains("last_tool=web.search"))
        assertTrue(capsule.contains("INVALID").not())
        assertTrue(
            capsule.contains(
                "Protocol-looking output is not one valid JSON envelope"
            )
        )
        assertTrue(capsule.contains("HISTORICAL_ONLY"))
        assertTrue(capsule.contains("no approval"))
        assertTrue(capsule.length <= 4_500)
    }


    @Test fun boundedPreviousTaskPhrasesRetainPriorGoal() {
        val goal = """
            Працюй у проекті e2e_step87.
            Прочитай через web.read і потім запусти python.tests.
        """.trimIndent()

        for (followUp in listOf(
            "повтори, з відповідним форматом, завдання вище...",
            "повтори попереднє завдання",
            "retry the previous task",
            "continue the task above",
            "powtórz poprzednie zadanie"
        )) {
            assertTrue(
                followUp,
                FollowUpGoal.isPriorTaskReference(followUp)
            )
            assertTrue(
                followUp,
                FollowUpGoal.isReference(followUp)
            )
            assertEquals(
                followUp,
                goal,
                FollowUpGoal.resolve(followUp, goal)
            )
        }

        assertFalse(
            FollowUpGoal.isPriorTaskReference(
                "повтори тест файлу"
            )
        )
        assertEquals(
            "повтори тест файлу",
            FollowUpGoal.resolve(
                "повтори тест файлу",
                goal
            )
        )
    }

    @Test fun partialOutcomeCapsulePreservesGoalAndVerifiedStateWithoutAuthority() {
        val call = AgentDecision.ToolCall(
            tool = "python.syntax_check",
            args = mapOf(
                "script" to "e2e_step87/dir_a.py"
            )
        )
        val kernel = ContextKernel.record(
            state = ContextKernel.before(
                ContextKernelState(),
                call
            ),
            call = call,
            ok = true,
            output = "syntax ok"
        )
        val task = TaskState(
            id = "task-partial",
            projectId = "e2e_step87",
            goal = "Run the original e2e verification task",
            status = TaskStatus.PARTIAL,
            lastTool = "python.syntax_check",
            lastResult = "ok=true",
            kernel = kernel
        )

        val capsule = PreviousTaskOutcomeContext.partial(
            task = task,
            message =
                "python.tests was not executed yet"
        )

        assertTrue(
            capsule.startsWith(
                "PREVIOUS_TASK_OUTCOME"
            )
        )
        assertTrue(capsule.contains("status=PARTIAL"))
        assertTrue(capsule.contains("project_id=e2e_step87"))
        assertTrue(
            capsule.contains(
                "goal=Run the original e2e verification task"
            )
        )
        assertTrue(
            capsule.contains(
                "summary=python.tests was not executed yet"
            )
        )
        assertTrue(capsule.contains("verified_kernel:"))
        assertTrue(capsule.contains("HISTORICAL_ONLY"))
        assertTrue(capsule.contains("no approval"))
        assertTrue(capsule.contains("no replay permission"))
        assertTrue(capsule.length <= 4_500)
    }

    @Test fun explicitNewGoalAndMissingHistoryArePreserved() {
        assertEquals("повтори", FollowUpGoal.resolve("повтори", null))
        assertEquals("повтори тест файлу", FollowUpGoal.resolve("повтори тест файлу", "старе завдання"))
        assertEquals("why?", FollowUpGoal.resolve("why?", null))
    }
}
