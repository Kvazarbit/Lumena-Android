package com.lumena.android.companion

import android.content.Context

/** Persist the editable handoff independently of tab/activity recreation. */
object CompanionHandoffStore {
    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences("lumena_companion_handoff", Context.MODE_PRIVATE)

    fun load(context: Context): String = prefs(context).getString("draft", "").orEmpty()

    fun save(context: Context, text: String) {
        prefs(context).edit().putString("draft", text).apply()
    }
}
