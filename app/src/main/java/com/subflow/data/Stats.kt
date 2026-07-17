package com.subflow.data

import android.content.Context
import com.subflow.models.ContentType

// lifetime counters for the stats screen. all local, nothing uploaded.
object Stats {

    private const val PREFS = "subflow_stats"
    private const val KEY_FOUND = "found"
    private const val KEY_SEARCHES = "searches"
    private const val MINUTES_SAVED_PER_SUB = 15

    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    val found: Int get() = prefs.getInt(KEY_FOUND, 0)
    val searches: Int get() = prefs.getInt(KEY_SEARCHES, 0)

    fun record(foundCount: Int, type: ContentType) {
        prefs.edit()
            .putInt(KEY_FOUND, found + foundCount)
            .putInt(KEY_SEARCHES, searches + 1)
            .putInt(typeKey(type), typeCount(type) + 1)
            .apply()
    }

    fun typeCount(type: ContentType): Int = prefs.getInt(typeKey(type), 0)

    // null until there's been at least one search
    fun topType(): ContentType? =
        ContentType.entries.maxByOrNull { typeCount(it) }?.takeIf { typeCount(it) > 0 }

    // rough hours saved not hunting subs by hand
    val estHoursSaved: Int get() = (found * MINUTES_SAVED_PER_SUB) / 60

    private fun typeKey(type: ContentType) = "type_${type.name}"
}
