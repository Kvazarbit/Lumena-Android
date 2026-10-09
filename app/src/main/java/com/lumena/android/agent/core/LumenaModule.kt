package com.lumena.android.agent.core

import com.lumena.android.modules.BuiltInModules

/**
 * Module contract (charter ART-14): a module adds capabilities, never
 * authority. The kernel (charter, ToolRegistry/ToolGate, GoalContract,
 * experience, StateVault) works with every module disabled.
 *
 * A module only DECLARES; the kernel decides. Its tools are registered only
 * while it is enabled and only if the kernel accepts its manifest: no
 * shadowing of kernel tools or aliases, and a READ_ONLY claim must match
 * read-only capabilities.
 */
data class ModuleTool(
    val spec: ToolSpec,
    val capabilities: Set<ToolCapability>,
    val aliases: Set<String> = emptySet()
)

data class ModuleManifest(
    val id: String,
    val version: String,
    /** Owner-facing name (Ukrainian). */
    val title: String,
    /** Owner-facing description (Ukrainian). */
    val description: String,
    val tools: List<ModuleTool> = emptyList(),
    /** App-private files this module owns; each must be in StateArchive. */
    val stateFiles: List<String> = emptyList()
)

interface LumenaModule {
    val manifest: ModuleManifest

    /**
     * Claims a goal at the module routing slot (after kernel file inspection,
     * before kernel public web). Null when the goal is not this module's.
     */
    fun route(normalized: String, lower: String): TaskIntentProfile? = null

    /**
     * Tools whose successful result satisfies the source-content criterion of
     * a public-web goal this module owns, in preference order. Null when the
     * goal is not this module's.
     */
    fun sourceEvidenceTools(goal: String): List<String>? = null

    /** Tools this module adds in front of the kernel public-web recipe. */
    fun publicWebTools(): List<String> = emptyList()

    /** Replaces the kernel public-web guidance while this module is enabled. */
    fun publicWebGuidance(): String? = null
}

object ModuleRegistry {
    /** A READ_ONLY claim is credible only with these capabilities. */
    val READ_ONLY_CAPABILITIES: Set<ToolCapability> =
        setOf(ToolCapability.READ_STATE, ToolCapability.NETWORK)

    private val ID_PATTERN = Regex("^[a-z][a-z0-9-]{1,40}$")

    @Volatile
    private var disabledIds: Set<String> = emptySet()

    /** Built-in modules the kernel accepted; a rejected module never runs. */
    private val accepted: List<LumenaModule> by lazy { accept(BuiltInModules.all) }

    fun all(): List<LumenaModule> = BuiltInModules.all

    fun enabled(): List<LumenaModule> = accepted.filter { it.manifest.id !in disabledIds }

    fun isEnabled(id: String): Boolean = enabled().any { it.manifest.id == id }

    fun disabledIds(): Set<String> = disabledIds

    fun setDisabled(ids: Set<String>) {
        disabledIds = ids.toSet()
    }

    /** Runs [block] with exactly [ids] disabled, then restores the previous set. */
    fun <T> withDisabled(ids: Set<String>, block: () -> T): T {
        val before = disabledIds
        disabledIds = ids.toSet()
        try {
            return block()
        } finally {
            disabledIds = before
        }
    }

    fun enabledTools(): List<ModuleTool> = enabled().flatMap { it.manifest.tools }

    fun moduleOfTool(tool: String): LumenaModule? {
        val canonical = tool.trim().lowercase()
        return all().firstOrNull { module ->
            module.manifest.tools.any { it.spec.name == canonical || canonical in it.aliases }
        }
    }

    /** Every reason the kernel refuses a module set; empty means all are accepted. */
    fun violations(modules: List<LumenaModule> = all()): List<String> =
        modules.flatMapIndexed { index, module -> violationsOf(module, modules.take(index)) }

    /** Modules from [modules] that the kernel accepts, in order (fail closed). */
    fun accept(modules: List<LumenaModule>): List<LumenaModule> {
        val out = mutableListOf<LumenaModule>()
        modules.forEach { module ->
            if (violationsOf(module, out).isEmpty()) out += module
        }
        return out
    }

    private fun violationsOf(module: LumenaModule, earlier: List<LumenaModule>): List<String> {
        val m = module.manifest
        val problems = mutableListOf<String>()
        val kernelNames = ToolRegistry.kernelToolNames()
        val kernelAliases = ToolRegistry.kernelAliasNames()
        val earlierNames = earlier.flatMap { e -> e.manifest.tools.flatMap { it.aliases + it.spec.name } }.toSet()
        val earlierFiles = earlier.flatMap { it.manifest.stateFiles }.toSet()

        if (!ID_PATTERN.matches(m.id)) problems += "${m.id}: invalid module id"
        if (earlier.any { it.manifest.id == m.id }) problems += "${m.id}: duplicate module id"
        m.tools.forEach { tool ->
            val name = tool.spec.name
            if (name in kernelNames || name in kernelAliases) problems += "${m.id}: $name shadows a kernel tool"
            if (name in earlierNames) problems += "${m.id}: $name is already owned by another module"
            tool.aliases.forEach { alias ->
                if (alias in kernelNames || alias in kernelAliases || alias in earlierNames) {
                    problems += "${m.id}: alias $alias collides"
                }
            }
            if (tool.capabilities.isEmpty()) problems += "${m.id}: $name declares no capabilities"
            if (tool.spec.risk == ToolRisk.READ_ONLY && !READ_ONLY_CAPABILITIES.containsAll(tool.capabilities)) {
                problems += "${m.id}: $name claims READ_ONLY but can ${tool.capabilities.joinToString("+")}"
            }
            if (
                tool.spec.workspaceMutationEffect != WorkspaceMutationEffect.NONE &&
                ToolCapability.WRITE_WORKSPACE !in tool.capabilities
            ) {
                problems += "${m.id}: $name changes the workspace without WRITE_WORKSPACE"
            }
        }
        m.stateFiles.forEach { file ->
            if (file in earlierFiles) problems += "${m.id}: state file $file is owned by another module"
        }
        return problems
    }
}
