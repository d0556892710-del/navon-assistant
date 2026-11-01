package com.navon.assistant

import android.content.Context

/** Simple persistent storage for the API key and chosen model. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("navon", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString("model", v.trim().ifBlank { DEFAULT_MODEL }).apply()

    companion object {
        // A fast, inexpensive model is a good default for an agent loop that
        // makes many calls. Change it in Settings to opus for harder tasks.
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
    }
}
