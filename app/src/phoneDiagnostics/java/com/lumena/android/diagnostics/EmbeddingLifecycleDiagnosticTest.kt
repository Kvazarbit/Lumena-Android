package com.lumena.android.diagnostics

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lumena.android.agent.core.ModuleRegistry
import com.lumena.android.llama.LiteRtLmEmbeddingProvider
import com.lumena.android.llama.SemanticLiteRtModelStore
import com.lumena.android.ollama.LocalEmbeddingIdentity
import com.lumena.android.settings.CognitivePrincipleStage
import com.lumena.android.settings.ModuleSettings
import com.lumena.android.settings.SemanticDocument
import com.lumena.android.settings.SemanticMemoryStore
import com.lumena.android.settings.SemanticMemorySettings
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.lumena.android.ui.SemanticMemoryPanel
import java.io.File
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Compose + Android preferences/files; no native inference is simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmbeddingLifecycleDiagnosticTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val digest = "a".repeat(64)
    private lateinit var storedCopy: File

    @Before fun prepareImportedSelection() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        ModuleSettings.setEnabled(context, "semantic-memory", true)
        SemanticMemorySettings.setBackend(context, "ollama")
        // Seed the exact durable state *after* a successful import. These bytes
        // are a fixture, NOT valid weights; never call metadata or inference.
        val root = File(context.filesDir, "semantic-litert-models").apply { mkdirs() }
        storedCopy = File(root, "embedding-$digest.litertlm")
        RandomAccessFile(storedCopy, "rw").use { it.setLength(SemanticLiteRtModelStore.MIN_FILE_BYTES) }
        assertTrue(context.getSharedPreferences("lumena_litert_semantic_model", Context.MODE_PRIVATE)
            .edit().putString("sha256", digest).commit())
        assertEquals(storedCopy.canonicalFile, SemanticLiteRtModelStore.selected(context))
    }

    @After fun cleanUp() { ModuleRegistry.setDisabled(emptySet()) }

    @Test fun closingSettingsMustRetainSelectedLiteRtBackend() {
        val shown = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (shown.value) SemanticMemoryPanel("http://127.0.0.1:11434")
                }
            }
        }
        compose.onNodeWithText("LiteRT CPU").performScrollTo().performClick()
        compose.onNodeWithText("✓ LiteRT CPU").assertExists()
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.onNodeWithText("✓ LiteRT CPU").assertDoesNotExist()
        compose.runOnIdle { shown.value = true }
        compose.waitForIdle()
        assertEquals("MODEL_COPY_LOST", storedCopy.canonicalFile,
            SemanticLiteRtModelStore.selected(context))
        compose.onNodeWithText("✓ LiteRT CPU").assertExists("BACKEND_RESET_ON_SETTINGS_CLOSE")
    }

    @Test fun retainedPanelRestoresBackendThroughSavedState() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SemanticMemoryPanel("http://127.0.0.1:11434")
                }
            }
        }
        compose.onNodeWithText("LiteRT CPU").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("✓ LiteRT CPU").assertExists()
        assertEquals(storedCopy.canonicalFile, SemanticLiteRtModelStore.selected(context))
    }

    @Test fun selectedFileAndOptInSurviveNewContextAndProviderClose() {
        LiteRtLmEmbeddingProvider(context).close() // No engine was opened.
        val recreatedContext = context.createConfigurationContext(context.resources.configuration)
        assertEquals("PROVIDER_CLOSE_DELETED_SELECTION", storedCopy.canonicalFile,
            SemanticLiteRtModelStore.selected(recreatedContext))
        assertFalse("OWNER_OPT_IN_LOST", "semantic-memory" in ModuleSettings.disabled(recreatedContext))
        assertTrue(storedCopy.isFile)
    }

    @Test fun missingFileIsReportedWithoutDeletingSelectionMetadata() {
        assertTrue(storedCopy.delete()) // Only this test's fake sandbox file.
        assertNull(SemanticLiteRtModelStore.selected(context))
        assertEquals(digest, context.getSharedPreferences("lumena_litert_semantic_model", Context.MODE_PRIVATE)
            .getString("sha256", null))
    }

    @Test fun cachedVectorsSurviveProviderCloseAndNewContext() {
        val model = LocalEmbeddingIdentity(LiteRtLmEmbeddingProvider.MODEL_ID, digest, storedCopy.length())
        val document = SemanticDocument("fixture-principle", CognitivePrincipleStage.NEGATIVE_DEAD_END,
            null, "Synthetic structured principle", "b".repeat(64), 1)
        // Synthetic vectors test SQLite durability only, never native quality.
        assertEquals(1, SemanticMemoryStore.upsert(context, model, listOf(document), listOf(floatArrayOf(1f, 0f))))
        LiteRtLmEmbeddingProvider(context).close()
        val recreated = context.createConfigurationContext(context.resources.configuration)
        assertEquals(1, SemanticMemoryStore.stats(recreated).rows)
        assertEquals(listOf(document.id), SemanticMemoryStore.search(recreated, model,
            floatArrayOf(1f, 0f), listOf(document)).map { it.id })
    }
}
