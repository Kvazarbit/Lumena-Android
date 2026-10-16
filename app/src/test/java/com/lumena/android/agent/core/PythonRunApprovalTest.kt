package com.lumena.android.agent.core

import com.lumena.android.agent.local.ToolGate
import com.lumena.android.agent.local.ToolRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner approves the bytes of a python.run script, not its path: the
 * dialog shows them (or a diff against a version already approved in this
 * task), and the approval and its task cache are keyed on their sha256.
 */
class PythonRunApprovalTest {
    private val v1 = PythonScriptPreview(
        script = "tools/report.py",
        sha256 = "a1".repeat(32),
        sizeBytes = 51,
        content = "import os\nprint('report')\nprint(os.getcwd())\n",
        truncated = false
    )
    private val v2 = v1.copy(
        sha256 = "b2".repeat(32),
        sizeBytes = 64,
        content = "import os\nprint('report')\nos.system('curl https://x.example')\n"
    )
    private val oldHealth =
        "Lumena bridge OK\nversion=0.29\n" +
            "features=http_get_links_v1,execution_timeout_integral_decimal_v1,search_explicit_constraints_v1,python_clean_env_v1\n"

    @Test
    fun dialogShowsScriptPathSizeHashAndContent() {
        val text = PythonRunApproval.render(v1, previous = null, taint = TaskTaint())

        assertTrue(text, text.contains("tools/report.py"))
        assertTrue(text, text.contains("51"))
        assertTrue(text, text.contains(v1.sha256.take(12)))
        assertTrue(text, text.contains("print(os.getcwd())"))
        assertFalse(text, text.contains(PythonRunApproval.TAINT_LABEL))
    }

    @Test
    fun truncatedPreviewSaysHowMuchIsShown() {
        val big = v1.copy(
            sizeBytes = 300_000,
            truncated = true,
            content = "x = 1\n".repeat(2_000)
        )
        val text = PythonRunApproval.render(big, previous = null, taint = TaskTaint())

        assertTrue(text, text.contains("300000"))
        assertTrue(text, text.contains("обрізано"))
        assertTrue(
            "dialog content is not bounded: ${text.length}",
            text.length < PythonRunApproval.MAX_CONTENT_CHARS + 2_000
        )
    }

    @Test
    fun dialogNamesTheTaintSourceOfATaintedTask() {
        val taint = TaskTaint(sources = listOf("крок 1 · web.read · https://evil.example/post"))
        val text = PythonRunApproval.render(v1, previous = null, taint = taint)

        assertTrue(text, text.contains(PythonRunApproval.TAINT_LABEL))
        assertTrue(text, text.contains("web.read"))
        assertTrue(text, text.contains("https://evil.example/post"))
    }

    @Test
    fun earlierApprovedVersionOfTheSameScriptIsShownAsALineDiff() {
        val text = PythonRunApproval.render(v2, previous = v1, taint = TaskTaint())

        assertTrue(text, text.contains(v1.sha256.take(12)))
        assertTrue(text, text.lines().any { it == "- print(os.getcwd())" })
        assertTrue(text, text.lines().any { it == "+ os.system('curl https://x.example')" })
        assertFalse(text, text.lines().any { it == "+ print('report')" || it == "- print('report')" })
    }

    @Test
    fun lineDiffKeepsUnchangedLinesAndOrder() {
        assertEquals(
            listOf("  a", "- b", "+ x", "  c", "+ d"),
            PythonRunApproval.lineDiff("a\nb\nc\n", "a\nx\nc\nd\n")
        )
        assertEquals(listOf("  same"), PythonRunApproval.lineDiff("same\n", "same\n"))
    }

    @Test
    fun ledgerRemembersApprovedVersionsPerTaskAndScript() {
        val ledger = PythonApprovalLedger().record("task-1", v1)

        assertEquals(v1, ledger.previous("task-1", "tools/report.py"))
        assertNull(ledger.previous("task-2", "tools/report.py"))
        assertNull(ledger.previous("task-1", "tools/other.py"))
        assertEquals(v2, ledger.record("task-1", v2).previous("task-1", "tools/report.py"))
    }

    @Test
    fun bindPutsThePreviewHashIntoTheRequestAndOverridesTheModel() {
        val modelArgs = mapOf(
            "script" to "tools/report.py",
            PythonRunApproval.HASH_ARG to "f".repeat(64)
        )
        val bound = PythonRunApproval.bind(modelArgs, v1)

        assertEquals(v1.sha256, bound[PythonRunApproval.HASH_ARG])
        assertEquals("tools/report.py", bound["script"])
        assertEquals(mapOf("script" to "tools/report.py"), PythonRunApproval.previewArgs(modelArgs))
    }

    @Test
    fun changedHashMissesTheTaskApprovalCache() {
        val args = mapOf("script" to "tools/report.py")
        val first = ToolRequest(PythonRunApproval.TOOL, PythonRunApproval.bind(args, v1))
        val second = ToolRequest(PythonRunApproval.TOOL, PythonRunApproval.bind(args, v2))
        val granted = setOf(ToolGate.approvalKey(first))

        assertNotEquals(ToolGate.approvalKey(first), ToolGate.approvalKey(second))
        assertFalse(ToolGate.approvalKey(second) in granted)
        assertTrue(ToolGate.approvalKey(first.copy(requestId = "later")) in granted)
    }

    @Test
    fun onlyABridgeAdvertisingTheExactFeatureIsSupported() {
        assertFalse(PythonRunApproval.bridgeSupports(oldHealth))
        assertFalse(PythonRunApproval.bridgeSupports("Lumena bridge OK\nversion=0.27\n"))
        assertFalse(PythonRunApproval.bridgeSupports("features=python_run_sha256_v1_beta\n"))
        assertFalse(PythonRunApproval.bridgeSupports("note=python_run_sha256_v1\n"))
        assertTrue(
            PythonRunApproval.bridgeSupports(
                oldHealth.replace("python_clean_env_v1", "python_clean_env_v1,python_run_sha256_v1")
            )
        )
        assertTrue(PythonRunApproval.BRIDGE_UPDATE_MESSAGE.contains(PythonRunApproval.BRIDGE_FEATURE))
    }

    @Test
    fun previewParsingAcceptsOnlyAWellFormedHash() {
        val json =
            """{"script":"tools/report.py","sha256":"${v1.sha256}","size":51,""" +
                """"content":"import os\nprint('report')\nprint(os.getcwd())\n","truncated":false}"""

        assertEquals(v1, PythonRunApproval.parsePreview(json))
        assertNull(PythonRunApproval.parsePreview(json.replace(v1.sha256, "zz")))
        assertNull(PythonRunApproval.parsePreview(json.replace(v1.sha256, v1.sha256.take(40))))
        assertNull(PythonRunApproval.parsePreview("not json"))
    }

    @Test
    fun pythonRunWithoutAPreviewCannotBeApproved() {
        assertTrue(PythonRunApproval.applies("python.run"))
        assertTrue(PythonRunApproval.applies("python_run"))
        assertFalse(PythonRunApproval.applies("python.syntax_check"))
        assertFalse(PythonRunApproval.canApprove("python.run", null))
        assertTrue(PythonRunApproval.canApprove("python.run", v1))
        assertTrue(PythonRunApproval.canApprove("file.write", null))
        assertTrue(PythonRunApproval.MISSING_PREVIEW_MESSAGE.isNotBlank())
    }
}
