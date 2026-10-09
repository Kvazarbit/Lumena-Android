package com.lumena.android.settings

import android.content.Context
import com.lumena.android.agent.core.ModuleRegistry

/**
 * Owner's module switches. Only the set of DISABLED module ids is stored, so a
 * newly shipped module starts enabled and the kernel never depends on this
 * file. Not part of StateVault: a lost switch only re-enables a module.
 */
object ModuleSettings {
    private const val PREFS = "lumena_modules"
    private const val KEY_DISABLED = "disabled"

    fun disabled(context: Context): Set<String> =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_DISABLED, emptySet())
            .orEmpty()
            .toSet()

    /** Applies the stored switches to the running kernel. */
    fun apply(context: Context) {
        ModuleRegistry.setDisabled(disabled(context))
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val next = if (enabled) disabled(context) - id else disabled(context) + id
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_DISABLED, next)
            .apply()
        ModuleRegistry.setDisabled(next)
    }
}
