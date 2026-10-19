package com.lumena.android.collaboration

import com.lumena.android.ollama.ChatModelClient
import com.lumena.android.ollama.OllamaMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CollaborationReviewTest {
    @Test fun cloudPrivacyCanaryNeverReachesClient() = runBlocking {
        var calls = 0
        val client = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> { calls++; return Result.success("bad") }
        }
        val canary = "SYNTHETIC_PRIVATE_CANARY_NOT_A_SECRET"
        for (route in listOf(Triple("ollama", "http://127.0.0.1:11434", "gemma4:31b-cloud"),
            Triple("ollama", "https://example.com", "gemma"), Triple("ollama", "http://127.0.0.1.evil:11434", "gemma"))) {
            assertTrue("CLOUD_EGRESS", runCatching { CollaborationReview.read(client, route.first, route.second, route.third, true, canary) }.isFailure)
        }
        assertEquals("CLOUD_EGRESS", 0, calls)
    }
    @Test fun localOllamaRequiresOwnerAttestationAndNoToolOrHistoryChannelExists() = runBlocking {
        var seen = emptyList<OllamaMessage>()
        val client = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> { seen = messages; return Result.success("claimed advice") }
        }
        assertTrue(runCatching { CollaborationReview.read(client, "ollama", "http://localhost:11434", "gemma:local", false, "private") }.isFailure)
        assertTrue(seen.isEmpty())
        assertEquals("claimed advice", CollaborationReview.read(client, "ollama", "http://localhost:11434", "gemma:local", true, "8 observed; 3 claimed"))
        assertEquals(2, seen.size)
        assertTrue(seen.first().content.contains("No tools"))
        assertEquals("8 observed; 3 claimed", seen.last().content)
    }
    @Test fun embeddedWorksOfflineWithoutOllamaAttestation() {
        assertTrue(CollaborationReview.localRoute("embedded", "", "embedded-gguf", false))
        assertFalse(CollaborationReview.localRoute("ollama", "http://user@localhost:11434", "gemma", true))
        assertFalse(CollaborationReview.localRoute("unknown", "http://localhost", "gemma", true))
    }
}
