package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticGoalEvidenceTest {
    private val controller = AgentController()

    private fun state(goal: String): AgentControlState = controller.initial(
        TaskState(
            id = "semantic-" + ContextKernel.hash(goal).take(10),
            projectId = null,
            goal = goal,
            status = TaskStatus.WAITING_MODEL
        )
    )

    private fun after(
        state: AgentControlState,
        tool: String,
        args: Map<String, String>,
        stdout: String
    ): AgentControlState = controller.afterTool(
        state = state,
        call = AgentDecision.ToolCall(tool, args, "fixture"),
        ok = true,
        stdout = stdout,
        stderr = "",
        error = null
    ).state

    private fun done(state: AgentControlState) = controller.interpret(
        """{"done":true,"summary":"Задачу виконано і перевірено."}""",
        state
    )

    @Test fun offTopicImageCannotBeDeclaredDoneDespiteSuccessfulTool() {
        val goal = "Знайди фото червоного кота і покажи"
        val original = state(goal)
        val result = after(
            original,
            "image.search",
            mapOf("query" to "red cat"),
            """{"query":"red cat","display_ready":true,"images":[{"title":"Blue car","thumbnail_url":"https://example.com/car.jpg","matched_query":"red cat"}]}"""
        )
        assertTrue(result.visualEvidenceReady)
        assertEquals(
            CriterionStatus.PENDING,
            result.task.goalContract.criteria
                .first { it.id == "visual-metadata-alignment" }.status
        )
        assertTrue(done(result).state.task.status != TaskStatus.DONE)
    }

    @Test fun imageSourceQueryEchoIsNotMetadataEvidence() {
        val output = """{"query":"red cat","display_ready":true,"images":[{"title":"Blue car","matched_query":"red cat","source_page":"https://example.org/red-cat"}]}"""
        assertFalse(
            SemanticGoalEvidence.imageMetadataSupportsGoal(
                "Знайди фото червоного кота і покажи", output
            )
        )
    }

    @Test fun crossCandidateKeywordMixingIsNotAllowed() {
        val output = """{"display_ready":true,"images":[{"title":"A red car"},{"title":"A blue cat"}]}"""
        assertFalse(
            SemanticGoalEvidence.imageMetadataSupportsGoal(
                "Find a photo of a red cat", output
            )
        )
    }

    @Test fun plausibleTitleCanSatisfyMetadataButNotPixelTruth() {
        val output = """{"display_ready":true,"images":[{"title":"Red cat","thumbnail_url":"https://example.org/cat.jpg"}]}"""
        assertTrue(SemanticGoalEvidence.imageMetadataSupportsGoal(
            "Знайди фото червоного кота і покажи", output
        ))
        // This does NOT certify pixels — the test only verifies title alignment.
        val result = after(
            state("Знайди фото червоного кота і покажи"),
            "image.search",
            mapOf("query" to "red cat"),
            output
        )
        assertEquals(
            CriterionStatus.PASSED,
            result.task.goalContract.criteria
                .first { it.id == "visual-metadata-alignment" }.status
        )
    }

    @Test fun missingOrMalformedVisualMetadataCannotCertifyRelevance() {
        assertFalse(SemanticGoalEvidence.imageMetadataSupportsGoal(
            "Find a photo of a cat", """{"display_ready":true,"images":[{"thumbnail_url":"https://example.org/cat.jpg"}]}"""
        ))
        assertFalse(SemanticGoalEvidence.imageMetadataSupportsGoal(
            "Find a photo of a cat", "truncated JSON"
        ))
        assertFalse(SemanticGoalEvidence.imageMetadataSupportsGoal(
            "Find a photo of a cat", """{"display_ready":false,"images":[{"title":"Cat"}]}"""
        ))
    }

    @Test fun officialHostNeedsSuccessfulFinalUrlNotInputUrlOnly() {
        val official = "gov.pl"
        val request = AgentDecision.ToolCall(
            "web.read",
            mapOf("url" to "https://www.gov.pl/something")
        )
        assertFalse(SemanticGoalEvidence.fetchedFromRequiredHost(
            request, "Blog content", official
        ))
        assertFalse(SemanticGoalEvidence.fetchedFromRequiredHost(
            request, """{"url":"https://www.gov.pl.example.org/page","text":"untrusted"}""", official
        ))
        assertFalse(SemanticGoalEvidence.fetchedFromRequiredHost(
            request, """{"url":"https://other.example.com/redirect","text":"untrusted"}""", official
        ))
        assertTrue(SemanticGoalEvidence.fetchedFromRequiredHost(
            request, """{"url":"https://www.gov.pl/web/topic","text":"Official page text"}""", official
        ))
    }

    @Test fun officialSourceIsBoundToTypedGoalCriterion() {
        val goal = "Знайди офіційну інструкцію gov.pl про протипожежні двері в інтернеті"
        val initial = state(goal)
        val criterionId = "required-source-host:gov.pl"
        assertTrue(initial.task.goalContract.criteria.any { it.id == criterionId })
        val unrelated = after(
            initial, "web.read",
            mapOf("url" to "https://blog.example.org/source"),
            """{"url":"https://blog.example.org/source","text":"blog content"}"""
        )
        assertEquals(CriterionStatus.PENDING,
            unrelated.task.goalContract.criteria.first { it.id == criterionId }.status)
        assertTrue(done(unrelated).state.task.status != TaskStatus.DONE)

        val official = after(
            unrelated, "web.read",
            mapOf("url" to "https://www.gov.pl/web/topic"),
            """{"url":"https://www.gov.pl/web/topic","text":"Official content"}"""
        )
        assertEquals(CriterionStatus.PASSED,
            official.task.goalContract.criteria.first { it.id == criterionId }.status)
    }

    @Test fun compileClaimDoesNotFollowFromKotlinFileWrite() {
        val goal = "Створи Kotlin файл Sample.kt і перевір, що він компілюється"
        val initial = state(goal)
        assertTrue(SemanticGoalEvidence.explicitKotlinCompilationGoal(goal))
        assertTrue(initial.task.goalContract.criteria.any {
            it.id == "kotlin-compile-verification" &&
                it.status == CriterionStatus.PENDING
        })
        val written = after(
            initial, "file.write",
            mapOf("path" to "Sample.kt", "content" to "fun broken( ="),
            "wrote Sample.kt"
        )
        assertTrue(done(written).state.task.status != TaskStatus.DONE)
    }

    @Test fun noSyntheticCompilerRequirementForOrdinaryKotlinDiscussion() {
        val goal = "Поясни, що таке Kotlin"
        assertFalse(SemanticGoalEvidence.explicitKotlinCompilationGoal(goal))
        assertTrue(state(goal).task.goalContract.criteria.none {
            it.id == "kotlin-compile-verification"
        })
    }
}
