package com.lumena.android.modules

import com.lumena.android.agent.core.LumenaModule
import com.lumena.android.agent.core.ModuleManifest

/** No agent tools, model routing, implicit execution, or prompt injection. */
object SemanticMemoryModule : LumenaModule {
    override val manifest = ModuleManifest(
        id = "semantic-memory",
        version = "0",
        title = "EmbeddingGemma 2 — семантична пам'ять · SHADOW",
        description = "Ручне зіставлення перевірених принципів у локальному Ollama. " +
            "Без автозапуску, без ToolGate повноважень і без передачі даних у хмару.",
        tools = emptyList(),
        stateFiles = emptyList(),
        requiresBridge = null
    )
}
