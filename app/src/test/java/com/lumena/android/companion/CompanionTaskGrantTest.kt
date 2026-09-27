package com.lumena.android.companion

import com.lumena.android.agent.local.PlannerDecision
import com.lumena.android.agent.local.ToolRequest
import org.junit.Assert.*
import org.junit.Test

class CompanionTaskGrantTest {
    private fun command(tool: String = "file.write", path: String = "aquarium-v2.part01") =
        CompanionCommand(
            PlannerDecision(ToolRequest(tool, mapOf("path" to path)), "test"),
            "{}", "fingerprint", "session", "task", "model"
        )

    private fun pythonCommand(script: String = "verify.py") =
        CompanionCommand(
            PlannerDecision(ToolRequest("python.run", mapOf("script" to script)), "test"),
            "{}", "python-fingerprint", "session", "task", "model"
        )

    private fun fileGrant() =
        CompanionTaskGrant.create(command(), "aquarium-v2.part*\naquarium.html", "url", "token", 100L)!!

    private fun pythonGrant() =
        CompanionTaskGrant.create(pythonCommand(), "", "url", "token", 100L)!!

    @Test fun coversApprovedFilesButNotSiblingOrNestedPaths() {
        val g = fileGrant()
        assertTrue(g.allows(command(path = "aquarium-v2.part06"), "url", "token", 101))
        assertTrue(g.allows(command("file.patch", "aquarium.html"), "url", "token", 101))
        for (path in listOf(
            "other.html", "aquarium-v2.part/secret", "../aquarium.html", "/aquarium.html",
            "@shared/aquarium.html", "a/../aquarium.html", "aquarium.html/"
        )) assertFalse(path, g.allows(command(path = path), "url", "token", 101))
        assertFalse(g.allows(pythonCommand(), "url", "token", 101))
    }

    @Test fun pythonGrantAuthorizesOnlyPythonRunInExactTaskContext() {
        val g = pythonGrant()
        assertEquals(CompanionTaskGrantKind.PYTHON_RUN, g.kind)
        assertTrue(g.allows(pythonCommand("one.py"), "url", "token", 101))
        assertTrue(g.allows(pythonCommand("another.py"), "url", "token", 101))
        assertFalse(g.allows(command(), "url", "token", 101))
        assertFalse(g.allows(command("git.commit"), "url", "token", 101))
        assertFalse(g.allows(pythonCommand().copy(sessionId = "other"), "url", "token", 101))
        assertFalse(g.allows(pythonCommand().copy(taskId = "other"), "url", "token", 101))
        assertFalse(g.allows(pythonCommand().copy(modelId = "other"), "url", "token", 101))
        assertFalse(g.allows(pythonCommand(), "other", "token", 101))
        assertFalse(g.allows(pythonCommand(), "url", "other", 101))
    }

    @Test fun expiresAfterFortyMinutesAndConsumesFortyUses() {
        var g = pythonGrant()
        assertEquals(100L + CompanionTaskGrant.DURATION_MS, g.expiresAt)
        assertEquals(40 * 60_000L, CompanionTaskGrant.DURATION_MS)
        assertFalse(g.allows(pythonCommand(), "url", "token", g.expiresAt))
        repeat(CompanionTaskGrant.MAX_USES) {
            assertTrue(g.allows(pythonCommand(), "url", "token", 101))
            g = g.consume()
        }
        assertFalse(g.allows(pythonCommand(), "url", "token", 101))
    }

    @Test fun restoreKeepsScopeButNeverStoresRawBridgeToken() {
        val key = CompanionTaskGrant.connectionFingerprint("url", "token")
        assertFalse(key.contains("token"))
        val restored = CompanionTaskGrant.restore(
            sessionId = "session",
            taskId = "task",
            modelId = "model",
            bridgeFingerprint = key,
            kindName = CompanionTaskGrantKind.PYTHON_RUN.name,
            patterns = listOf("ignored.py"),
            expiresAt = 50_000L,
            remaining = 17
        )!!
        assertEquals(17, restored.remaining)
        assertTrue(restored.patterns.isEmpty())
        assertTrue(restored.allows(pythonCommand(), "url", "token", 200L))
        assertFalse(restored.allows(pythonCommand(), "url", "different", 200L))
        assertNull(
            CompanionTaskGrant.restore(
                "session", "task", "model", key, "BROKEN", emptyList(), 50_000L, 17
            )
        )
    }

    @Test fun rejectsUnboundedOrAmbiguousFileScopes() {
        for (path in listOf("*", "../*", "/tmp/*", "@shared/*", "a/**", "a/*/b", "a\\b", "a/./b", "", "dir/*"))
            assertNull(path, CompanionTaskGrant.create(command(), path, "url", "token", 100))
        assertNull(CompanionTaskGrant.create(command().copy(taskId = null), "a.html", "url", "token", 100))
        assertNull(CompanionTaskGrant.create(pythonCommand().copy(sessionId = null), "", "url", "token", 100))
    }

    @Test fun diagnosesMissingAndTruncatedCommandsSeparately() {
        assertTrue(CompanionProtocol.captureDiagnosticText("old result").contains("маркера"))
        assertTrue(CompanionProtocol.captureDiagnosticText("LUMENA_TOOL {\"tool\":\"file.write\"").contains("неповний"))
        assertTrue(CompanionProtocol.captureDiagnosticText("LUMENA_TOOL {oops}").contains("некоректний"))
        assertTrue(CompanionProtocol.captureDiagnosticText("LUMENA_TOOL {\"tool\":\"file.read\",\"args\":{\"path\":\"a\"}}").contains("повністю"))
    }
}
