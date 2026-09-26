package com.lumena.android.agent.mcp

import com.lumena.android.agent.local.ToolExecutor
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMcpRuntimeTest {
    private class FakeExecutor : ToolExecutor {
        var calls: Int = 0
        var last: ToolRequest? = null

        override suspend fun execute(toolRequest: ToolRequest): ToolResult {
            calls += 1
            last = toolRequest
            return ToolResult(
                ok = true,
                tool = toolRequest.tool,
                stdout = "ok"
            )
        }
    }

    @Test
    fun discoverUsesCurrentStatelessProtocolVersion() {
        val runtime = LocalMcpRuntime(FakeExecutor())
        val result = runtime.discover()

        assertTrue(LOCAL_MCP_PROTOCOL_VERSION in result.supportedVersions)
        assertTrue(result.tools)
        assertEquals("private", result.cacheScope)
    }

    @Test
    fun toolsListProjectsExistingRegistry() {
        val runtime = LocalMcpRuntime(FakeExecutor())
        val tools = runtime.listTools().tools

        val health = tools.single { it.name == "health" }
        assertTrue(health.readOnlyHint)

        val write = tools.single { it.name == "file.write" }
        assertFalse(write.readOnlyHint)
        assertTrue(write.mutatingOrExecutable)
    }

    @Test
    fun readOnlyToolExecutesInProcess() = runBlocking {
        val executor = FakeExecutor()
        val runtime = LocalMcpRuntime(executor)

        val result = runtime.callTool("health")

        assertTrue(result is LocalMcpCallResult.Complete)
        assertEquals(1, executor.calls)
        assertEquals("health", executor.last?.tool)
    }

    @Test
    fun mutatingToolRequiresHostConfirmationAndDoesNotExecute() = runBlocking {
        val executor = FakeExecutor()
        val runtime = LocalMcpRuntime(executor)

        val result = runtime.callTool(
            name = "file.write",
            args = mapOf(
                "path" to "demo.txt",
                "content" to "hello"
            )
        )

        assertTrue(result is LocalMcpCallResult.InputRequired)
        assertEquals(0, executor.calls)
    }

    @Test
    fun unknownToolIsRejectedWithoutExecution() = runBlocking {
        val executor = FakeExecutor()
        val runtime = LocalMcpRuntime(executor)

        val result = runtime.callTool("not.a.tool")

        assertTrue(result is LocalMcpCallResult.Rejected)
        assertEquals(0, executor.calls)
    }
}
