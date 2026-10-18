package com.lumena.android.settings

import android.content.Context
import com.lumena.android.llama.LiteRtLmEmbeddingProvider
import com.lumena.android.llama.SemanticLiteRtModelStore

data class SemanticModelSelection(
    val backend: String, val tag: String, val artifact: String?, val chatEnabled: Boolean
) {
    val requestedModel: String get() = if (backend == "litertlm") LiteRtLmEmbeddingProvider.MODEL_ID else tag
}

/** Device-local configuration; restoring historical state grants no module access. */
object SemanticMemorySettings {
    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences("lumena_semantic_settings", Context.MODE_PRIVATE)
    fun selection(context: Context): SemanticModelSelection {
        val p = prefs(context)
        val model = SemanticLiteRtModelStore.selected(context)
        val backend = p.getString("backend", null)?.takeIf { it in setOf("ollama", "litertlm") }
            ?: if (model != null) "litertlm" else "ollama"
        return SemanticModelSelection(backend, p.getString("tag", "embeddinggemma-2").orEmpty(),
            if (backend == "litertlm") model?.name else null, p.getBoolean("chat", true))
    }
    fun setBackend(context: Context, value: String) {
        require(value in setOf("ollama", "litertlm"))
        check(prefs(context).edit().putString("backend", value).commit())
    }
    fun setTag(context: Context, value: String) {
        check(prefs(context).edit().putString("tag", value.trim().take(100)).commit())
    }
    fun setChatEnabled(context: Context, value: Boolean) {
        check(prefs(context).edit().putBoolean("chat", value).commit())
    }
}
