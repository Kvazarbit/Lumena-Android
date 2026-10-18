package com.lumena.android.diagnostics

import android.app.Application
import android.content.Context
import com.lumena.android.agent.core.ModuleRegistry
import com.lumena.android.agent.core.TaskState
import com.lumena.android.ollama.*
import com.lumena.android.settings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SemanticMemoryCoordinatorTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val identity = LocalEmbeddingIdentity("embeddinggemma-2", "a".repeat(64), 1_000_000)
    private fun doc(id: String, scope: String? = null) = SemanticDocument(id,
        CognitivePrincipleStage.NEGATIVE_DEAD_END, scope, "structured-$id", SemanticMemoryPolicy.hash(id), 1)
    private class Provider(val identity: LocalEmbeddingIdentity, val failBatch: Int = 0) : LocalEmbeddingProvider, AutoCloseable {
        val inputs = mutableListOf<List<String>>()
        var closed = false
        override suspend fun inspectModel(name: String) = identity
        override suspend fun embed(identity: LocalEmbeddingIdentity, texts: List<String>): List<FloatArray> {
            inputs += texts
            if (inputs.size == failBatch) throw CancellationException("fixture interruption")
            return texts.map { floatArrayOf(1f, 0f) }
        }
        override fun close() { closed = true }
    }
    @Before fun prepare() {
        ModuleSettings.setEnabled(context, "semantic-memory", true)
        SemanticMemorySettings.setBackend(context, "ollama")
        SemanticMemorySettings.setChatEnabled(context, true)
    }
    @After fun cleanup() { ModuleRegistry.setDisabled(emptySet()) }

    @Test fun cancelledIndexRetainsCommittedBatchAndResumesByRevision() = runBlocking {
        val docs = (1..5).map { doc("p$it") }
        val first = Provider(identity, failBatch = 3) // canary, committed batch, interrupted batch.
        val config = SemanticMemorySettings.selection(context)
        try {
            SemanticMemoryCoordinator(context, "http://127.0.0.1:11434", { first }, { docs }).index(config) { _, _ -> }
            fail("Cancellation not propagated")
        } catch (_: CancellationException) { }
        assertTrue(first.closed)
        assertEquals(4, SemanticMemoryStore.stats(context).rows)
        val second = Provider(identity)
        assertEquals(1, SemanticMemoryCoordinator(context, "http://127.0.0.1:11434", { second }, { docs })
            .index(config) { _, _ -> })
        assertEquals(5, SemanticMemoryStore.stats(context).rows)
        assertTrue(second.closed)
    }
    @Test fun recallUsesOnlyCurrentProjectAndGlobalSources() = runBlocking {
        val foreign = doc("foreign", CoordinatorExperiencePolicy.hash("project-b"))
        val current = doc("current", CoordinatorExperiencePolicy.hash("project-a"))
        val global = doc("global")
        val provider = Provider(identity)
        val lines = SemanticMemoryCoordinator(context, "http://127.0.0.1:11434", { provider },
            { listOf(global, current, foreign) }).recall(TaskState("t", "project-a", "Remember experience"))
        assertTrue(lines.any { it.contains("source=current") })
        assertTrue(lines.any { it.contains("source=global") })
        assertFalse(lines.any { it.contains("source=foreign") })
        assertFalse(provider.inputs.flatten().contains(foreign.text))
        assertTrue(provider.closed)
    }
    @Test fun disabledChatRecallDoesNotOpenProvider() = runBlocking {
        SemanticMemorySettings.setChatEnabled(context, false)
        var opened = false
        val result = SemanticMemoryCoordinator(context, "http://127.0.0.1:11434", {
            opened = true; Provider(identity)
        }, { listOf(doc("a")) }).recall(TaskState("t", null, "Hello"))
        assertTrue(result.isEmpty())
        assertFalse(opened)
    }
    @Test fun failedNativeBoundaryPreservesOrdinaryChatWithTruthfulStatus() = runBlocking {
        val result = SemanticMemoryCoordinator(context, "http://127.0.0.1:11434", {
            throw UnsatisfiedLinkError("fixture native boundary")
        }, { listOf(doc("a")) }).recall(TaskState("t", null, "Hello"))
        assertTrue(result.any { it.contains("RUNTIME_UNAVAILABLE") })
        assertFalse(result.any { it.startsWith("SEMANTIC MEMORY") })
    }
    @Test fun persistedBackendAndChatChoiceSurviveNewContext() {
        SemanticMemorySettings.setBackend(context, "litertlm")
        SemanticMemorySettings.setChatEnabled(context, false)
        val recreated = context.createConfigurationContext(context.resources.configuration)
        assertEquals("litertlm", SemanticMemorySettings.selection(recreated).backend)
        assertFalse(SemanticMemorySettings.selection(recreated).chatEnabled)
    }
}
