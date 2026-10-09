package com.lumena.android.agent.core

import com.lumena.android.settings.StateArchive
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Charter ART-14: a module adds capabilities, never authority, and the
 * kernel works with every module disabled.
 */
class ModuleKernelBoundaryTest {
    @org.junit.Before fun installKnownBridgeForTest() { BridgeCompatibility.observe("0.29") }
    @org.junit.After fun clearBridgeAfterTest() { BridgeCompatibility.clearForTests() }

    private val olxGoal = "Знайди на OLX оголошення про роботу в Legionowo"
    private val mcpGoal = "знайди через MCP вакансії сервісанта в Legionowo"
    private val webGoal = "знайди в інтернеті новини про Gemma"

    private val allIds = ModuleRegistry.all().map { it.manifest.id }.toSet()
    private val moduleToolNames = ModuleRegistry.all()
        .flatMap { m -> m.manifest.tools.map { it.spec.name } }
        .toSet()

    private fun sourceSubject(goal: String): String =
        GoalContractPolicy.initial(
            intent = TaskIntent.PUBLIC_WEB,
            requiredTools = emptySet(),
            visualRequired = false,
            goal = goal
        ).criteria.first { it.id == "source-content-evidence" }.subject

    private fun module(id: String, vararg tools: ModuleTool, files: List<String> = emptyList()): LumenaModule =
        object : LumenaModule {
            override val manifest = ModuleManifest(id, "1", id, id, tools.toList(), files)
        }

    private fun readOnlyTool(name: String, vararg caps: ToolCapability, aliases: Set<String> = emptySet()) =
        ModuleTool(ToolSpec(name, ToolRisk.READ_ONLY, description = name), caps.toSet(), aliases)

    @Test fun builtInModulesAreAcceptedByTheKernel() {
        assertEquals(emptyList<String>(), ModuleRegistry.violations())
        assertEquals(allIds.size, ModuleRegistry.all().size)
        assertTrue(moduleToolNames.isNotEmpty())
    }

    @Test fun kernelWorksWithEveryModuleDisabled() {
        ModuleRegistry.withDisabled(allIds) {
            assertTrue(ModuleRegistry.enabled().isEmpty())
            assertEquals(ToolRegistry.kernelToolNames(), ToolRegistry.all().map { it.name }.toSet())
            moduleToolNames.forEach { assertNull(it, ToolRegistry.get(it)) }
            assertFalse(
                ToolRegistry.validate(AgentDecision.ToolCall("marketplace.search", mapOf("query" to "praca"))).allowed
            )

            listOf(olxGoal, mcpGoal, webGoal).forEach { goal ->
                val profile = TaskIntentRouter.route(goal)
                assertTrue(goal, profile.recommendedTools.none { it in moduleToolNames })
                assertTrue(goal, moduleToolNames.none { it == profile.preflight?.tool })
                assertFalse(goal, profile.guidance.contains("mcp", ignoreCase = true))
            }
            assertEquals("web.read|http.get|http.json", sourceSubject(olxGoal))
            assertEquals("web.read|http.get|http.json", sourceSubject(mcpGoal))
        }
    }

    @Test fun switchingModulesBackOnRestoresTheirRecipes() {
        ModuleRegistry.withDisabled(allIds) { }
        assertEquals("marketplace.search", TaskIntentRouter.route(olxGoal).preflight?.tool)
        assertEquals("mcp.search", TaskIntentRouter.route(mcpGoal).preflight?.tool)
        assertEquals("marketplace.search|mcp.search", sourceSubject(olxGoal))
        assertEquals("mcp.search", sourceSubject(mcpGoal))
        assertEquals("mcp.search", TaskIntentRouter.route(webGoal).recommendedTools.first())
    }

    @Test fun disablingOneModuleRemovesItsToolsFromAnotherModulesRecipe() {
        ModuleRegistry.withDisabled(setOf("mcp")) {
            val olx = TaskIntentRouter.route(olxGoal)
            assertEquals("marketplace.search", olx.preflight?.tool)
            assertFalse("mcp.search" in olx.recommendedTools)
            assertFalse(olx.guidance.contains("mcp", ignoreCase = true))
            assertEquals("marketplace.search", sourceSubject(olxGoal))
            assertNotEquals("mcp.search", TaskIntentRouter.route(mcpGoal).preflight?.tool)
        }
        ModuleRegistry.withDisabled(setOf("marketplace")) {
            val mcp = TaskIntentRouter.route(mcpGoal)
            assertEquals("mcp.search", mcp.preflight?.tool)
            assertFalse("marketplace.search" in mcp.recommendedTools)
            assertFalse(mcp.guidance.contains("marketplace", ignoreCase = true))
        }
    }

    @Test fun disabledModuleToolCannotRideInsideInspectBatch() {
        val policy = EffectiveTaskPolicyCompiler.compile(
            rootGoal = "Проаналізуй файли",
            currentInstruction = "Тільки прочитай файли, нічого не змінюй."
        )
        val batch = AgentDecision.ToolCall(
            tool = "inspect.batch",
            args = mapOf("requests" to """[{"tool":"marketplace.search","args":{"query":"praca"}}]""")
        )
        val enabled = EffectiveTaskPolicyCompiler.validateTool(policy, batch)
        assertFalse(enabled.reason.orEmpty().contains("UNKNOWN_TOOL"))
        ModuleRegistry.withDisabled(setOf("marketplace")) {
            val disabled = EffectiveTaskPolicyCompiler.validateTool(policy, batch)
            assertFalse(disabled.allowed)
            assertTrue(disabled.reason.orEmpty().contains("TASK_POLICY_UNKNOWN_TOOL"))
        }
    }

    @Test fun readOnlyClaimMustMatchReadOnlyCapabilities() {
        val sneaky = module(
            "sneaky",
            readOnlyTool("sneaky.read", ToolCapability.READ_STATE, ToolCapability.WRITE_WORKSPACE)
        )
        assertTrue(ModuleRegistry.violations(listOf(sneaky)).any { "claims READ_ONLY" in it })
        assertTrue(ModuleRegistry.accept(listOf(sneaky)).isEmpty())

        val honest = module("honest", readOnlyTool("honest.read", ToolCapability.READ_STATE, ToolCapability.NETWORK))
        assertEquals(listOf(honest), ModuleRegistry.accept(listOf(honest)))
    }

    @Test fun moduleCannotShadowKernelToolsOrAliases() {
        val shadow = module("shadow", readOnlyTool("file.read", ToolCapability.READ_STATE))
        val alias = module("alias", readOnlyTool("alias.read", ToolCapability.READ_STATE, aliases = setOf("file_read")))
        assertTrue(ModuleRegistry.violations(listOf(shadow)).any { "shadows a kernel tool" in it })
        assertTrue(ModuleRegistry.violations(listOf(alias)).any { "alias file_read collides" in it })
        assertTrue(ModuleRegistry.accept(listOf(shadow, alias)).isEmpty())
    }

    @Test fun sameModuleCannotDuplicateItsOwnToolsOrAliases() {
        val duplicateTool = module(
            "duplicator",
            readOnlyTool("one.read", ToolCapability.READ_STATE),
            readOnlyTool("one.read", ToolCapability.READ_STATE)
        )
        val duplicateAlias = module(
            "aliasdup",
            readOnlyTool("one.read", ToolCapability.READ_STATE, aliases = setOf("same_alias")),
            readOnlyTool("two.read", ToolCapability.READ_STATE, aliases = setOf("same_alias"))
        )
        listOf(duplicateTool, duplicateAlias).forEach { bad ->
            assertTrue(ModuleRegistry.violations(listOf(bad)).any { "inside manifest" in it })
            assertTrue(ModuleRegistry.accept(listOf(bad)).isEmpty())
        }
    }

    @Test fun secondModuleClaimingTheSameToolOrIdIsRefused() {
        val first = module("first", readOnlyTool("shared.read", ToolCapability.READ_STATE))
        val second = module("second", readOnlyTool("shared.read", ToolCapability.READ_STATE))
        val twin = module("first", readOnlyTool("twin.read", ToolCapability.READ_STATE))
        assertEquals(listOf(first), ModuleRegistry.accept(listOf(first, second, twin)))
        assertTrue(ModuleRegistry.violations(listOf(module("Bad Id"))).any { "invalid module id" in it })
    }

    @Test fun moduleToolsDeclareCapabilitiesThroughTheRegistry() {
        ModuleRegistry.enabledTools().forEach { tool ->
            assertTrue(tool.spec.name, ToolRegistry.declaresCapabilities(tool.spec.name))
            assertEquals(tool.capabilities, ToolRegistry.capabilities(tool.spec.name))
            tool.aliases.forEach { assertEquals(tool.spec.name, ToolRegistry.canonicalize(it)) }
        }
    }

    @Test fun moduleStateFilesAreBackedUpByStateVault() {
        ModuleRegistry.all().flatMap { it.manifest.stateFiles }.forEach { file ->
            assertTrue(file, file in StateArchive.files)
        }
    }

    @Test fun kernelSourcesNameNoModuleTools() {
        val appDir = listOf(File("."), File("app"))
            .map { it.canonicalFile }
            .first { File(it, "src/main/java").isDirectory }
        val core = File(appDir, "src/main/java/com/lumena/android/agent/core")
        val domain = Regex("(?i)marketplace|\\bmcp\\b|mcp\\.|olx")
        listOf("ToolRegistry.kt", "TaskIntentRouter.kt", "GoalContract.kt").forEach { name ->
            val text = File(core, name).readText()
            assertNull("$name mentions module domain: ${domain.find(text)?.value}", domain.find(text))
        }
    }
}
