package com.lumena.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextGenomePolicyTest {
    @Test
    fun irrelevantNoiseIsFilteredOut() {
        val units = listOf(
            GenomeMemoryUnit(
                id = "python",
                layer = GenomeLayer.ANCHOR,
                topicKey = "script:audio_test.py",
                text = "NEGATIVE unresolved python.run audio_test.py device unavailable",
                importance = 20.0,
                updatedAt = 2000L
            ),
            GenomeMemoryUnit(
                id = "git",
                layer = GenomeLayer.ANCHOR,
                topicKey = "@Lumena-Android",
                text = "POSITIVE verified git.status clean branch",
                importance = 15.0,
                updatedAt = 3000L
            )
        )

        val packet = ContextGenomePolicy.express(
            units = units,
            query = "debug python audio_test.py",
            maxChars = 1000,
            maxUnits = 8
        )

        assertTrue(packet.selectedIds.contains("python"))
        assertFalse(packet.selectedIds.contains("git"))
    }

    @Test
    fun unresolvedExactExperienceCanBeatBroadCapsule() {
        val units = listOf(
            GenomeMemoryUnit(
                id = "anchor",
                layer = GenomeLayer.ANCHOR,
                topicKey = "script:demo.py",
                text = "NEGATIVE unresolved python.run script=demo.py dependency missing",
                importance = 18.0,
                updatedAt = 4000L
            ),
            GenomeMemoryUnit(
                id = "topic",
                layer = GenomeLayer.TOPIC_CAPSULE,
                topicKey = "script:demo.py",
                text = "topic script demo.py with several historical outcomes",
                importance = 30.0,
                updatedAt = 3000L
            )
        )

        val packet = ContextGenomePolicy.express(
            units = units,
            query = "python demo.py dependency",
            maxChars = 1000,
            maxUnits = 1
        )

        assertEquals(listOf("anchor"), packet.selectedIds)
    }

    @Test
    fun broadQueryPrefersTopicLevelCompression() {
        val units = listOf(
            GenomeMemoryUnit(
                id = "topic",
                layer = GenomeLayer.TOPIC_CAPSULE,
                topicKey = "@Lumena-Android",
                text = "Lumena Android repository project state and recurring verified experience",
                importance = 20.0,
                updatedAt = 5000L
            ),
            GenomeMemoryUnit(
                id = "anchor",
                layer = GenomeLayer.ANCHOR,
                topicKey = "@Lumena-Android",
                text = "POSITIVE verified git.status cwd=@Lumena-Android clean",
                importance = 10.0,
                updatedAt = 5000L
            )
        )

        val packet = ContextGenomePolicy.express(
            units = units,
            query = "Lumena Android",
            maxChars = 1000,
            maxUnits = 1
        )

        assertEquals(listOf("topic"), packet.selectedIds)
    }

    @Test
    fun hardCharacterBudgetIsNeverExceeded() {
        val units = (0 until 20).map { index ->
            GenomeMemoryUnit(
                id = "u$index",
                layer = GenomeLayer.SIGNATURE_CAPSULE,
                topicKey = "python",
                text = "python memory $index " + "x".repeat(800),
                importance = index.toDouble(),
                updatedAt = index.toLong()
            )
        }

        for (limit in 0..1500) {
            val packet = ContextGenomePolicy.express(
                units = units,
                query = "python memory",
                maxChars = limit,
                maxUnits = 8
            )
            assertTrue(packet.usedChars <= limit)
            assertTrue(packet.lines.joinToString("\n").length <= limit)
        }
    }

    @Test
    fun evidenceIdsTravelWithSelectedMemory() {
        val packet = ContextGenomePolicy.express(
            units = listOf(
                GenomeMemoryUnit(
                    id = "a",
                    layer = GenomeLayer.ANCHOR,
                    topicKey = "model:ornith",
                    text = "ornith local model verified",
                    importance = 10.0,
                    updatedAt = 1L,
                    evidenceIds = listOf("e1", "e2")
                )
            ),
            query = "ornith model",
            maxChars = 500
        )

        assertEquals(listOf("e1", "e2"), packet.evidenceIds)
    }
}
