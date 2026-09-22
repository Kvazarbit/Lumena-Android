package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentResponseParserTest {
    private val parser = AgentResponseParser()

    @Test fun singleRegisteredToolKeyIsNormalized() {
        val call = parser.parse("""{"web.search":{"query":"news"}}""") as AgentDecision.ToolCall
        assertEquals("web.search", call.tool)
        assertEquals("news", call.args["query"])
    }

    @Test fun ambiguousOrUnknownShorthandIsNotExecuted() {
        listOf("""{"web.search":{},"file.read":{}}""",
            """{"unknown.run":{}}""", """{"web.search":"news"}""",
            """{"web.search":{},"reason":"example"}""").forEach {
            assertTrue(parser.parse(it) is AgentDecision.Reply)
        }
    }

    @Test
    fun plainTextBecomesReply() {
        val parsed = parser.parse("Hello there")
        assertEquals(AgentDecision.Reply("Hello there"), parsed)
    }

    @Test
    fun strictToolJsonBecomesToolCall() {
        val parsed = parser.parse(
            """{"plan":["inspect","verify"],"tool":"file.read","args":{"path":"README.md"},"reason":"Inspect project"}"""
        )
        assertTrue(parsed is AgentDecision.ToolCall)
        parsed as AgentDecision.ToolCall
        assertEquals("file.read", parsed.tool)
        assertEquals("README.md", parsed.args["path"])
        assertEquals("Inspect project", parsed.reason)
        assertEquals(listOf("inspect", "verify"), parsed.plan)
    }

    @Test
    fun nestedBatchArgsStayValidJson() {
        val parsed = parser.parse(
            """{"tool":"inspect.batch","args":{"requests":[{"tool":"file.read","args":{"path":"README.md"}},{"tool":"system.info","args":{}}]},"reason":"Inspect in one round trip"}"""
        )
        assertTrue(parsed is AgentDecision.ToolCall)
        parsed as AgentDecision.ToolCall
        val requests = parsed.args["requests"].orEmpty()
        assertTrue(requests.startsWith("["))
        assertTrue(requests.contains("\"tool\":\"file.read\""))
        assertTrue(requests.contains("\"path\":\"README.md\""))
        assertTrue(requests.contains("\"tool\":\"system.info\""))
    }

    @Test
    fun arbitraryJsonQuotedInProseIsNotExecuted() {
        val text = """Example only: {"tool":"git.status","args":{"cwd":"btc"}} do not run it."""
        assertEquals(AgentDecision.Reply(text), parser.parse(text))
    }

    @Test
    fun fencedJsonIsAccepted() {
        val parsed = parser.parse(
            """```json
{"tool":"git.status","args":{"cwd":"btc"},"reason":"Check state"}
```"""
        )
        assertTrue(parsed is AgentDecision.ToolCall)
        parsed as AgentDecision.ToolCall
        assertEquals("git.status", parsed.tool)
        assertEquals("btc", parsed.args["cwd"])
    }

    @Test
    fun hermesToolEnvelopeIsAccepted() {
        val parsed = parser.parse(
            """<tool_call>{"name":"file.read","arguments":{"path":"a.txt"}}</tool_call>"""
        )
        assertTrue(parsed is AgentDecision.ToolCall)
        parsed as AgentDecision.ToolCall
        assertEquals("file.read", parsed.tool)
        assertEquals("a.txt", parsed.args["path"])
    }

    @Test
    fun actionReplyResultEnvelopeBecomesReply() {
        val parsed = parser.parse(
            """```json
{"action":"reply","result":"Привіт із локальної моделі"}
```"""
        )
        assertEquals(AgentDecision.Reply("Привіт із локальної моделі"), parsed)
    }

    @Test
    fun actionDoneAndPartialResultEnvelopesAreNormalized() {
        assertEquals(
            AgentDecision.Done("Verified result"),
            parser.parse("""{"action":"done","result":"Verified result"}""")
        )
        assertEquals(
            AgentDecision.Partial("Only partial evidence"),
            parser.parse("""{"action":"partial","result":"Only partial evidence"}""")
        )
    }

    @Test
    fun unknownActionIsNeverExecutedAsTool() {
        val parsed = parser.parse(
            """{"action":"shell","tool":"file.write","args":{"path":"x","content":"bad"}}"""
        )
        assertTrue(parsed is AgentDecision.Reply)
    }

    @Test
    fun doneJsonBecomesDone() {
        val parsed = parser.parse("""{"done":true,"summary":"Tests passed"}""")
        assertEquals(AgentDecision.Done("Tests passed"), parsed)
    }

    @Test
    fun replyJsonBecomesReply() {
        val parsed = parser.parse("""{"reply":"Привіт"}""")
        assertEquals(AgentDecision.Reply("Привіт"), parsed)
    }

    @Test
    fun malformedJsonFallsBackToReply() {
        val text = "```json {tool:file.read} ```"
        assertEquals(AgentDecision.Reply(text), parser.parse(text))
    }
}
