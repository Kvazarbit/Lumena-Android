package com.lumena.android.agent.core

import org.junit.Assert.*
import org.junit.Test

class BridgeTransportPolicyTest {
    @Test fun interruptedReadsCanRetryButMutationsAndUnknownToolsCannot() {
        listOf("web.search", "web.read", "http.get", "file.read", "inspect.batch").forEach {
            assertTrue(BridgeTransportPolicy.canRetry(it))
            assertFalse(BridgeTransportPolicy.outcomeUnknown(it))
        }
        listOf("file.write", "python.run", "git.commit", "not-a-tool").forEach {
            assertFalse(BridgeTransportPolicy.canRetry(it))
            assertTrue(BridgeTransportPolicy.outcomeUnknown(it))
        }
    }

    @Test fun localTransportErrorCannotBeDiagnosedAsWebsiteBlock() {
        val advice = RecoveryAdvisor.suggest(
            TaskState("id", null, "Search web", TaskStatus.WAITING_MODEL),
            AgentDecision.ToolCall("http.get", mapOf("url" to "https://example.org"), "read"),
            false, "", "", "Bridge transport: unexpected end of stream"
        ).orEmpty()
        assertTrue(advice.contains("LOCAL bridge"))
        assertTrue(advice.contains("does not prove"))
    }
}
