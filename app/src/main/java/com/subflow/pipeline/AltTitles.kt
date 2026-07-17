package com.subflow.pipeline

import com.subflow.models.ContentType
import com.subflow.models.Release
import com.subflow.utils.Net
import org.json.JSONObject

/**
 * Alternate titles a release is known by, so a search in one language matches
 * files named in another. Anime via AniList, live-action via TVMaze, both keyless.
 * Only ever adds names, never blocks a search. Any failure returns empty and the
 * pipeline runs on the typed title alone. Cached per process.
 */
object AltTitles {

    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    suspend fun resolve(release: Release): List<String> {
        if (release.title.isBlank()) return emptyList()
        val key = "${release.type.name}:${release.title.lowercase()}"
        cache[key]?.let { return it }

        val titles = runCatching {
            when (release.type) {
                ContentType.ANIME, ContentType.DONGHUA -> fromAniList(release.title)
                ContentType.SERIES, ContentType.ANIMATION -> fromTvMaze(release.title)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
            .filter { it.isNotBlank() && !it.equals(release.title, ignoreCase = true) }
            .filter { latin(it) } // files are named in Latin script, skip CJK synonyms
            .distinctBy { it.lowercase() }
            .take(4)

        cache[key] = titles
        return titles
    }

    private fun latin(s: String): Boolean = s.count { it.code in 0x41..0x24F } > s.length / 2

    /** strip diacritics before comparing */
    private fun stripAccents(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    // aniList search is spelling-sensitive, so try the full title then shorter prefixes.
    private fun aniListQueries(title: String): List<String> {
        val words = stripAccents(title).trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return buildList {
            add(words.joinToString(" "))                                    // full
            if (words.size >= 3) add(words.dropLast(1).joinToString(" "))   // drop last word, often misspelled
            if (words.size >= 2) add(words.take(2).joinToString(" "))       // first two words
        }.filter { it.isNotBlank() }.distinct()
    }

    private suspend fun fromAniList(title: String): List<String> {
        for (q in aniListQueries(title)) {
            val out = aniListLookup(q)
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    private suspend fun aniListLookup(title: String): List<String> {
        val query = JSONObject()
            .put("query", "query(\$s:String){Media(search:\$s,type:ANIME){title{romaji english}synonyms}}")
            .put("variables", JSONObject().put("s", title))
            .toString()
        val body = Net.postJson("https://graphql.anilist.co", query) ?: return emptyList()
        val media = JSONObject(body).optJSONObject("data")?.optJSONObject("Media") ?: return emptyList()
        val out = mutableListOf<String>()
        media.optJSONObject("title")?.let { t ->
            t.optString("romaji").takeIf { it.isNotBlank() }?.let { out += it }
            t.optString("english").takeIf { it.isNotBlank() && it != "null" }?.let { out += it }
        }
        media.optJSONArray("synonyms")?.let { syn ->
            for (i in 0 until syn.length()) out += syn.optString(i)
        }
        return out
    }

    private suspend fun fromTvMaze(title: String): List<String> {
        val q = java.net.URLEncoder.encode(title, "UTF-8")
        val body = Net.getString("https://api.tvmaze.com/singlesearch/shows?q=$q") ?: return emptyList()
        val show = JSONObject(body)
        val out = mutableListOf<String>()
        show.optString("name").takeIf { it.isNotBlank() }?.let { out += it }
        val id = show.optLong("id", -1)
        if (id > 0) {
            Net.getString("https://api.tvmaze.com/shows/$id/akas")?.let { akasBody ->
                runCatching {
                    val arr = org.json.JSONArray(akasBody)
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.optString("name")?.let { out += it }
                    }
                }
            }
        }
        return out
    }
}
