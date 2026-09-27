package com.lumena.android.companion

import com.lumena.android.agent.local.PlannerDecision
import com.lumena.android.agent.local.ToolRequest
import org.junit.Assert.*
import org.junit.Test

class CompanionTaskGrantTest {
    private fun command(tool: String = "file.write", path: String = "aquarium-v2.part01") =
        CompanionCommand(PlannerDecision(ToolRequest(tool, mapOf("path" to path)), "test"),
            "{}", "fingerprint", "session", "task", "model")
    private fun grant() = CompanionTaskGrant.create(command(), "aquarium-v2.part*\naquarium.html", "url", "token", 100L)!!

    @Test fun coversApprovedFilesButNotSiblingOrNestedPaths() {
        val g = grant()
        assertTrue(g.allows(command(path = "aquarium-v2.part06"), "url", "token", 101))
        assertTrue(g.allows(command("file.patch", "aquarium.html"), "url", "token", 101))
        for (path in listOf("other.html", "aquarium-v2.part/secret", "../aquarium.html", "/aquarium.html", "@shared/aquarium.html", "a/../aquarium.html", "aquarium.html/"))
            assertFalse(path, g.allows(command(path = path), "url", "token", 101))
    }

    @Test fun bindsContextAndConnectionAndNeverAuthorizesExecution() {
        val g = grant()
        for (c in listOf(command().copy(sessionId = "other"), command().copy(taskId = "other"),
            command().copy(modelId = "other"), command().copy(taskId = null), command("python.run"), command("git.commit")))
            assertFalse(g.allows(c, "url", "token", 101))
        assertFalse(g.allows(command(), "other", "token", 101))
        assertFalse(g.allows(command(), "url", "other", 101))
    }

    @Test fun expiresAndConsumesFortyUses() {
        var g = grant()
        assertFalse(g.allows(command(), "url", "token", g.expiresAt))
        repeat(40) {
            assertTrue(g.allows(command(), "url", "token", 101))
            g = g.consume()
        }
        assertFalse(g.allows(command(), "url", "token", 101))
    }

    @Test fun rejectsUnboundedOrAmbiguousScopes() {
        for (path in listOf("*", "../*", "/tmp/*", "@shared/*", "a/**", "a/*/b", "a\\b", "a/./b", "", "dir/*"))
            assertNull(path, CompanionTaskGrant.create(command(), path, "url", "token", 100))
        assertNull(CompanionTaskGrant.create(command().copy(taskId = null), "a.html", "url", "token", 100))
    }

    @Test fun diagnosesMissingAndTruncatedCommandsSeparately() {
        assertTrue(CompanionProtocol.captureDiagnosticText("old result").contains("маркера"))
        assertTrue(CompanionProtocol.captureDiagnosticText("LUMENA_TOOL {\"tool\":\"file.write\"").contains("неповний"))
        assertTrue(CompanionProtocol.captureDiagnosticText("LUMENA_TOOL {oops}").contains("некоректний"))
        assertTrue(CompanionProtocol.captureDiagnosticText("LUMENA_TOOL {\"tool\":\"file.read\",\"args\":{\"path\":\"a\"}}").contains("повністю"))
    }
}
