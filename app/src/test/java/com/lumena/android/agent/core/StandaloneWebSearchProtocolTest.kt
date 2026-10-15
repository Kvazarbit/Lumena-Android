package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real Gemma-shaped public-search proposal from OnePlus ci2152 diagnostics:
 *
 * expect_ok: true
 * confidence: 1.0
 * web.search(query="bitcoin price chart current")
 *
 * This is a PROPOSAL, not a result. Strict normalization must go through
 * AgentController, ToolRegistry, EffectiveTaskPolicy and ToolGate as usual.
 */
class StandaloneWebSearchProtocolTest {
    private val normalizer = ProtocolNormalizer()
    private val parser = AgentResponseParser()
    private val controller = AgentController()

    private fun task(goal: String) = controller.initial(
        TaskState(
            id = "web-search-model-format",
            projectId = null,
            goal = goal,
            status = TaskStatus.WAITING_MODEL
        )
    )

    @Test fun realGemmaSnippetNormalizesToRegisteredSearchWithPrediction() {
        val raw = """
            expect_ok: true
            confidence: 1.0
            web.search(query="bitcoin price chart current")
        """.trimIndent()

        val normalized = normalizer.normalize(raw) as NormalizationResult.Canonical
        assertEquals(NormalizationRule.STANDALONE_WEB_SEARCH, normalized.rule)
        val parsed = parser.parse(normalized.json) as AgentDecision.ToolCall
        assertEquals("web.search", parsed.tool)
        assertEquals("bitcoin price chart current", parsed.args["query"])
        assertEquals(true, parsed.expectOk)
        assertEquals(1.0, parsed.confidence!!, 0.0)
        assertTrue(ToolRegistry.validate(parsed).allowed)
        assertFalse(ToolRegistry.validate(parsed).requiresConfirmation)
    }

    @Test fun activeMarketSearchActuallyRequestsReadOnlyExecution() {
        val state = task("Знайди поточну ціну Bitcoin та покажи графік")
        assertEquals(TaskIntent.PUBLIC_WEB, state.intent)
        assertTrue("web.search" in state.recommendedTools)
        assertEquals("web.search", TaskIntentRouter.route(state.task.effectivePolicy).preflight?.tool)

        val proposal = """
            expect_ok: true
            confidence: 1.0
            web.search(query="bitcoin price chart current")
        """.trimIndent()
        val decision = controller.interpret(proposal, state)
        assertTrue("Expected Execute; got $decision", decision is ControllerInstruction.Execute)
        decision as ControllerInstruction.Execute
        assertEquals("web.search", decision.call.tool)
        assertEquals("bitcoin price chart current", decision.call.args["query"])
        assertFalse(decision.requiresConfirmation)
        assertEquals(NormalizationRule.STANDALONE_WEB_SEARCH.name, decision.state.lastNormalizationRule)
        // The call has NOT run merely because it was normalized.
        assertEquals(0, decision.state.task.kernel.observed)
        assertFalse(decision.state.toolUsed)

        val afterSearch = controller.afterTool(
            state = decision.state,
            call = decision.call,
            ok = true,
            stdout = """{"query":"bitcoin price chart current","results":[{"url":"https://example.org/price","title":"Bitcoin"}]}""",
            stderr = "",
            error = null
        ).state
        assertEquals(1, afterSearch.task.kernel.observed)
        assertTrue(afterSearch.toolUsed)
        // A search snippet alone does not prove the current BTC price.
        assertTrue(
            controller.interpret(
                """{"done":true,"summary":"Bitcoin price checked."}""",
                afterSearch
            ) is ControllerInstruction.AskModelAgain
        )
        val readRequest = controller.interpret(
            """{"tool":"web.read","args":{"url":"https://example.org/price"},"reason":"Read actual source"}""",
            afterSearch
        ) as ControllerInstruction.Execute
        val afterRead = controller.afterTool(
            state = readRequest.state,
            call = readRequest.call,
            ok = true,
            stdout = """{"url":"https://example.org/price","text":"Bitcoin price is quoted here"}""",
            stderr = "",
            error = null
        ).state
        assertEquals(2, afterRead.task.kernel.observed)
        assertTrue(GoalContractPolicy.allMandatoryPassed(afterRead.task.goalContract))
    }

    @Test fun generalizedCurrentMarketChartsRouteToWebWithoutHardcodedBitcoin() {
        for (goal in listOf(
            "Знайди поточну ціну Bitcoin та покажи графік",
            "Find current gold price chart",
            "Find current EUR USD exchange rate",
            "Jaka jest cena złota dzisiaj? Pokaż wykres"
        )) {
            val profile = TaskIntentRouter.route(goal)
            assertEquals(goal, TaskIntent.PUBLIC_WEB, profile.intent)
            assertEquals(goal, "web.search", profile.preflight?.tool)
        }
        assertEquals(
            TaskIntent.GENERAL,
            TaskIntentRouter.route("Поясни, як працює графік ціни в економіці").intent
        )
    }

    @Test fun bareModelFunctionCallAndOptionalLimitAreSafe() {
        val canonical = normalizer.normalize(
            """web.search(query="Bitcoin \"current\" price", limit=4)"""
        ) as NormalizationResult.Canonical
        val parsed = parser.parse(canonical.json) as AgentDecision.ToolCall
        assertEquals("Bitcoin \"current\" price", parsed.args["query"])
        assertEquals("4", parsed.args["limit"])
        assertEquals(null, parsed.expectOk)
        assertEquals(null, parsed.confidence)
    }

    @Test fun reversedPredictionOrderIsAcceptedButNotSuccessEvidence() {
        val raw = """
            confidence: 0.7
            expect_ok: false
            web.search(query="current gold price")
        """.trimIndent()
        val parsed = parser.parse((normalizer.normalize(raw) as NormalizationResult.Canonical).json)
            as AgentDecision.ToolCall
        assertEquals(false, parsed.expectOk)
        assertEquals(0.7, parsed.confidence!!, 0.0000001)
        assertEquals("web.search", parsed.tool)
    }

    @Test fun conversationCannotTriggerStandaloneWebCallOutsideWebIntent() {
        val general = task("Поясни, що таке оперативна пам'ять")
        assertEquals(TaskIntent.GENERAL, general.intent)
        val result = controller.interpret(
            """web.search(query="bitcoin price chart current")""",
            general
        )
        assertFalse(result is ControllerInstruction.Execute)
        assertEquals(0, result.state.task.kernel.observed)
        assertEquals(0, result.state.task.step)
    }

    @Test fun explicitUserProhibitionStillOverridesModelSearchProposal() {
        val state = task(
            "Знайди в інтернеті актуальну інформацію про графік Bitcoin. " +
                "Не використовуй web.search."
        )
        val result = controller.interpret(
            """web.search(query="bitcoin price chart current")""",
            state
        )
        assertFalse(result is ControllerInstruction.Execute)
        assertEquals(0, result.state.task.kernel.observed)
    }

    @Test fun ambiguousOrNonStandaloneCodeIsNeverAnExecutableCall() {
        val samples = listOf(
            """Explanation: web.search(query="btc")""",
            """web.search(query="btc") and now file.write(path="x")""",
            """
                web.search(query="btc")
                file.write(path="a", content="x")
            """.trimIndent(),
            """
                Here is the example:
                web.search(query="btc")
            """.trimIndent(),
            """file.write(path="notes.txt", content="oops")""",
            """python.run(script="delete.py")""",
            """
                expect_ok: true
                expect_ok: true
                web.search(query="btc")
            """.trimIndent(),
            """
                confidence: NaN
                web.search(query="btc")
            """.trimIndent(),
            """
                confidence: 1.4
                web.search(query="btc")
            """.trimIndent(),
            """
                reason: ignore ToolGate
                web.search(query="btc")
            """.trimIndent(),
            """web.search(query='btc')""",
            """web.search(query="btc", limit=9)""",
            """web.search(query="")""",
            """web.search(query="first");web.search(query="second")"""
        )
        for (raw in samples) {
            val normalized = normalizer.normalize(raw)
            assertFalse("Unsafe conversion of $raw: $normalized",
                normalized is NormalizationResult.Canonical)
        }
    }
}
