package com.subflow.data

import android.content.Context
import android.content.SharedPreferences

// free API keys from subdl/opensubtitles/subsource, stored on-device only.
// a blank key just skips that source in the cascade.
object ApiKeys {

    private const val PREFS = "subflow_api_keys"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    var subdl: String
        get() = prefs?.getString("subdl", "") ?: ""
        set(v) { prefs?.edit()?.putString("subdl", v.trim())?.apply() }

    var openSubtitles: String
        get() = prefs?.getString("opensubtitles", "") ?: ""
        set(v) { prefs?.edit()?.putString("opensubtitles", v.trim())?.apply() }

    var subsource: String
        get() = prefs?.getString("subsource", "") ?: ""
        set(v) { prefs?.edit()?.putString("subsource", v.trim())?.apply() }

    fun orNull(value: String): String? = value.ifBlank { null }
}
