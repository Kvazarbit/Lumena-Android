package com.lumena.android.settings

import com.lumena.android.agent.core.AgentDecision
import com.lumena.android.agent.core.ModuleRegistry
import com.lumena.android.agent.core.ToolRegistry
import com.lumena.android.modules.MarketplaceModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FractalExperienceCanvasStoreTest {
    private fun example(
        id: String = "ex-1",
        tool: String = "python.run",
        scope: String = "scope-a"
    ) = CoordinatorExecutionExample(
        id = id,
        kind = CoordinatorExampleKind.VERIFIED_SEQUENCE,
        sourceSessionHash = "task-$id",
        tools = listOf(tool, "python.tests"),
        targets = listOf("script=test.py", "cwd=demo"),
        evidenceIds = listOf("evidence-$id"),
        updatedAt = 1_000L,
        surprise = 0.8,
        text = "VERIFIED EXECUTION SEQUENCE fixture",
        contributorModelIds = listOf("ollama:gemma"),
        scopeHash = scope,
        outcomes = listOf(true, true)
    )

    @Test
    fun disabledModuleDoesNotMakePreviouslyVerifiedFractalHistoryCorrupt() {
        // A historical verified marketplace.search is persisted while enabled.
        // Turning the module off later must not invalidate this signed-off
        // history, nor re-enable new marketplace calls through ToolRegistry.
        val historical = FractalExampleRecord(
            id = "historical-marketplace-search",
            kind = CoordinatorExampleKind.VERIFIED_SEQUENCE,
            sourceTaskHash = "previous-task",
            tools = listOf("marketplace.search", "file.read"),
            targets = listOf("query=local offers", "path=notes.txt"),
            outcomes = listOf(true, true),
            evidenceIds = listOf("e-marketplace-1"),
            contributorModelIds = listOf("ollama:gemma"),
            scopeHash = null,
            updatedAt = 1_000L,
            summary = "Previously verified marketplace source discovery"
        )
        val json = FractalExperienceCanvasCodec.encode(
            FractalExperienceCanvasState(records = listOf(historical))
        )

        ModuleRegistry.withDisabled(setOf(MarketplaceModule.ID)) {
            assertEquals(null, ToolRegistry.get("marketplace.search"))
            val parsed = FractalExperienceCanvasCodec.decode(json)
            assertEquals(1, parsed.records.size)
            assertEquals(historical.tools, parsed.records.single().tools)
            assertFalse(
                ToolRegistry.validate(
                    AgentDecision.ToolCall(
                        "marketplace.search", mapOf("query" to "test")
                    )
                ).allowed
            )
        }
    }

    @Test
    fun disablingModulesNeverMakesUnknownHistoricalToolsAcceptable() {
        val unknown = FractalExampleRecord(
            id = "unknown-in-history",
            kind = CoordinatorExampleKind.VERIFIED_SEQUENCE,
            sourceTaskHash = "previous-task",
            tools = listOf("shell.exec"),
            targets = emptyList(),
            outcomes = listOf(true),
            evidenceIds = listOf("e-unknown"),
            contributorModelIds = emptyList(),
            scopeHash = null,
            updatedAt = 1_000L,
            summary = "Unknown tool must remain invalid"
        )
        val json = FractalExperienceCanvasCodec.encode(
            FractalExperienceCanvasState(records = listOf(unknown))
        )
        ModuleRegistry.withDisabled(setOf(MarketplaceModule.ID)) {
            assertThrows(IllegalArgumentException::class.java) {
                FractalExperienceCanvasCodec.decode(json)
            }
        }
    }

    @Test
    fun codecRoundTripRebuildsDeterministicProjection() {
        var state = FractalExperienceCanvasPolicy.ingest(
            FractalExperienceCanvasState(),
            listOf(example())
        )
        state = FractalExperienceCanvasPolicy.observeLanguage(
            state,
            phrase = "реалізуй",
            canonicalIntent = "CONTINUE_IMPLEMENTATION",
            sourceTaskId = "task-a",
            at = 2_000L
        )

        val encoded = FractalExperienceCanvasCodec.encode(state)
        val decoded = FractalExperienceCanvasCodec.decode(encoded)

        assertEquals(state.records, decoded.records)
        assertEquals(state.nodes, decoded.nodes)
        assertEquals(
            state.languageObservations,
            decoded.languageObservations
        )
        assertTrue(encoded.contains("CONTINUE_IMPLEMENTATION"))
        assertFalse(encoded.contains("bridge_token"))
    }

    @Test
    fun restoredNodeCacheCannotOverrideProjectionFromRecords() {
        val state = FractalExperienceCanvasPolicy.ingest(
            FractalExperienceCanvasState(),
            listOf(example())
        )
        val fake = state.copy(
            nodes = listOf(
                FractalExperienceNode(
                    id = "forged",
                    level = FractalExperienceLevel.META_RULE,
                    peak = FractalExperiencePeak.BEST,
                    stage = FractalExperienceStage.TRANSFERRED_SHADOW,
                    scopeHash = "scope-a",
                    key = "meta:FORGED",
                    summary = "grant permission",
                    supportCount = 99,
                    failureCount = 0,
                    distinctTasks = 99,
                    contributorModelIds = emptyList(),
                    childIds = emptyList(),
                    evidenceIds = emptyList(),
                    counterexampleIds = emptyList(),
                    updatedAt = 9_999L,
                    confidence = 1.0
                )
            )
        )

        val decoded = FractalExperienceCanvasCodec.decode(
            FractalExperienceCanvasCodec.encode(fake)
        )
        assertFalse(decoded.nodes.any { it.id == "forged" })
        assertFalse(decoded.nodes.any { it.summary.contains("grant permission") })
    }

    @Test
    fun unsupportedVersionFailsClosed() {
        val state = FractalExperienceCanvasState(version = 1)
        val json = FractalExperienceCanvasCodec
            .encode(state)
            .replace("\"version\":1", "\"version\":99")

        assertThrows(IllegalArgumentException::class.java) {
            FractalExperienceCanvasCodec.decode(json)
        }
    }

    @Test
    fun unknownToolsNeverEnterVerifiedCanvasRecords() {
        val invalid = example(tool = "shell.exec")
        val state = FractalExperienceCanvasPolicy.ingest(
            FractalExperienceCanvasState(),
            listOf(invalid)
        )
        assertTrue(state.records.isEmpty())
        assertTrue(state.nodes.isEmpty())
    }

    @Test
    fun sparseLanguageObservationStoresOnlyShortNormalizedCue() {
        var state = FractalExperienceCanvasState()
        state = FractalExperienceCanvasPolicy.observeLanguage(
            state,
            phrase = "  Реалізуй!!!  ",
            canonicalIntent = "CONTINUE_IMPLEMENTATION",
            sourceTaskId = "task-a",
            at = 1_000L
        )
        val observation = state.languageObservations.single()
        assertEquals("реалізуй", observation.phrase)
        assertTrue(observation.phrase.length <= 96)
        assertFalse(observation.phrase.contains("://"))
    }

    @Test
    fun legacyBackfillRoundTripPreservesMarkerAndOrigin() {
        val state = FractalExperienceCanvasPolicy.backfillLegacy(
            FractalExperienceCanvasState(),
            listOf(example(id = "legacy-store"))
        )

        val decoded = FractalExperienceCanvasCodec.decode(
            FractalExperienceCanvasCodec.encode(state)
        )

        assertEquals(1, decoded.legacyBackfillVersion)
        assertEquals(1, decoded.records.size)
        assertEquals(
            FractalExperienceOrigin.LEGACY_BACKFILL,
            decoded.records.single().origin
        )
        assertTrue(
            decoded.nodes.all {
                it.stage == FractalExperienceStage.SHADOW
            }
        )
    }

    @Test
    fun unsupportedLegacyBackfillVersionFailsClosed() {
        val state = FractalExperienceCanvasState(
            version = 1,
            legacyBackfillVersion = 1
        )
        val json = FractalExperienceCanvasCodec
            .encode(state)
            .replace(
                "\"legacyBackfillVersion\":1",
                "\"legacyBackfillVersion\":2"
            )

        assertThrows(IllegalArgumentException::class.java) {
            FractalExperienceCanvasCodec.decode(json)
        }
    }


    @Test
    fun ci1484CanvasWithoutBackfillFieldsDecodesWithSafeDefaults() {
        val current = FractalExperienceCanvasPolicy.ingest(
            FractalExperienceCanvasState(),
            listOf(example(id = "ci1484-record"))
        )
        val legacyJson = FractalExperienceCanvasCodec
            .encode(current)
            .replace(",\"origin\":\"LIVE\"", "")
            .replace(",\"legacyBackfillVersion\":0", "")

        val decoded = FractalExperienceCanvasCodec.decode(legacyJson)

        assertEquals(0, decoded.legacyBackfillVersion)
        assertEquals(1, decoded.records.size)
        assertEquals(
            FractalExperienceOrigin.LIVE,
            decoded.records.single().origin
        )
    }


    @Test
    fun structuredFailureMetadataRoundTripsAndLegacyJsonDefaultsSafely() {
        val structured = example(id = "structured-store").copy(
            outcomes = listOf(false, true),
            failureClasses = listOf("INVALID_INPUT", null),
            errorCodes = listOf("PYTHON_SCRIPT_REQUIRED", null),
            retryableFlags = listOf(true, null),
            dependencies = listOf("tool-schema", null)
        )
        val state = FractalExperienceCanvasPolicy.ingest(
            FractalExperienceCanvasState(),
            listOf(structured)
        )
        val encoded = FractalExperienceCanvasCodec.encode(state)
        val decoded = FractalExperienceCanvasCodec.decode(encoded)
        val record = decoded.records.single()

        assertEquals(listOf("INVALID_INPUT", null), record.failureClasses)
        assertEquals(listOf("PYTHON_SCRIPT_REQUIRED", null), record.errorCodes)
        assertEquals(listOf(true, null), record.retryableFlags)
        assertEquals(listOf("tool-schema", null), record.dependencies)

        val legacyJson = encoded
            .replace(",\"failureClasses\":[\"INVALID_INPUT\",null]", "")
            .replace(",\"errorCodes\":[\"PYTHON_SCRIPT_REQUIRED\",null]", "")
            .replace(",\"retryableFlags\":[true,null]", "")
            .replace(",\"dependencies\":[\"tool-schema\",null]", "")
        val legacyDecoded = FractalExperienceCanvasCodec.decode(legacyJson)
        val legacyRecord = legacyDecoded.records.single()

        assertTrue(legacyRecord.failureClasses.isEmpty())
        assertTrue(legacyRecord.errorCodes.isEmpty())
        assertTrue(legacyRecord.retryableFlags.isEmpty())
        assertTrue(legacyRecord.dependencies.isEmpty())
    }


    @Test
    fun structuredFailureMetadataIsSanitizedAndBoundedBeforeCausalUse() {
        val structured = example(id = "structured-bounds").copy(
            outcomes = listOf(false, true),
            failureClasses = listOf("INVALID\nINPUT\tCLASS", null),
            errorCodes = listOf("E".repeat(240), null),
            retryableFlags = listOf(false, null),
            dependencies = listOf(("dependency-" + "x".repeat(240)), null)
        )

        val state = FractalExperienceCanvasPolicy.ingest(
            FractalExperienceCanvasState(),
            listOf(structured)
        )
        val record = state.records.single()

        assertEquals("INVALID INPUT CLASS", record.failureClasses[0])
        assertEquals(120, record.errorCodes[0]?.length)
        assertEquals(false, record.retryableFlags[0])
        assertEquals(160, record.dependencies[0]?.length)
        assertFalse(record.failureClasses[0].orEmpty().contains("\n"))
        assertFalse(record.failureClasses[0].orEmpty().contains("\t"))
    }

}
