package com.lumena.android.agent.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A new APK can meet an old Termux bridge. Lumena must not route to tools
 * the installed bridge does not have, and must say why.
 */
class BridgeCompatibilityTest {
    private val olxGoal = "Знайди на OLX оголошення про роботу в Legionowo"
    private val mcpGoal = "знайди через MCP вакансії сервісанта в Legionowo"

    private val health027 = """
        Lumena bridge OK
        workspace=/data/data/com.termux/files/home/lumena-workspace
        read_only_roots=(none)
        read_roots_config=/data/data/com.termux/files/home/.lumena/read_roots.conf
        version=0.27
        bridge_run_id=abc123
        last_web_search_status=(none)
    """.trimIndent()

    @Test fun versionIsReadFromHealthOutput() {
        assertEquals("0.27", BridgeCompatibility.versionFromHealth(health027))
        assertNull(BridgeCompatibility.versionFromHealth("Lumena bridge OK\nworkspace=/x\n"))
        assertNull(BridgeCompatibility.versionFromHealth("legacy_backfill_version=1\n"))
    }

    @Test fun versionsCompareBySegment() {
        assertTrue(BridgeCompatibility.compare("0.29", "0.28")!! > 0)
        assertTrue(BridgeCompatibility.compare("0.30", "0.29")!! > 0)
        assertTrue(BridgeCompatibility.compare("0.3", "0.29")!! < 0)
        assertEquals(0, BridgeCompatibility.compare("0.29", "0.29.0"))
        assertTrue(BridgeCompatibility.compare("1.0", "0.29")!! > 0)
        assertNull(BridgeCompatibility.compare("abc", "0.29"))
    }

    @Test fun unknownBridgeBlocksUntilHealthAndObservedOldBridgeDoes() {
        BridgeCompatibility.withObserved(null) {
            assertFalse(BridgeCompatibility.satisfies("0.29"))
        }
        BridgeCompatibility.withObserved("0.27") {
            assertFalse(BridgeCompatibility.satisfies("0.28"))
            assertTrue(BridgeCompatibility.satisfies(null))
        }
    }

    @Test fun unknownBridgeDoesNotOfferExternalMcpOrMarketplaceTools() {
        BridgeCompatibility.withObserved(null) {
            assertFalse(ModuleRegistry.isEnabled("mcp"))
            assertFalse(ModuleRegistry.isEnabled("marketplace"))
            assertNull(ToolRegistry.get("mcp.search"))
            assertNull(ToolRegistry.get("marketplace.search"))
            assertTrue(ModuleRegistry.isEnabled("listing-attention"))
        }
    }

    @Test fun garbageNeverOverwritesAnObservedVersion() {
        BridgeCompatibility.withObserved("0.29") {
            BridgeCompatibility.observe("not-a-version")
            BridgeCompatibility.observe(null)
            assertEquals("0.29", BridgeCompatibility.observedVersion())
        }
    }

    @Test fun oldBridgeDisablesModulesWhoseToolsItLacks() {
        BridgeCompatibility.withObserved("0.27") {
            assertFalse(ModuleRegistry.isEnabled("marketplace"))
            assertFalse(ModuleRegistry.isEnabled("mcp"))
            assertTrue(ModuleRegistry.isEnabled("listing-attention"))
            assertNull(ToolRegistry.get("marketplace.search"))
            assertNull(ToolRegistry.get("mcp.search"))
            assertTrue(TaskIntentRouter.route(olxGoal).preflight?.tool != "marketplace.search")
            val marketplace = ModuleRegistry.all().first { it.manifest.id == "marketplace" }
            val reason = ModuleRegistry.unavailableReason(marketplace)
            assertNotNull(reason)
            assertTrue(reason!!.contains("0.28") && reason.contains("0.27"))
        }
    }

    @Test fun bridge028RunsMarketplaceButNotMcp() {
        BridgeCompatibility.withObserved("0.28") {
            assertTrue(ModuleRegistry.isEnabled("marketplace"))
            assertFalse(ModuleRegistry.isEnabled("mcp"))
            val olx = TaskIntentRouter.route(olxGoal)
            assertEquals("marketplace.search", olx.preflight?.tool)
            assertFalse("mcp.search" in olx.recommendedTools)
            assertTrue(TaskIntentRouter.route(mcpGoal).preflight?.tool != "mcp.search")
        }
    }

    @Test fun currentBridgeRunsEveryModule() {
        BridgeCompatibility.withObserved("0.29") {
            assertEquals(ModuleRegistry.all().size, ModuleRegistry.enabled().size)
            assertEquals("mcp.search", TaskIntentRouter.route(mcpGoal).preflight?.tool)
            ModuleRegistry.all().forEach { assertNull(ModuleRegistry.unavailableReason(it)) }
        }
    }

    @Test fun noModuleRequiresABridgeNewerThanTheShippedOne() {
        val appDir = listOf(File("."), File("app"))
            .map { it.canonicalFile }
            .first { File(it, "src/main/java").isDirectory }
        val bridge = File(requireNotNull(appDir.parentFile), "termux/bridge.py").readText()
        val shipped = requireNotNull(Regex("""version=(\d+(?:\.\d+)+)\\n""").find(bridge)).groupValues[1]
        ModuleRegistry.all().mapNotNull { it.manifest.requiresBridge }.forEach { required ->
            assertTrue("module needs $required, repo bridge is $shipped", BridgeCompatibility.compare(shipped, required)!! >= 0)
        }
    }
}
