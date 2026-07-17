package com.subflow.pipeline

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * EN to TR phrase dictionary applied over MT output. MT leaves profanity
 * untranslated or softens it, this restores register. Entries are uncensored
 * on purpose. Longest phrases match first ("son of a bitch" before "bitch")
 * and matches are word-bounded so "class" isn't hit by the "ass" entry.
 */
object MegaDictionary {

    // loaded on a bg thread, read from the translation coroutine, so publish safely
    @Volatile private var entries: List<Pair<Regex, String>> = emptyList()

    fun load(context: Context) {
        try {
            val json = context.assets.open("mega_dictionary.json")
                .bufferedReader()
                .readText()
            val parsed = JSONObject(json)
            entries = parsed.keys().asSequence()
                .map { key -> key to parsed.getString(key) }
                .sortedByDescending { it.first.length } // longest phrase wins
                .map { (key, value) ->
                    Regex("\\b${Regex.escape(key)}\\b", RegexOption.IGNORE_CASE) to value
                }
                .toList()
            Log.d("SubFlow", "Mega Dictionary loaded: ${entries.size} entries")
        } catch (e: Exception) {
            Log.e("SubFlow", "Mega Dictionary FAILED to load: ${e.message}")
        }
    }

    val size: Int get() = entries.size

    fun apply(lines: List<String>): List<String> {
        if (entries.isEmpty()) {
            Log.w("SubFlow", "Dictionary empty — apply skipped")
            return lines
        }
        var hits = 0
        val result = lines.map { line ->
            var out = line
            for ((regex, replacement) in entries) {
                if (regex.containsMatchIn(out)) {
                    out = regex.replace(out, replacement)
                    hits++
                }
            }
            out
        }
        if (hits > 0) Log.d("SubFlow", "Mega Dictionary: $hits replacement(s) in batch")
        return result
    }
}
