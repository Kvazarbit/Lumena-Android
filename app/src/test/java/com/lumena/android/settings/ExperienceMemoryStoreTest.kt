package com.lumena.android.settings

import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ExperienceMemoryStoreTest {
    @Test
    fun failureCreatesNegativeAnchorAndSuccessResolvesIt() {
        val request = ToolRequest(
            tool = "python.run",
            args = mapOf("script" to "demo.py")
        )

        val failed = ExperienceMemoryIndex.record(
            ExperienceMemoryState(),
            request,
            ToolResult(
                ok = false,
                tool = "python.run",
                stderr = "ModuleNotFoundError: No module named requests"
            ),
            now = 1000L
        )

        assertEquals(1, failed.anchors.size)
        assertEquals(ExperienceValence.NEGATIVE, failed.anchors.single().valence)
        assertEquals(null, failed.anchors.single().resolvedAt)

        val recovered = ExperienceMemoryIndex.record(
            failed,
            request,
            ToolResult(
                ok = true,
                tool = "python.run",
                exitCode = 0,
                stdout = "SUCCESS"
            ),
            now = 2000L
        )

        val negative = recovered.anchors.first { it.valence == ExperienceValence.NEGATIVE }
        val positive = recovered.anchors.first { it.valence == ExperienceValence.POSITIVE }

        assertEquals(2000L, negative.resolvedAt)
        assertTrue(positive.summary.contains("SUCCESS"))
    }

    @Test
    fun recoveredFailureRemainsHistoricalButPositiveRanksFirst() {
        val request = ToolRequest(
            tool = "python.run",
            args = mapOf("script" to "demo.py")
        )
        var state = ExperienceMemoryIndex.record(
            ExperienceMemoryState(),
            request,
            ToolResult(ok = false, tool = "python.run", error = "dependency missing"),
            now = 1000L
        )
        state = ExperienceMemoryIndex.record(
            state,
            request,
            ToolResult(ok = true, tool = "python.run", stdout = "verified"),
            now = 2000L
        )

        val relevant = ExperienceMemoryIndex.relevant(
            state,
            query = "python demo.py",
            limit = 2
        )

        assertTrue(relevant.first().startsWith("POSITIVE verified"))
        assertTrue(relevant.any { it.startsWith("NEGATIVE resolved") })
    }

    @Test
    fun memoryIsHardBoundedTo128Anchors() {
        var state = ExperienceMemoryState()
        repeat(150) { index ->
            state = ExperienceMemoryIndex.record(
                state,
                ToolRequest(
                    tool = "file.read",
                    args = mapOf("path" to "file-$index.txt")
                ),
                ToolResult(
                    ok = index % 2 == 0,
                    tool = "file.read",
                    stdout = "ok-$index",
                    error = if (index % 2 == 0) null else "missing-$index"
                ),
                now = index.toLong()
            )
        }

        assertEquals(128, state.anchors.size)
    }

    @Test
    fun unrelatedExperienceIsNotInjectedIntoAQuery() {
        var state = ExperienceMemoryState()
        state = ExperienceMemoryIndex.record(
            state,
            ToolRequest("git.status", mapOf("cwd" to "@Lumena-Android")),
            ToolResult(ok = true, tool = "git.status", stdout = "clean branch"),
            now = 1000L
        )
        state = ExperienceMemoryIndex.record(
            state,
            ToolRequest("python.run", mapOf("script" to "audio_test.py")),
            ToolResult(ok = false, tool = "python.run", error = "audio device unavailable"),
            now = 2000L
        )

        val relevant = ExperienceMemoryIndex.relevant(
            state,
            query = "debug python audio_test.py",
            limit = 8
        )

        assertTrue(relevant.any { it.contains("python.run") })
        assertFalse(relevant.any { it.contains("git.status") })
    }

    @Test
    fun changedPositiveSummaryUpdatesSameAnchor() {
        val request = ToolRequest(
            tool = "git.status",
            args = mapOf("cwd" to "@Lumena-Android")
        )
        var state = ExperienceMemoryIndex.record(
            ExperienceMemoryState(),
            request,
            ToolResult(ok = true, tool = "git.status", stdout = "## feature/a"),
            now = 1000L
        )
        state = ExperienceMemoryIndex.record(
            state,
            request,
            ToolResult(ok = true, tool = "git.status", stdout = "## feature/b"),
            now = 2000L
        )

        val positives = state.anchors.filter { it.valence == ExperienceValence.POSITIVE }
        assertEquals(1, positives.size)
        assertEquals(2, positives.single().occurrences)
        assertTrue(positives.single().summary.contains("feature/b"))
    }

    @Test
    fun imageSearchAnchorsAreKeyedByQuery() {
        var state = ExperienceMemoryState()
        state = ExperienceMemoryIndex.record(
            state,
            ToolRequest(
                "image.search",
                mapOf("query" to "woman portrait")
            ),
            ToolResult(
                ok = true,
                tool = "image.search",
                stdout = "display ready"
            ),
            now = 1000L
        )
        state = ExperienceMemoryIndex.record(
            state,
            ToolRequest(
                "image.search",
                mapOf("query" to "mountain landscape")
            ),
            ToolResult(
                ok = true,
                tool = "image.search",
                stdout = "display ready"
            ),
            now = 2000L
        )

        val positives = state.anchors.filter {
            it.valence == ExperienceValence.POSITIVE &&
                it.tool == "image.search"
        }
        assertEquals(2, positives.size)
        assertTrue(positives.any { it.target.contains("woman portrait") })
        assertTrue(positives.any { it.target.contains("mountain landscape") })
    }

    @Test
    fun repeatedVerifiedSuccessCompactsIntoOccurrences() {
        val request = ToolRequest(
            tool = "git.status",
            args = mapOf("cwd" to "@Lumena-Android")
        )
        var state = ExperienceMemoryState()

        repeat(3) { index ->
            state = ExperienceMemoryIndex.record(
                state,
                request,
                ToolResult(
                    ok = true,
                    tool = "git.status",
                    stdout = "## feature/embedded-llamacpp-v0.12"
                ),
                now = 1000L + index
            )
        }

        val positive = state.anchors.single { it.valence == ExperienceValence.POSITIVE }
        assertEquals(3, positive.occurrences)
    }

    @Test
    fun relevantMemoryPrioritizesMatchingExperience() {
        var state = ExperienceMemoryState()
        state = ExperienceMemoryIndex.record(
            state,
            ToolRequest("git.status", mapOf("cwd" to "@Lumena-Android")),
            ToolResult(ok = true, tool = "git.status", stdout = "clean branch"),
            now = 1000L
        )
        state = ExperienceMemoryIndex.record(
            state,
            ToolRequest("python.run", mapOf("script" to "audio_test.py")),
            ToolResult(ok = false, tool = "python.run", error = "audio device unavailable"),
            now = 2000L
        )

        val relevant = ExperienceMemoryIndex.relevant(
            state,
            query = "check python audio_test.py failure",
            limit = 1
        )

        assertEquals(1, relevant.size)
        assertTrue(relevant.single().contains("python.run"))
        assertTrue(relevant.single().contains("NEGATIVE"))
    }

    @Test
    fun anchorsSurviveJsonDiskRoundTrip() {
        val dir = Files.createTempDirectory("lumena-memory-test").toFile()
        val file = File(dir, "memory.json")
        try {
            val original = ExperienceMemoryState(
                anchors = listOf(
                    ExperienceAnchor(
                        id = "a1",
                        signature = "sig",
                        tool = "python.run",
                        target = "script=demo.py",
                        valence = ExperienceValence.NEGATIVE,
                        summary = "failure: missing dependency",
                        occurrences = 2,
                        firstSeenAt = 1000L,
                        lastSeenAt = 2000L,
                        resolvedAt = 3000L
                    )
                )
            )

            ExperienceMemoryFileCodec.save(file, original)
            val loaded = ExperienceMemoryFileCodec.load(file)

            assertEquals(original, loaded)
            assertTrue(file.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun modelContentIsNotPartOfAnchorTargetAndSummaryIsBounded() {
        val huge = "x".repeat(5000)
        val state = ExperienceMemoryIndex.record(
            ExperienceMemoryState(),
            ToolRequest(
                tool = "file.write",
                args = mapOf(
                    "path" to "demo.txt",
                    "content" to huge
                )
            ),
            ToolResult(
                ok = false,
                tool = "file.write",
                error = "failed\n" + huge
            ),
            now = 1L
        )

        val anchor = state.anchors.single()
        assertTrue(anchor.target.contains("path=demo.txt"))
        assertFalse(anchor.target.contains("xxxxx"))
        assertTrue(anchor.summary.length <= 429)
        assertFalse(anchor.summary.contains("\n"))
    }
}
