package com.subflow.data

import android.content.Context

// on-device user prefs, backed by SharedPreferences
object AppSettings {

    private const val PREFS = "subflow_settings"
    private const val KEY_TARGET_LANG = "default_target_lang"
    private const val KEY_AUTO_SAVE = "auto_save"

    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    var defaultTargetLang: String
        get() = prefs.getString(KEY_TARGET_LANG, "tr") ?: "tr"
        set(value) = prefs.edit().putString(KEY_TARGET_LANG, value).apply()

    // on = auto-write every subtitle to Downloads/SubFlow
    var autoSave: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SAVE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SAVE, value).apply()

    var haptics: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    // versionCode of the last "what's new" the user saw
    var lastSeenVersion: Int
        get() = prefs.getInt(KEY_SEEN_VERSION, 0)
        set(value) = prefs.edit().putInt(KEY_SEEN_VERSION, value).apply()

    private const val KEY_HAPTICS = "haptics"
    private const val KEY_SEEN_VERSION = "seen_version"
}
