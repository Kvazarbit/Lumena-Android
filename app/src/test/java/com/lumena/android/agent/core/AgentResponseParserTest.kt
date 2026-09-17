package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentResponseParserTest {
    private val parser = AgentResponseParser()

    @Test
    fun plainTextBecomesReply() {
        val parsed = parser.parse("Hello there")
        assertEquals(AgentDecision.Reply("Hello there"), parsed)
    }

    @Test
    fun strictToolJsonBecomesToolCall() {
        val parsed = parser.parse(
            """{"tool":"file.read","args":{"path":"README.md"},"reason":"Inspect project"}"""
        )
        assertTrue(parsed is AgentDecision.ToolCall)
        parsed as AgentDecision.ToolCall
        assertEquals("file.read", parsed.tool)
        assertEquals("README.md", parsed.args["path"])
        assertEquals("Inspect project", parsed.reason)
    }

    @Test
    fun wrappedJsonIsExtractedWithoutGuessing() {
        val parsed = parser.parse(
            """I need one tool.\n{"tool":"git.status","args":{"cwd":"btc"},"reason":"Check state"}\nWaiting."""
        )
        assertTrue(parsed is AgentDecision.ToolCall)
        parsed as AgentDecision.ToolCall
        assertEquals("git.status", parsed.tool)
        assertEquals("btc", parsed.args["cwd"])
    }

    @Test
    fun doneJsonBecomesDone() {
        val parsed = parser.parse("""{"done":true,"summary":"Tests passed"}""")
        assertEquals(AgentDecision.Done("Tests passed"), parsed)
    }

    @Test
    fun malformedJsonFallsBackToReply() {
        val text = "```json {tool:file.read} ```"
        assertEquals(AgentDecision.Reply(text), parser.parse(text))
    }
}
