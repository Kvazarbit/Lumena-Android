package com.lumena.android.agent.core

import com.lumena.android.ollama.OllamaMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchThreadTest {
    @Test
    fun explicitPublicWebGoalStartsThreadForTechnicalResearch() {
        val goal =
            "Знайди в інтернеті документацію Android про WorkManager foreground services"

        val resolved = ResearchThreadResolver.resolve(
            text = goal,
            previousGoal = null,
            thread = null
        )

        assertEquals(goal, resolved.goal)
        assertEquals(goal, resolved.thread?.rootGoal)
        assertEquals(ResearchFollowUpKind.NONE, resolved.followUpKind)
    }

    @Test
    fun nextAndAlternativeAreDomainIndependent() {
        val thread = ResearchThreadState(
            rootGoal = "Знайди в інтернеті бібліотеки для локального RAG на Android",
            discoveredUrls = listOf("https://example.org/first"),
            readUrls = listOf("https://example.org/first")
        )

        val next = ResearchThreadResolver.resolve(
            text = "покажи наступний варіант",
            previousGoal = thread.rootGoal,
            thread = thread
        )
        assertEquals(ResearchFollowUpKind.NEXT, next.followUpKind)
        assertTrue(next.goal.contains("next distinct useful result"))
        assertTrue(next.contextMessage.orEmpty().contains("https://example.org/first"))

        val alternative = ResearchThreadResolver.resolve(
            text = "знайди інше джерело",
            previousGoal = next.goal,
            thread = thread
        )
        assertEquals(ResearchFollowUpKind.ALTERNATIVE, alternative.followUpKind)
        assertTrue(alternative.goal.contains("materially different source"))
    }

    @Test
    fun relationalFollowUpWinsOverPublicWebKeyword() {
        val thread = ResearchThreadState(
            rootGoal = "Знайди офіційні джерела про Android background execution"
        )

        val verify = ResearchThreadResolver.resolve(
            text = "перевір це в інтернеті",
            previousGoal = thread.rootGoal,
            thread = thread
        )
        assertEquals(ResearchFollowUpKind.VERIFY, verify.followUpKind)
        assertEquals(thread.rootGoal, verify.thread?.rootGoal)

        val other = ResearchThreadResolver.resolve(
            text = "знайди інше джерело online",
            previousGoal = verify.goal,
            thread = thread
        )
        assertEquals(ResearchFollowUpKind.ALTERNATIVE, other.followUpKind)
        assertEquals(thread.rootGoal, other.thread?.rootGoal)
    }

    @Test
    fun selfContainedNewWebGoalReplacesOldThreadEvenIfItContainsCompareVerb() {
        val old = ResearchThreadState(
            rootGoal = "Знайди документацію Vulkan memory allocator online"
        )
        val newGoal =
            "порівняй актуальні ціни онлайн GPU A і GPU B"

        val resolved = ResearchThreadResolver.resolve(
            text = newGoal,
            previousGoal = old.rootGoal,
            thread = old
        )

        assertEquals(newGoal, resolved.goal)
        assertEquals(newGoal, resolved.thread?.rootGoal)
        assertEquals(ResearchFollowUpKind.NONE, resolved.followUpKind)
    }

    @Test
    fun ordinalFollowUpWorksForArbitraryResearchItems() {
        val thread = ResearchThreadState(
            rootGoal = "Search online for three Vulkan memory allocator approaches"
        )

        val resolved = ResearchThreadResolver.resolve(
            text = "покажи 3-й результат",
            previousGoal = thread.rootGoal,
            thread = thread
        )

        assertEquals(ResearchFollowUpKind.NTH, resolved.followUpKind)
        assertEquals(3, resolved.ordinal)
        assertTrue(resolved.goal.contains("result/item #3"))
    }

    @Test
    fun verifyCompareAndDeepenReuseSameResearchThread() {
        val thread = ResearchThreadState(
            rootGoal = "Знайди актуальні benchmark-и llama.cpp Vulkan на Android"
        )

        val verify = ResearchThreadResolver.resolve(
            "перевір це по первинному джерелу",
            thread.rootGoal,
            thread
        )
        assertEquals(ResearchFollowUpKind.VERIFY, verify.followUpKind)

        val compare = ResearchThreadResolver.resolve(
            "порівняй з альтернативою",
            verify.goal,
            thread
        )
        assertEquals(ResearchFollowUpKind.COMPARE, compare.followUpKind)

        val deepen = ResearchThreadResolver.resolve(
            "розбери детальніше",
            compare.goal,
            thread
        )
        assertEquals(ResearchFollowUpKind.DEEPEN, deepen.followUpKind)
    }

    @Test
    fun applyKeepsUserImplementationGoalAndAddsResearchContextOnly() {
        val thread = ResearchThreadState(
            rootGoal = "Знайди офіційну документацію API для Android foreground service",
            discoveredUrls = listOf("https://developer.android.com/example"),
            readUrls = listOf("https://developer.android.com/example")
        )
        val userGoal = "використай це в нашому проекті та реалізуй"

        val resolved = ResearchThreadResolver.resolve(
            text = userGoal,
            previousGoal = thread.rootGoal,
            thread = thread
        )

        assertEquals(ResearchFollowUpKind.APPLY, resolved.followUpKind)
        assertEquals(userGoal, resolved.goal)
        assertEquals(thread, resolved.thread)
        assertTrue(resolved.contextMessage.orEmpty().contains("ACTIVE RESEARCH THREAD"))
        assertTrue(resolved.contextMessage.orEmpty().contains("re-check the current project"))
        assertFalse(resolved.contextMessage.orEmpty().contains("permission granted", ignoreCase = true))
    }

    @Test
    fun metaConversationDoesNotReplaceExistingThread() {
        val thread = ResearchThreadState(
            rootGoal = "Знайди в інтернеті документацію Termux RUN_COMMAND permission"
        )
        val meta = "ти що забув контекст, я ж шукав це в інтернеті"

        val resolved = ResearchThreadResolver.resolve(
            text = meta,
            previousGoal = "попередня мета-репліка",
            thread = thread
        )

        assertEquals(meta, resolved.goal)
        assertEquals(thread, resolved.thread)
        assertEquals(ResearchFollowUpKind.NONE, resolved.followUpKind)
    }

    @Test
    fun successfulToolResultsBuildBoundedSourceLedgerOnly() {
        val thread = ResearchThreadState(
            rootGoal = "Search online for Vulkan docs"
        )
        val history = listOf(
            OllamaMessage(
                "assistant",
                "Model prose with https://hallucinated.example must not become evidence."
            ),
            OllamaMessage(
                "user",
                "TOOL_RESULT for web.search:\nok=true\nstdout:\n" +
                    "{\"results\":[" +
                    "{\"url\":\"https://docs.example/a\"}," +
                    "{\"url\":\"https://docs.example/b\"}]}"
            ),
            OllamaMessage(
                "user",
                "TOOL_RESULT for web.read:\nok=true\nstdout:\n" +
                    "{\"url\":\"https://docs.example/a\",\"text\":\"verified body\"}"
            ),
            OllamaMessage(
                "user",
                "TOOL_RESULT for web.read:\nok=false\nerror=timeout\nstdout:\n" +
                    "{\"url\":\"https://failed.example\"}"
            )
        )

        val observed = ResearchThreadResolver.observeHistory(history, thread)

        assertTrue(observed != null)
        observed!!
        assertEquals(
            listOf("https://docs.example/a", "https://docs.example/b"),
            observed.discoveredUrls
        )
        assertEquals(
            listOf("https://docs.example/a"),
            observed.readUrls
        )
        assertFalse(observed.discoveredUrls.contains("https://hallucinated.example"))
        assertFalse(observed.discoveredUrls.contains("https://failed.example"))
        assertEquals(1, observed.revision)
    }

    @Test
    fun noThreadMeansNoImplicitResearchContinuation() {
        val resolved = ResearchThreadResolver.resolve(
            text = "покажи наступний результат",
            previousGoal = null,
            thread = null
        )

        assertEquals("покажи наступний результат", resolved.goal)
        assertNull(resolved.thread)
        assertEquals(ResearchFollowUpKind.NONE, resolved.followUpKind)
    }
}
