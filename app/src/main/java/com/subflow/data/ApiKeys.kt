package com.subflow.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Free API keys from subdl/opensubtitles/subsource. On-device only.
 * A blank key just skips that source in the cascade.
 *
 * These are the only secrets the app holds, so they get their own SharedPreferences
 * file instead of sitting alongside [AppSettings]: a separate file is what lets the
 * backup rules drop the keys without dropping everything else
 * (res/xml/backup_rules.xml, res/xml/data_extraction_rules.xml).
 * [PREFS_FILE] is the one name those rules and their test refer back to.
 */
object ApiKeys {

    const val PREFS = "subflow_api_keys"

    /** on-disk name of [PREFS], as the backup rules have to spell it. */
    const val PREFS_FILE = "$PREFS.xml"

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
