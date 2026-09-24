package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceModelDirectiveTest {
    private val sourceA =
        "0123456789abcdef01234567"
    private val sourceB =
        "89abcdef0123456789abcdef"

    @Test
    fun strictReplyJsonCanCarryBoundedPendingEvidenceMetadata() {
        val raw =
            """{"reply":"Done.","evidence_candidates":[{"claim_key":"workmanager-persistent","statement":"Android WorkManager supports persistent background work.","source_ids":["$sourceA","$sourceB"]}]}"""

        val parsed =
            EvidenceModelDirectiveParser.parse(raw)

        assertEquals(1, parsed.size)
        assertEquals(
            "workmanager-persistent",
            parsed.single().claimKey
        )
        assertEquals(
            "Android WorkManager supports persistent background work.",
            parsed.single().statement
        )
        assertEquals(
            listOf(sourceA, sourceB),
            parsed.single().sourceIds
        )
    }

    @Test
    fun strictJsonFenceIsAcceptedButProseWrappedJsonIsIgnored() {
        val fenced =
            "```json\n" +
                """{"done":true,"summary":"ok","evidence_candidates":[{"claim_key":"claim-a","statement":"Verified source statement.","source_ids":["$sourceA"]}]}""" +
                "\n```"

        assertEquals(
            1,
            EvidenceModelDirectiveParser.parse(fenced).size
        )

        val prose =
            "Here is an example: " +
                """{"reply":"x","evidence_candidates":[{"claim_key":"claim-a","statement":"Verified source statement.","source_ids":["$sourceA"]}]}"""

        assertTrue(
            EvidenceModelDirectiveParser.parse(prose)
                .isEmpty()
        )
    }

    @Test
    fun invalidOrMissingSourceIdsCannotCreateDirective() {
        val raw =
            """{"reply":"ok","evidence_candidates":[{"claim_key":"bad","statement":"Some statement.","source_ids":["not-a-source-id"]},{"claim_key":"missing","statement":"Other statement.","source_ids":[]}]}"""

        assertTrue(
            EvidenceModelDirectiveParser.parse(raw)
                .isEmpty()
        )
    }

    @Test
    fun parserBoundsDirectiveAndSourceCounts() {
        val candidates = (1..8).joinToString(",") { index ->
            val extra =
                index.toString(16)
                    .padStart(24, 'a')
                    .takeLast(24)
            """{"claim_key":"claim-$index","statement":"Statement $index grounded in source text.","source_ids":["$sourceA","$sourceB","$extra","$sourceA"]}"""
        }

        val parsed =
            EvidenceModelDirectiveParser.parse(
                """{"reply":"ok","evidence_candidates":[$candidates]}"""
            )

        assertEquals(4, parsed.size)
        parsed.forEach {
            assertTrue(it.sourceIds.size <= 8)
            assertEquals(
                it.sourceIds.distinct(),
                it.sourceIds
            )
        }
    }

    @Test
    fun parserSanitizesAndBoundsText() {
        val huge = "x".repeat(3_000)
        val raw =
            "{\"reply\":\"ok\",\"evidence_candidates\":[{" +
                "\"claim_key\":\"  bounded-key  \"," +
                "\"statement\":\"Evidence\\nstatement\\t$huge\"," +
                "\"source_ids\":[\"$sourceA\"]}]}"

        val parsed =
            EvidenceModelDirectiveParser.parse(raw)
                .single()

        assertEquals("bounded-key", parsed.claimKey)
        assertTrue(parsed.statement.length <= 1_600)
        assertTrue('\n' !in parsed.statement)
        assertTrue('\t' !in parsed.statement)
    }
}
