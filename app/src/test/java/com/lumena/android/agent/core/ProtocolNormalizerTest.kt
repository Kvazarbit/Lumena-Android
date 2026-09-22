package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolNormalizerTest {
    private val normalizer = ProtocolNormalizer()
    private val parser = AgentResponseParser()

    private fun canonical(raw: String): NormalizationResult.Canonical {
        val result = normalizer.normalize(raw)
        assertTrue("Expected canonical result, got $result", result is NormalizationResult.Canonical)
        return result as NormalizationResult.Canonical
    }

    @Test
    fun canonicalToolRemainsExecutableWithoutProtocolRetry() {
        val normalized = canonical(
            """{"tool":"web.search","args":{"query":"BTC"},"reason":"Find evidence"}"""
        )

        val parsed = parser.parse(normalized.json)
        assertTrue(parsed is AgentDecision.ToolCall)
        parsed as AgentDecision.ToolCall
        assertEquals("web.search", parsed.tool)
        assertEquals("BTC", parsed.args["query"])
    }

    @Test
    fun actionReplyIsNormalizedLocally() {
        val normalized = canonical(
            """{"action":"reply","result":"Привіт"}"""
        )

        assertEquals(NormalizationRule.ACTION_REPLY, normalized.rule)
        assertTrue(normalized.changed)
        assertEquals(AgentDecision.Reply("Привіт"), parser.parse(normalized.json))
    }

    @Test
    fun actionDoneAndPartialBecomeCanonicalDecisions() {
        val done = canonical("""{"action":"done","result":"Verified"}""")
        val partial = canonical("""{"action":"partial","result":"Blocked"}""")

        assertEquals(NormalizationRule.ACTION_DONE, done.rule)
        assertEquals(NormalizationRule.ACTION_PARTIAL, partial.rule)
        assertEquals(AgentDecision.Done("Verified"), parser.parse(done.json))
        assertEquals(AgentDecision.Partial("Blocked"), parser.parse(partial.json))
    }

    @Test
    fun registeredActionAliasWithParametersBecomesRegisteredToolOnly() {
        val normalized = canonical(
            """{"action":"web_search","parameters":{"query":"latest Poland news","limit":3}}"""
        )

        assertEquals(NormalizationRule.REGISTERED_ACTION_ALIAS, normalized.rule)
        val parsed = parser.parse(normalized.json)
        assertTrue(parsed is AgentDecision.ToolCall)
        parsed as AgentDecision.ToolCall
        assertEquals("web.search", parsed.tool)
        assertEquals("latest Poland news", parsed.args["query"])
        assertEquals("3.0", parsed.args["limit"])
        assertTrue(ToolRegistry.validate(parsed).allowed)
    }

    @Test
    fun actionToolUsesExplicitRegisteredTool() {
        val normalized = canonical(
            """{"action":"tool","tool":"file.read","args":{"path":"README.md"}}"""
        )

        assertEquals(NormalizationRule.ACTION_TOOL, normalized.rule)
        val parsed = parser.parse(normalized.json) as AgentDecision.ToolCall
        assertEquals("file.read", parsed.tool)
        assertEquals("README.md", parsed.args["path"])
    }

    @Test
    fun hermesEnvelopeIsNormalizedWithoutProseExecution() {
        val normalized = canonical(
            """<tool_call>{"name":"file.read","arguments":{"path":"README.md"}}</tool_call>"""
        )

        assertEquals(NormalizationRule.HERMES_TOOL_CALL, normalized.rule)
        val parsed = parser.parse(normalized.json) as AgentDecision.ToolCall
        assertEquals("file.read", parsed.tool)
        assertEquals("README.md", parsed.args["path"])
    }

    @Test
    fun fencedJsonIsStrippedBeforeStrictParser() {
        val normalized = canonical(
            """```json
{"tool":"git.status","args":{"cwd":"@Lumena-Android"}}
```"""
        )

        assertTrue(normalized.changed)
        val parsed = parser.parse(normalized.json) as AgentDecision.ToolCall
        assertEquals("git.status", parsed.tool)
        assertEquals("@Lumena-Android", parsed.args["cwd"])
    }

    @Test
    fun singleRegisteredToolShorthandIsCanonicalized() {
        val normalized = canonical(
            """{"web.search":{"query":"news"}}"""
        )

        assertEquals(NormalizationRule.REGISTERED_SINGLE_KEY_TOOL, normalized.rule)
        val parsed = parser.parse(normalized.json) as AgentDecision.ToolCall
        assertEquals("web.search", parsed.tool)
        assertEquals("news", parsed.args["query"])
    }

    @Test
    fun jsonQuotedInsideOrdinaryProseStaysPlainText() {
        val raw =
            """Example only: {"tool":"file.write","args":{"path":"x","content":"bad"}} do not run it."""

        val result = normalizer.normalize(raw)

        assertTrue(result is NormalizationResult.PlainText)
        assertEquals(raw, (result as NormalizationResult.PlainText).text)
    }

    @Test
    fun unknownActionCannotSmuggleRegisteredMutation() {
        val result = normalizer.normalize(
            """{"action":"shell","tool":"file.write","args":{"path":"x","content":"bad"}}"""
        )

        assertTrue(result is NormalizationResult.Failure)
        result as NormalizationResult.Failure
        assertEquals(ProtocolFailureKind.UNKNOWN_ACTION, result.kind)
        assertTrue(result.reason.contains("shell"))
    }

    @Test
    fun twoToolShorthandIsAmbiguousAndExecutesNothing() {
        val result = normalizer.normalize(
            """{"web.search":{"query":"news"},"file.read":{"path":"README.md"}}"""
        )

        assertTrue(result is NormalizationResult.Failure)
        assertEquals(
            ProtocolFailureKind.AMBIGUOUS,
            (result as NormalizationResult.Failure).kind
        )
    }

    @Test
    fun malformedProtocolJsonIsFailureNotPlainReply() {
        val result = normalizer.normalize(
            """{"tool":"web.search","args":{"query":"""
        )

        assertTrue(result is NormalizationResult.Failure)
        assertEquals(
            ProtocolFailureKind.SYNTAX,
            (result as NormalizationResult.Failure).kind
        )
    }

    @Test
    fun unknownToolCannotBeCreatedByNormalizer() {
        val result = normalizer.normalize(
            """{"tool":"shell.exec","args":{"cmd":"rm -rf /"}}"""
        )

        assertTrue(result is NormalizationResult.Failure)
        assertEquals(
            ProtocolFailureKind.UNKNOWN_ACTION,
            (result as NormalizationResult.Failure).kind
        )
    }

    @Test
    fun invalidArgumentsStringDoesNotTurnIntoEmptyExecutableArgs() {
        val result = normalizer.normalize(
            """{"name":"web.search","arguments":"not-json"}"""
        )

        assertTrue(result is NormalizationResult.Failure)
        assertEquals(
            ProtocolFailureKind.SYNTAX,
            (result as NormalizationResult.Failure).kind
        )
    }

    @Test
    fun plainConversationRemainsPlainText() {
        val result = normalizer.normalize("Привіт, як справи?")

        assertTrue(result is NormalizationResult.PlainText)
        assertFalse(result is NormalizationResult.Canonical)
    }
}
