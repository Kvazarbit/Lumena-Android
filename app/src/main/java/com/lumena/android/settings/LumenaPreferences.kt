package com.lumena.android.settings

import android.content.Context

data class LumenaConnectionSettings(
    val bridgeUrl: String = DEFAULT_BRIDGE_URL,
    val bridgeToken: String = "",
    val ollamaUrl: String = DEFAULT_OLLAMA_URL,
    val selectedModel: String = "",
    val companionAutoReturn: Boolean = true
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
    private const val FILE = "lumena_settings"
    private const val LEGACY_COMPANION_FILE = "lumena_companion"

    private const val KEY_BRIDGE_URL = "bridge_url"
    private const val KEY_BRIDGE_TOKEN = "bridge_token"
    private const val KEY_OLLAMA_URL = "ollama_url"
    private const val KEY_SELECTED_MODEL = "selected_model"
    private const val KEY_COMPANION_AUTO_RETURN = "companion_auto_return"
    private const val KEY_LEGACY_MIGRATED = "legacy_companion_migrated"

    fun load(context: Context): LumenaConnectionSettings {
        val app = context.applicationContext
        migrateLegacyCompanionIfNeeded(app)
        val prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return LumenaConnectionSettings(
            bridgeUrl = prefs.getString(KEY_BRIDGE_URL, LumenaConnectionSettings.DEFAULT_BRIDGE_URL)
                ?: LumenaConnectionSettings.DEFAULT_BRIDGE_URL,
            bridgeToken = prefs.getString(KEY_BRIDGE_TOKEN, "") ?: "",
            ollamaUrl = prefs.getString(KEY_OLLAMA_URL, LumenaConnectionSettings.DEFAULT_OLLAMA_URL)
                ?: LumenaConnectionSettings.DEFAULT_OLLAMA_URL,
            selectedModel = prefs.getString(KEY_SELECTED_MODEL, "") ?: "",
            companionAutoReturn = prefs.getBoolean(KEY_COMPANION_AUTO_RETURN, true)
        )
    }

    fun saveBridgeUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_BRIDGE_URL, value.trim()).apply()
    }

    fun saveBridgeToken(context: Context, value: String) {
        prefs(context).edit().putString(KEY_BRIDGE_TOKEN, value.trim()).apply()
    }

    fun saveOllamaUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_OLLAMA_URL, value.trim()).apply()
    }

    fun saveSelectedModel(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SELECTED_MODEL, value.trim()).apply()
    }

    fun saveCompanionAutoReturn(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_COMPANION_AUTO_RETURN, value).apply()
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
