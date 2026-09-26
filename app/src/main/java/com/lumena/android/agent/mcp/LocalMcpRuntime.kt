package com.lumena.android.agent.mcp

import com.lumena.android.agent.core.AgentDecision
import com.lumena.android.agent.core.ToolRegistry
import com.lumena.android.agent.core.ToolRisk
import com.lumena.android.agent.local.ToolExecutor
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult

const val LOCAL_MCP_PROTOCOL_VERSION = "2026-07-28"

data class LocalMcpServerInfo(
    val name: String = "lumena-local-mcp",
    val version: String = "1.0"
)

data class LocalMcpDiscoverResult(
    val resultType: String = "complete",
    val supportedVersions: List<String> = listOf(LOCAL_MCP_PROTOCOL_VERSION),
    val tools: Boolean = true,
    val serverInfo: LocalMcpServerInfo = LocalMcpServerInfo(),
    val ttlMs: Long = 60_000L,
    val cacheScope: String = "private"
)

data class LocalMcpToolDescriptor(
    val name: String,
    val title: String,
    val description: String,
    val requiredArgs: List<String>,
    val readOnlyHint: Boolean,
    val mutatingOrExecutable: Boolean
)

data class LocalMcpListToolsResult(
    val resultType: String = "complete",
    val tools: List<LocalMcpToolDescriptor>,
    val ttlMs: Long = 15_000L,
    val cacheScope: String = "private"
)

sealed interface LocalMcpCallResult {
    val resultType: String

    data class Complete(
        val result: ToolResult
    ) : LocalMcpCallResult {
        override val resultType: String = "complete"
    }

    data class InputRequired(
        val reason: String,
        val tool: String
    ) : LocalMcpCallResult {
        override val resultType: String = "input_required"
    }

    data class Rejected(
        val reason: String,
        val tool: String?
    ) : LocalMcpCallResult {
        override val resultType: String = "complete"
    }
}

/**
 * APK-local MCP 2026-07-28 data-layer host.
 *
 * V1 is intentionally in-process and stateless. It exposes server/discover,
 * tools/list and tools/call semantics over the existing Lumena ToolRegistry.
 * It does not open a socket, does not bypass ToolGate and never executes a
 * mutating/executable tool without a separate confirmation-capable host flow.
 */
class LocalMcpRuntime(
    private val executor: ToolExecutor
) {
    fun discover(): LocalMcpDiscoverResult = LocalMcpDiscoverResult()

    fun listTools(): LocalMcpListToolsResult =
        LocalMcpListToolsResult(
            tools = ToolRegistry.all().map { spec ->
                LocalMcpToolDescriptor(
                    name = spec.name,
                    title = spec.name,
                    description = spec.description,
                    requiredArgs = spec.requiredArgs.sorted(),
                    readOnlyHint = spec.risk == ToolRisk.READ_ONLY,
                    mutatingOrExecutable = spec.risk != ToolRisk.READ_ONLY
                )
            }
        )

    suspend fun callTool(
        name: String,
        args: Map<String, String> = emptyMap(),
        requestId: String? = null
    ): LocalMcpCallResult {
        val canonical = ToolRegistry.canonicalize(name)
        val validation = ToolRegistry.validate(
            AgentDecision.ToolCall(
                tool = canonical,
                args = args,
                reason = "Local MCP tools/call"
            ),
            externalSource = false
        )

        if (!validation.allowed) {
            return LocalMcpCallResult.Rejected(
                reason = validation.error ?: "Tool validation failed",
                tool = validation.canonicalTool ?: canonical
            )
        }

        val tool = validation.canonicalTool ?: canonical
        if (validation.requiresConfirmation) {
            return LocalMcpCallResult.InputRequired(
                reason = "Lumena confirmation/policy gate is required before side effects.",
                tool = tool
            )
        }

        return LocalMcpCallResult.Complete(
            result = executor.execute(
                ToolRequest(
                    tool = tool,
                    args = args,
                    requestId = requestId
                )
            )
        )
    }
}
