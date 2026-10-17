package com.lumena.android.agent.core

import com.lumena.android.modules.SemanticMemoryModule
import com.lumena.android.settings.ModuleSettings
import com.lumena.android.settings.SemanticMemoryStore
import com.lumena.android.settings.StateArchive
import org.junit.Assert.*
import org.junit.Test

class SemanticMemoryModuleBoundaryTest {
    @Test fun newModuleNeverAutoEnablesOnExistingInstall() {
        assertTrue("semantic-memory" in ModuleSettings.DEFAULT_DISABLED)
        assertTrue("semantic-memory" in ModuleSettings.effectiveDisabled(
            setOf("listing-attention"), optedIn = false
        ))
        assertFalse("semantic-memory" in ModuleSettings.effectiveDisabled(
            emptySet(), optedIn = true
        ))
    }

    @Test fun noRegisteredToolsAndNoExecutionAuthority() {
        assertTrue(SemanticMemoryModule.manifest.tools.isEmpty())
        assertTrue(SemanticMemoryModule.route("запусти код", "запусти код") == null)
        assertTrue(ModuleRegistry.violations().isEmpty())
        assertTrue(ModuleRegistry.all().contains(SemanticMemoryModule))
    }

    @Test fun vectorDatabaseIsPartOfStateVaultAllowlist() {
        assertTrue(SemanticMemoryStore.DATABASE in StateArchive.databases)
        assertTrue("databases/" + SemanticMemoryStore.DATABASE in StateArchive.allowed)
        assertTrue(SemanticMemoryModule.manifest.stateFiles.isEmpty())
    }
}
