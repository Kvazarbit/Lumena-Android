package com.lumena.android.settings

import android.content.Context
import com.lumena.android.llama.LlamaTuning

data class LumenaConnectionSettings(
    val bridgeUrl: String = DEFAULT_BRIDGE_URL,
    val bridgeToken: String = "",
    val ollamaUrl: String = DEFAULT_OLLAMA_URL,
    val selectedModel: String = "",
    val companionAutoReturn: Boolean = true,
    val companionSafeAuto: Boolean = true,
    val inferenceBackend: String = "ollama",
    val ggufPath: String = "",
    val computeMode: String = "auto"
) {
    companion object {
        const val DEFAULT_BRIDGE_URL = "http://127.0.0.1:8765"
        const val DEFAULT_OLLAMA_URL = "http://127.0.0.1:11434"
    }
}

/**
 * One app-wide source of connection settings for Companion, Local and Tools.
 *
 * The bridge token is stored only in Lumena's private app preferences. The bridge
 * itself is loopback-only, so the token is never intentionally exposed to Wi-Fi.
 */
object LumenaPreferences {
    fun loadTuning(context: Context): LlamaTuning {
        val stored = prefs(context)
        return LlamaTuning(
            stored.getInt("llama_context", 0), stored.getInt("llama_batch", 0),
            stored.getInt("llama_threads", 0), stored.getInt("llama_response", 0),
            stored.getInt("llama_extra_ram_mb", 0)
        ).normalized()
    }

    fun saveTuning(context: Context, value: LlamaTuning) {
        val safe = value.normalized()
        prefs(context).edit()
            .putInt("llama_context", safe.contextTokens)
            .putInt("llama_batch", safe.batchTokens)
            .putInt("llama_threads", safe.cpuThreads)
            .putInt("llama_response", safe.responseTokens)
            .putInt("llama_extra_ram_mb", safe.extraRamMb).apply()
    }

    private const val FILE = "lumena_settings"
    private const val LEGACY_COMPANION_FILE = "lumena_companion"

    private const val KEY_BRIDGE_URL = "bridge_url"
    private const val KEY_BRIDGE_TOKEN = "bridge_token"
    private const val KEY_OLLAMA_URL = "ollama_url"
    private const val KEY_SELECTED_MODEL = "selected_model"
    private const val KEY_COMPANION_AUTO_RETURN = "companion_auto_return"
    private const val KEY_COMPANION_SAFE_AUTO = "companion_safe_auto"
    private const val KEY_LEGACY_MIGRATED = "legacy_companion_migrated"
    private const val KEY_INFERENCE_BACKEND = "inference_backend"
    private const val KEY_GGUF_PATH = "gguf_path"
    private const val KEY_COMPUTE_MODE = "compute_mode"

    fun load(context: Context): LumenaConnectionSettings {
        val app = context.applicationContext
        migrateLegacyCompanionIfNeeded(app)
        val prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val bridgeUrl = prefs.getString(KEY_BRIDGE_URL, LumenaConnectionSettings.DEFAULT_BRIDGE_URL)
            ?: LumenaConnectionSettings.DEFAULT_BRIDGE_URL
        val storedOllamaUrl = prefs.getString(KEY_OLLAMA_URL, LumenaConnectionSettings.DEFAULT_OLLAMA_URL)
            ?: LumenaConnectionSettings.DEFAULT_OLLAMA_URL
        // Older builds could leave the Termux bridge URL in the Ollama field.
        // Sending Ollama /api/chat to port 8765 produces the misleading bridge 404 seen in logs.
        val ollamaUrl = if (storedOllamaUrl.trimEnd('/') == bridgeUrl.trimEnd('/')) {
            LumenaConnectionSettings.DEFAULT_OLLAMA_URL
        } else {
            storedOllamaUrl
        }
        if (ollamaUrl != storedOllamaUrl) {
            prefs.edit().putString(KEY_OLLAMA_URL, ollamaUrl).apply()
        }
        val storedBridgeToken = prefs.getString(KEY_BRIDGE_TOKEN, "") ?: ""
        val bridgeToken = normalizeBridgeToken(storedBridgeToken)
        if (bridgeToken != storedBridgeToken) {
            prefs.edit().putString(KEY_BRIDGE_TOKEN, bridgeToken).apply()
        }

        return LumenaConnectionSettings(
            bridgeUrl = bridgeUrl,
            bridgeToken = bridgeToken,
            ollamaUrl = ollamaUrl,
            selectedModel = prefs.getString(KEY_SELECTED_MODEL, "") ?: "",
            companionAutoReturn = prefs.getBoolean(KEY_COMPANION_AUTO_RETURN, true),
            companionSafeAuto = prefs.getBoolean(KEY_COMPANION_SAFE_AUTO, true),
            inferenceBackend = prefs.getString(KEY_INFERENCE_BACKEND, "ollama") ?: "ollama",
            ggufPath = prefs.getString(KEY_GGUF_PATH, "") ?: "",
            computeMode = (prefs.getString(KEY_COMPUTE_MODE, "auto") ?: "auto")
                .takeIf { it in setOf("auto", "cpu", "gpu") } ?: "auto"
        )
    }

    fun saveBridgeUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_BRIDGE_URL, value.trim()).apply()
    }

    fun saveBridgeToken(context: Context, value: String) {
        prefs(context).edit().putString(KEY_BRIDGE_TOKEN, normalizeBridgeToken(value)).apply()
    }

    fun normalizeBridgeToken(value: String): String =
        value.replace("\r", "").replace("\n", "").trim()

    fun saveOllamaUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_OLLAMA_URL, value.trim()).apply()
    }

    fun saveSelectedModel(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SELECTED_MODEL, value.trim()).apply()
    }

    fun saveInferenceBackend(context: Context, value: String) {
        prefs(context).edit().putString(KEY_INFERENCE_BACKEND, value).apply()
    }

    fun saveGgufPath(context: Context, value: String) {
        prefs(context).edit().putString(KEY_GGUF_PATH, value.trim()).apply()
    }

    fun saveComputeMode(context: Context, value: String) {
        val normalized = value.takeIf { it in setOf("auto", "cpu", "gpu") } ?: "auto"
        prefs(context).edit().putString(KEY_COMPUTE_MODE, normalized).apply()
    }

    fun saveCompanionAutoReturn(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_COMPANION_AUTO_RETURN, value).apply()
    }

    fun saveCompanionSafeAuto(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_COMPANION_SAFE_AUTO, value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun migrateLegacyCompanionIfNeeded(context: Context) {
        val target = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (target.getBoolean(KEY_LEGACY_MIGRATED, false)) return

        val legacy = context.getSharedPreferences(LEGACY_COMPANION_FILE, Context.MODE_PRIVATE)
        val editor = target.edit()
        if (!target.contains(KEY_BRIDGE_URL)) {
            legacy.getString(KEY_BRIDGE_URL, null)?.takeIf { it.isNotBlank() }?.let {
                editor.putString(KEY_BRIDGE_URL, it)
            }
        }
        if (!target.contains(KEY_BRIDGE_TOKEN)) {
            legacy.getString(KEY_BRIDGE_TOKEN, null)?.takeIf { it.isNotBlank() }?.let {
                editor.putString(KEY_BRIDGE_TOKEN, it)
            }
        }
        editor.putBoolean(KEY_LEGACY_MIGRATED, true).apply()
    }
}
