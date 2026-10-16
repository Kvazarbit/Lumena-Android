package com.lumena.android.companion

import com.lumena.android.agent.core.PythonRunApproval
import com.lumena.android.agent.core.PythonScriptPreview
import com.lumena.android.agent.local.PythonRunPreflight
import com.lumena.android.agent.local.ToolExecutor
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Companion sends python.run that ChatGPT wrote. The app binds the hash of
 * the bytes it read itself (the version shown for Run once, the current file
 * for a task grant) and never the approved_sha256 from the incoming command.
 */
class CompanionPythonApprovalTest {
    private val script = "tools/report.py"
    private val v1 = "print('report v1')\n"
    private val v2 = "print('report v1')\nimport os\nos.remove('data.db')\n"
    private val forged = "0".repeat(64)
    private val incoming = ToolRequest(
        "python.run",
        mapOf("script" to script, PythonRunApproval.HASH_ARG to forged)
    )

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun preview(content: String) = PythonScriptPreview(
        script = script,
        sha256 = sha256(content),
        sizeBytes = content.toByteArray().size.toLong(),
        content = content,
        truncated = false
    )

    private class FakeBridge(
        var content: String,
        var features: String = NEW_FEATURES,
        var previewError: String? = null
    ) : ToolExecutor {
        val calls = mutableListOf<ToolRequest>()

        override suspend fun execute(toolRequest: ToolRequest): ToolResult {
            calls += toolRequest
            return when (toolRequest.tool) {
                "health" -> ToolResult(
                    ok = true,
                    tool = "health",
                    exitCode = 0,
                    stdout = "Lumena bridge OK\nversion=0.29\nfeatures=$features\n"
                )
                "python.preview" -> previewError?.let {
                    ToolResult(ok = false, tool = "python.preview", error = it)
                } ?: ToolResult(
                    ok = true,
                    tool = "python.preview",
                    exitCode = 0,
                    stdout = previewJson(toolRequest.args["script"].orEmpty(), content)
                )
                "python.run" -> ToolResult(ok = true, tool = "python.run", exitCode = 0, stdout = "report done")
                else -> ToolResult(ok = true, tool = toolRequest.tool, exitCode = 0, stdout = "ok")
            }
        }
    }

    private fun FakeBridge.pythonRuns() = calls.filter { it.tool == "python.run" }

    @Test
    fun runOnceSendsTheHashOfTheVersionShownNotTheIncomingOne() = runBlocking {
        val bridge = FakeBridge(v1)
        val shown = PythonRunPreflight.prepare(bridge, incoming)
        assertTrue("$shown", shown is PythonRunPreflight.Bound)
        bridge.content = v2

        CompanionPythonApproval.execute(bridge, incoming, CompanionPythonApproval.Mode.Manual(shown))

        assertEquals(sha256(v1), bridge.pythonRuns().single().args[PythonRunApproval.HASH_ARG])
    }

    @Test
    fun runOnceWithoutAShownVersionNeverStartsPython() = runBlocking {
        val bridge = FakeBridge(v1)

        val result = CompanionPythonApproval.execute(bridge, incoming, CompanionPythonApproval.Mode.Manual(null))

        assertFalse(result.ok)
        assertFalse(result.outcomeUnknown)
        assertTrue(bridge.pythonRuns().isEmpty())
    }

    @Test
    fun runOnceRechecksTheBridgeBeforeRunning() = runBlocking {
        val bridge = FakeBridge(v1)
        val shown = PythonRunPreflight.prepare(bridge, incoming)
        bridge.features = OLD_FEATURES

        val result = CompanionPythonApproval.execute(bridge, incoming, CompanionPythonApproval.Mode.Manual(shown))

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty(), result.error.orEmpty().contains(PythonRunApproval.BRIDGE_FEATURE))
        assertTrue(bridge.pythonRuns().isEmpty())
    }

    @Test
    fun taskGrantBindsTheCurrentFileAndIgnoresTheIncomingHash() = runBlocking {
        val bridge = FakeBridge(v2)

        val result = CompanionPythonApproval.execute(bridge, incoming, CompanionPythonApproval.Mode.Grant)

        assertTrue(result.ok)
        assertEquals(sha256(v2), bridge.pythonRuns().single().args[PythonRunApproval.HASH_ARG])
    }

    @Test
    fun taskGrantAgainstAnOldBridgeIsRefusedWithoutReadingOrRunning() = runBlocking {
        val bridge = FakeBridge(v1, features = OLD_FEATURES)

        val result = CompanionPythonApproval.execute(bridge, incoming, CompanionPythonApproval.Mode.Grant)

        assertFalse(result.ok)
        assertFalse(result.outcomeUnknown)
        assertTrue(result.error.orEmpty(), result.error.orEmpty().contains(PythonRunApproval.BRIDGE_UPDATE_MESSAGE))
        assertFalse(bridge.calls.any { it.tool == "python.preview" || it.tool == "python.run" })
    }

    @Test
    fun taskGrantWithAMissingScriptReturnsTheErrorWithoutRunning() = runBlocking {
        val bridge = FakeBridge(v1, previewError = "FileNotFoundError: /workspace/tools/report.py")

        val result = CompanionPythonApproval.execute(bridge, incoming, CompanionPythonApproval.Mode.Grant)

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty(), result.error.orEmpty().contains("FileNotFoundError"))
        assertTrue(bridge.pythonRuns().isEmpty())
    }

    @Test
    fun cardShowsTheContentSizeAndChatGptAsTheTaintSource() = runBlocking {
        val shown = PythonRunPreflight.prepare(FakeBridge(v1), incoming)

        val text = CompanionPythonApproval.cardText(shown, previous = null)

        assertTrue(text, text.contains("print('report v1')"))
        assertTrue(text, text.contains("${v1.toByteArray().size} байт"))
        assertTrue(text, text.contains(PythonRunApproval.TAINT_LABEL))
        assertTrue(text, text.contains(CompanionPythonApproval.TAINT_SOURCE))
    }

    @Test
    fun cardShowsADiffAgainstAVersionAlreadyRunOnce() = runBlocking {
        val shown = PythonRunPreflight.prepare(FakeBridge(v2), incoming)

        val text = CompanionPythonApproval.cardText(shown, previous = preview(v1))

        assertTrue(text, text.lines().any { it == "+ os.remove('data.db')" })
        assertFalse(text, text.lines().any { it == "+ print('report v1')" })
    }

    @Test
    fun cardIsHonestWhileLoadingOrRefused() {
        assertTrue(CompanionPythonApproval.cardText(null, null).isNotBlank())
        assertEquals(
            PythonRunApproval.BRIDGE_UPDATE_MESSAGE,
            CompanionPythonApproval.cardText(PythonRunPreflight.Refused(PythonRunApproval.BRIDGE_UPDATE_MESSAGE), null)
        )
    }

    companion object {
        private const val OLD_FEATURES =
            "http_get_links_v1,execution_timeout_integral_decimal_v1,search_explicit_constraints_v1,python_clean_env_v1"
        private const val NEW_FEATURES = "$OLD_FEATURES,python_run_sha256_v1"

        private fun json(text: String): String = buildString {
            append('"')
            text.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    else -> append(ch)
                }
            }
            append('"')
        }

        private fun previewJson(script: String, content: String): String {
            val sha = MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return """{"script":${json(script)},"sha256":"$sha","size":${content.toByteArray().size},""" +
                """"content":${json(content)},"truncated":false}"""
        }
    }
}
