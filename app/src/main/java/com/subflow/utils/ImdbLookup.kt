package com.subflow.utils

import com.subflow.models.ContentType
import org.json.JSONObject

// one autocomplete suggestion for the title field
data class TitleSuggestion(
    val title: String,
    val year: Int?,
    val type: ContentType,
    val imdbId: String
)

/**
 * Resolves a title to an IMDb id via IMDb's public suggestion endpoint.
 * No key needed. Some film sources index by IMDb id, not title.
 */
object ImdbLookup {

    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val MISS = "-" // don't re-fetch a known miss

    /** Returns a "tt" id for [title], preferring a [year] match, or null. */
    suspend fun idFor(title: String, year: Int? = null): String? {
        val name = title.lowercase().trim()
        if (name.isBlank()) return null
        val key = "$name|${year ?: ""}" // year is part of the identity, Dune 1984 != Dune 2021
        cache[key]?.let { return it.takeIf { c -> c != MISS } }

        val bucket = name.firstOrNull { it.isLetterOrDigit() } ?: return null
        // endpoint wants %20 for spaces, not URLEncoder's '+'
        val q = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val body = Net.getString("https://v2.sg.media-imdb.com/suggestion/$bucket/$q.json")
        val id = body?.let { parse(it, year) }
        cache[key] = id ?: MISS
        return id
    }

    // Title autocomplete: partial query to a ranked list of real titles (film/series/anime).
    // Same keyless endpoint as idFor. "youjo" -> Saga of Tanya the Evil, "fight" -> Fight Club.
    suspend fun suggest(query: String): List<TitleSuggestion> {
        val name = query.lowercase().trim()
        if (name.length < 2) return emptyList()
        val bucket = name.firstOrNull { it.isLetterOrDigit() } ?: return emptyList()
        val q = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val body = Net.getString("https://v2.sg.media-imdb.com/suggestion/$bucket/$q.json") ?: return emptyList()
        val arr = runCatching { JSONObject(body).optJSONArray("d") }.getOrNull() ?: return emptyList()
        val out = mutableListOf<TitleSuggestion>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (!id.startsWith("tt")) continue // nm ids are people
            val title = o.optString("l").takeIf { it.isNotBlank() } ?: continue
            val kind = o.optString("q").lowercase()
            if (kind == "video game" || kind == "podcast series") continue // not watchable
            val year = o.optInt("y", -1).takeIf { it > 0 }
            val type = if ("series" in kind) ContentType.SERIES else ContentType.FILM
            out += TitleSuggestion(title, year, type, id)
        }
        return out.distinctBy { it.title.lowercase() }.take(7)
    }

    private fun parse(body: String, year: Int?): String? {
        val arr = runCatching { JSONObject(body).optJSONArray("d") }.getOrNull() ?: return null
        var firstTitle: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (!id.startsWith("tt")) continue // nm ids are people, skip
            if (year != null && o.optInt("y", -1) == year) return id // exact year wins
            if (firstTitle == null) firstTitle = id
        }
        return firstTitle
    }
}
