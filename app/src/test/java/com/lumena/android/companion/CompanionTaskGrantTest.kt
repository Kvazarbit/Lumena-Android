package com.lumena.android.companion

import com.lumena.android.agent.local.PlannerDecision
import com.lumena.android.agent.local.ToolRequest
import org.junit.Assert.*
import org.junit.Test

class CompanionTaskGrantTest {
    private fun command(tool: String = "file.write", path: String = "aquarium-v2.part01") =
        CompanionCommand(PlannerDecision(ToolRequest(tool, mapOf((if (tool == "python.run") "script" else "path") to path)), "test"),
            "{}", "fingerprint", "session", "task", "model")
    private fun grant(python: Boolean = false) = CompanionTaskGrant.create(command(),
        "aquarium-v2.part*\naquarium.html\nverify_aquarium*", "url", "token", python)!!

    @Test fun coversApprovedFilesButNotSiblingOrNestedPaths() {
        val g = grant()
        assertTrue(g.allows(command(path = "aquarium-v2.part06"), "url", "token"))
        assertTrue(g.allows(command("file.patch", "aquarium.html"), "url", "token"))
        for (path in listOf("other.html", "aquarium-v2.part/secret", "../aquarium.html", "/aquarium.html", "@shared/aquarium.html", "a/../aquarium.html", "aquarium.html/"))
            assertFalse(path, g.allows(command(path = path), "url", "token"))
    }

    @Test fun bindsContextAndConnection() {
        val g = grant()
        for (c in listOf(command().copy(sessionId = "other"), command().copy(taskId = "other"),
            command().copy(modelId = "other"), command().copy(taskId = null), command("git.commit")))
            assertFalse(g.allows(c, "url", "token"))
        assertFalse(g.allows(command(), "other", "token"))
        assertFalse(g.allows(command(), "url", "other"))
        assertTrue(g.allows(command(), "url", "token")) // Switching away does not destroy consent.
    }

    @Test fun pythonRequiresExplicitConsentAndApprovedScript() {
        val c = command("python.run", "check.py")
        val g = CompanionTaskGrant.create(c, "check.py", "url", "token", allowPython = true)!!
        assertTrue(g.allows(c, "url", "token"))
        assertFalse(grant().allows(c, "url", "token"))
        assertFalse(g.allows(command("python.run", "other.py"), "url", "token"))
        assertFalse(g.allows(command("python.run", "../check.py"), "url", "token"))
        assertFalse(g.allows(c.copy(decision = PlannerDecision(ToolRequest("python.run", mapOf("path" to "check.py")), "test")), "url", "token"))
    }

    @Test fun survivesHundredsOfCommandsAndSerializedRestartsWithoutTokenStorage() {
        var g = CompanionTaskGrant.create(command(), "aquarium*", "url", "token", true)!!
        repeat(250) {
            g = CompanionTaskGrant.restore(g.snapshot())!!
            assertTrue(g.allows(command(), "url", "token"))
            assertTrue(g.allows(command("python.run", "aquarium_test.py"), "url", "token"))
        }
        assertFalse(g.snapshot().values.contains("token"))
        assertFalse(g.snapshot().keys.any { it == "remaining" || it == "expiresAt" })
    }

    @Test fun pendingExecutionPausesAfterRestartAndIsNotReplayed() {
        val recovered = CompanionTaskGrantStore.State(grant(), "previous", "in-flight").recovered()
        assertEquals("in-flight", recovered.handled)
        assertNull(recovered.pending)
        assertFalse(recovered.grant!!.allows(command(), "url", "token"))
        assertEquals(recovered, recovered.recovered())
        val complete = CompanionTaskGrantStore.State(grant(), "finished")
        assertEquals(complete, complete.recovered())
        assertFalse(CompanionTaskGrant.restore(grant().copy(paused = true).snapshot())!!.allows(command(), "url", "token"))
    }

    @Test fun rejectsUnboundedOrAmbiguousScopesAndMalformedSnapshots() {
        for (path in listOf("*", "../*", "/tmp/*", "@shared/*", "a/**", "a/*/b", "a\\b", "a/./b", "", "dir/*"))
            assertNull(path, CompanionTaskGrant.create(command(), path, "url", "token"))
        assertNull(CompanionTaskGrant.create(command().copy(taskId = null), "a.html", "url", "token"))
        assertNull(CompanionTaskGrant.restore(emptyMap()))
        assertNull(CompanionTaskGrant.restore(grant().snapshot() + ("python" to "garbage")))
        assertNull(CompanionTaskGrant.restore(grant().snapshot() + ("paths" to "*")))
    }

    @Test fun diagnosesMissingAndTruncatedCommandsSeparately() {
        assertTrue(CompanionProtocol.captureDiagnosticText("old result").contains("маркера"))
        assertTrue(CompanionProtocol.captureDiagnosticText("LUMENA_TOOL {\"tool\":\"file.write\"").contains("неповний"))
        assertTrue(CompanionProtocol.captureDiagnosticText("LUMENA_TOOL {oops}").contains("некоректний"))
        assertTrue(CompanionProtocol.captureDiagnosticText("LUMENA_TOOL {\"tool\":\"file.read\",\"args\":{\"path\":\"a\"}}").contains("повністю"))
    }
}
