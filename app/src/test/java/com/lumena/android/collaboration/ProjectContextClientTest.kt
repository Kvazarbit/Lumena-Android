package com.lumena.android.collaboration

import com.lumena.android.ollama.ChatModelClient
import com.lumena.android.ollama.OllamaMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectContextClientTest {
    @Test fun delegatedInferenceCannotUseACloudAliasOrRemoteBridgeWithoutConsent() {
        assertTrue(ProjectToolPrivacy.inferenceAllowed("http://127.0.0.1:8765", "gemma:local", true, false))
        assertFalse("NESTED_CLOUD_EGRESS", ProjectToolPrivacy.inferenceAllowed("http://127.0.0.1:8765", "minimax:cloud", true, false))
        assertFalse(ProjectToolPrivacy.inferenceAllowed("https://remote.example", "gemma:local", true, false))
        assertFalse(ProjectToolPrivacy.inferenceAllowed("http://127.0.0.1:8765", "gemma:local", false, false))
        assertTrue(ProjectToolPrivacy.inferenceAllowed("https://remote.example", "minimax:cloud", false, true))
    }
    @Test fun derivedPrivateHistoryCannotLeaveOnRouteChangeOrDeselection() = runBlocking {
        var sent = 0
        val delegate = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
                sent++; return Result.success("ok")
            }
        }
        val history = listOf(OllamaMessage("assistant", "DERIVED_PRIVATE_CANARY"))
        val client = ProjectContextClient(delegate, "ollama", "https://remote.example", { false },
            { setOf("old-project") }, { false })
        assertTrue("PRIVATE_HISTORY_EGRESS", client.chat("remote", history).isFailure)
        var streamed = false
        assertTrue(client.chatStreaming("remote", history) { streamed = true }.isFailure)
        assertEquals("PRIVATE_HISTORY_EGRESS", 0, sent)
        assertFalse(streamed)
    }

    @Test fun allContributingProjectsNeedOwnerPermissionAndRevocationIsImmediate() = runBlocking {
        var sent = 0
        val allowed = mutableSetOf("p")
        val delegate = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
                sent++; return Result.success("ok")
            }
        }
        var projects = setOf("p", "q")
        val client = ProjectContextClient(delegate, "ollama", "https://remote.example", { false }, { projects }, { it in allowed })
        assertTrue(client.chat("remote", emptyList()).isFailure)
        allowed += "q"
        assertTrue(client.chat("remote", emptyList()).isSuccess)
        allowed -= "p"
        assertTrue(client.chat("remote", emptyList()).isFailure)
        projects = emptySet()
        assertTrue(client.chat("remote", emptyList()).isSuccess)
        assertEquals(2, sent)
    }

    @Test fun embeddedAndAttestedLoopbackWorkOfflineButCloudAliasDoesNot() = runBlocking {
        var sent = 0
        val delegate = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>) = Result.success("ok").also { sent++ }
        }
        fun client(backend: String, url: String, attested: Boolean) = ProjectContextClient(delegate, backend, url,
            { attested }, { setOf("p") }, { false })
        assertTrue(client("embedded", "", false).chat("gemma", emptyList()).isSuccess)
        assertTrue(client("ollama", "http://127.0.0.1:11434", true).chat("gemma", emptyList()).isSuccess)
        assertTrue(client("ollama", "http://127.0.0.1:11434", true).chat("gemma-cloud", emptyList()).isFailure)
        assertTrue(client("ollama", "http://127.0.0.1:11434", false).chat("gemma", emptyList()).isFailure)
        assertEquals(2, sent)
    }
}
