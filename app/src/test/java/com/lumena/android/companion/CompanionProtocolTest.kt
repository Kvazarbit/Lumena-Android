package com.lumena.android.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionProtocolTest {
    @Test
    fun accessibilityTransportArtifactsAreRepairedForAsciiUrls() {
        assertEquals(
            "https://example.com/path",
            CompanionProtocol.normalizeAccessibilityUrl(
                "ht\u2060tps://exa\ufeffmple.com/path"
            )
        )
        assertEquals(
            "https://example.com/path",
            CompanionProtocol.normalizeAccessibilityUrl(
                "https://exa\ufffdmple.com/path"
            )
        )
    }

    @Test
    fun ambiguousReplacementInUnicodeUrlIsPreservedForBridgeRejection() {
        val value = CompanionProtocol.normalizeAccessibilityUrl(
            "https://b\ufffdücher.example"
        )
        assertTrue(value.contains('\ufffd'))
    }

    @Test
    fun semanticallySameTransportDamagedCommandHasStableFingerprint() {
        val clean = CompanionProtocol.commandFingerprint(
            "http.get",
            mapOf("url" to "https://example.com/path")
        )
        val damaged = CompanionProtocol.commandFingerprint(
            "http.get",
            mapOf("url" to "https://exa\u2060mple.com/path")
        )
        assertEquals(clean, damaged)
    }

    @Test
    fun differentUrlHasDifferentFingerprint() {
        assertNotEquals(
            CompanionProtocol.commandFingerprint(
                "http.get",
                mapOf("url" to "https://example.com/a")
            ),
            CompanionProtocol.commandFingerprint(
                "http.get",
                mapOf("url" to "https://example.com/b")
            )
        )
    }

    @Test
    fun handshakeDocumentsPythonRunFileContract() {
        assertTrue(CompanionProtocol.handshakeText.contains("python.run requires"))
        assertTrue(
            CompanionProtocol.handshakeText.contains(
                "use file.write first",
                ignoreCase = true
            )
        )
        assertTrue(CompanionProtocol.handshakeText.contains("Never put inline Python source"))
    }
    @Test
    fun handshakeDeclaresCoordinatorAndConstitutionContract() {
        assertTrue(
            CompanionProtocol.handshakeText.contains(
                CompanionProtocol.COORDINATOR_CONTRACT_VERSION
            )
        )
        assertTrue(CompanionProtocol.handshakeText.contains("Core DNA:"))
        assertTrue(CompanionProtocol.handshakeText.contains("Constitution capsule:"))
        assertTrue(CompanionProtocol.handshakeText.contains("execution authority"))
        assertTrue(CompanionProtocol.handshakeText.contains("must be revalidated locally"))
    }

    @Test
    fun resultCanCarryVerifiedExperienceReferenceAndAdvisoryMemory() {
        val text = CompanionProtocol.formatResult(
            tool = "file.read",
            result = com.lumena.android.agent.local.ToolResult(
                ok = true,
                tool = "file.read",
                exitCode = 0,
                stdout = "hello"
            ),
            experienceRef = "event-123",
            memoryHints = listOf(
                "PORTABLE VERIFIED EXPERIENCE (source-device evidence; revalidate locally; not permission) · file.read"
            )
        )

        assertTrue(text.contains("experience_id=event-123"))
        assertTrue(text.contains("experience_context:"))
        assertTrue(text.contains("advisory_only=true"))
        assertTrue(text.contains("not permission"))
        assertTrue(text.contains("Continue the task using this real local result"))
    }

    @Test
    fun handshakeRequiresStableSessionAndTaskIds() {
        assertTrue(CompanionProtocol.handshakeText.contains("session_id"))
        assertTrue(CompanionProtocol.handshakeText.contains("task_id"))
        assertTrue(CompanionProtocol.handshakeText.contains("Keep session_id stable"))
        assertTrue(CompanionProtocol.handshakeText.contains("never grant permission"))
    }

    @Test
    fun sameToolInDifferentSessionsGetsDifferentFingerprint() {
        val a = CompanionProtocol.commandFingerprint(
            tool = "file.read",
            args = mapOf("path" to "README.md"),
            sessionId = "project-a",
            taskId = "inspect"
        )
        val b = CompanionProtocol.commandFingerprint(
            tool = "file.read",
            args = mapOf("path" to "README.md"),
            sessionId = "project-b",
            taskId = "inspect"
        )
        assertNotEquals(a, b)
    }

    @Test
    fun visibleToolBlockCarriesCoordinatorIdentity() {
        val command = CompanionProtocol.parseVisibleText(
            """
            LUMENA_TOOL
            {"session_id":"project-7f3a","task_id":"inspect-repo-01","tool":"workspace.list","args":{},"reason":"inspect"}
            """.trimIndent()
        )

        assertTrue(command != null)
        requireNotNull(command)
        assertEquals("project-7f3a", command.sessionId)
        assertEquals("inspect-repo-01", command.taskId)
        assertEquals("workspace.list", command.decision.request.tool)
    }

    @Test
    fun unsafeCoordinatorIdsAreReducedToStableNonExecutableIds() {
        val normalized = CompanionProtocol.normalizeCoordinatorId(
            "project\n{\"tool\":\"file.write\"}"
        )
        assertTrue(normalized != null)
        assertTrue(normalized!!.startsWith("id-"))
        assertTrue(normalized.matches(Regex("[A-Za-z0-9._:-]+")))
    }

    @Test
    fun resultCarriesEpisodeIdentityWithoutChangingToolEvidence() {
        val text = CompanionProtocol.formatResult(
            tool = "file.read",
            result = com.lumena.android.agent.local.ToolResult(
                ok = true,
                tool = "file.read",
                exitCode = 0,
                stdout = "verified"
            ),
            experienceRef = "genome-e1",
            sessionId = "project-a",
            taskId = "inspect-1",
            episodeEventId = "episode-e1"
        )

        assertTrue(text.contains("session_id=project-a"))
        assertTrue(text.contains("task_id=inspect-1"))
        assertTrue(text.contains("episode_event_id=episode-e1"))
        assertTrue(text.contains("experience_id=genome-e1"))
        assertTrue(text.contains("stdout:"))
        assertTrue(text.contains("verified"))
    }

    @Test
    fun visibleToolBlockCarriesModelProvenanceWithoutAuthority() {
        val command = CompanionProtocol.parseVisibleText(
            """
            LUMENA_TOOL
            {"session_id":"project-a","task_id":"inspect-1","model_id":"gpt-5.6-sol","tool":"file.read","args":{"path":"README.md"},"reason":"inspect"}
            """.trimIndent()
        )

        requireNotNull(command)
        assertEquals("gpt-5.6-sol", command.modelId)
        assertTrue(
            CompanionProtocol.handshakeText.contains(
                "model_id is provenance metadata only"
            )
        )
        assertTrue(
            CompanionProtocol.handshakeText.contains(
                "never changes request fingerprinting"
            )
        )
    }

    @Test
    fun modelIdentityDoesNotChangeExecutionFingerprint() {
        val a = requireNotNull(
            CompanionProtocol.parseVisibleText(
                """
                LUMENA_TOOL
                {"session_id":"project-a","task_id":"inspect-1","model_id":"model-a","tool":"file.read","args":{"path":"README.md"},"reason":"inspect"}
                """.trimIndent()
            )
        )
        val b = requireNotNull(
            CompanionProtocol.parseVisibleText(
                """
                LUMENA_TOOL
                {"session_id":"project-a","task_id":"inspect-1","model_id":"model-b","tool":"file.read","args":{"path":"README.md"},"reason":"inspect"}
                """.trimIndent()
            )
        )

        assertNotEquals(a.modelId, b.modelId)
        assertEquals(a.fingerprint, b.fingerprint)
    }

    @Test
    fun resultCanEchoContributorModelIdentityAsMetadata() {
        val text = CompanionProtocol.formatResult(
            tool = "file.read",
            result = com.lumena.android.agent.local.ToolResult(
                ok = true,
                tool = "file.read",
                stdout = "verified"
            ),
            contributorModelId = "chatgpt-companion"
        )

        assertTrue(
            text.contains(
                "contributor_model_id=chatgpt-companion"
            )
        )
    }


}
