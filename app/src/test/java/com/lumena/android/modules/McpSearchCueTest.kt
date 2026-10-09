package com.lumena.android.modules

import com.lumena.android.agent.core.BridgeCompatibility
import com.lumena.android.agent.core.TaskIntentRouter
import com.lumena.android.agent.core.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSearchCueTest {
    @Test fun ownerTyposAndMixedScriptsStillSelectReadOnlySearch() {
        val cases = listOf(
            "Знайди через MCP вакансії сервісанта в Legionowo",
            "знайди черз mcp вакансіі в Легіоново",
            "знайди через мср вакансії сервісанта",
            "пошукай чирез MCP роботу",
            "найди через mcp роботі",
            "знайди через МCP вакансії",
            "знайди через mсp вакансії"
        )
        BridgeCompatibility.withObserved("0.29") {
            cases.forEach { goal ->
                assertTrue(goal, McpSearchCue.searchRequested(goal))
                val profile = TaskIntentRouter.route(goal)
                assertEquals(goal, "mcp.search", profile.preflight?.tool)
                assertEquals(
                    goal, com.lumena.android.agent.core.ToolRisk.READ_ONLY,
                    ToolRegistry.get(profile.preflight!!.tool)?.risk
                )
                val query = profile.preflight?.args?.get("query").orEmpty()
                assertFalse(goal, query.contains("MCP", ignoreCase = true))
                assertFalse(goal, query.contains("мср"))
                assertTrue(goal, query.isNotBlank())
            }
        }
    }

    @Test fun negationsCannotTriggerMcpOrProduceMcpEvidenceRequirement() {
        BridgeCompatibility.withObserved("0.29") {
            listOf(
                "не шукай через MCP вакансії",
                "не використовуй MCP для пошуку",
                "пошукай альтернативу, тільки не через MCP",
                "Don't use MCP for search",
                "Nie używaj MCP do wyszukiwania"
            ).forEach { goal ->
                assertFalse(goal, McpSearchCue.searchRequested(goal))
                assertFalse(goal, TaskIntentRouter.route(goal).preflight?.tool == "mcp.search")
                assertNull(goal, McpModule.sourceEvidenceTools(goal))
            }
        }
    }

    @Test fun latestPositiveMcpClauseCanOverrideEarlierDenial() {
        val goal = "не шукай через MCP, а знайди через MCP вакансії"
        assertTrue(McpSearchCue.searchRequested(goal))
        assertEquals("вакансії", McpSearchCue.searchQuery(goal))
    }

    @Test fun protocolWithoutSearchSubjectMustNotIssueMcpCall() {
        BridgeCompatibility.withObserved("0.29") {
            val profile = TaskIntentRouter.route("мсп пошук")
            assertTrue(McpSearchCue.searchRequested("мсп пошук"))
            assertNull(profile.preflight)
        }
    }

    @Test fun typoCleaningLeavesActualSearchSubjectUnmodified() {
        val goal = "знайди черз мср ваквнсії в Легіоново"
        assertEquals("ваквнсії в Легіоново", McpSearchCue.searchQuery(goal))
    }
}
