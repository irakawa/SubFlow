package com.subflow.sources

import com.subflow.R
import com.subflow.models.Release
import com.subflow.models.SubtitleCandidate
import com.subflow.utils.L10n
import com.subflow.utils.Net
import org.json.JSONObject

/**
 * Needs an API key. Without one it tries anonymously, logs the error, and moves on.
 */
class SubDLSource(private val apiKey: String? = null) : SubtitleSource {
    override val name = "SubDL"
    // a key unlocks the full api, so bump to tier 1
    override val tier = if (apiKey != null) 1 else 2

    override suspend fun search(release: Release, log: suspend (String) -> Unit): List<SubtitleCandidate> {
        val key = apiKey ?: ""
        val params = buildString {
            append("https://api.subdl.com/api/v1/subtitles?api_key=$key")
            append("&languages=${release.targetLang.uppercase()},EN")
            append("&film_name=" + java.net.URLEncoder.encode(release.title, "UTF-8"))
            release.season?.let { append("&season_number=$it") }
            release.episode?.let { append("&episode_number=$it") }
            release.year?.let { append("&year=$it") }
        }
        val body = Net.getString(params) ?: return emptyList()
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        if (!json.optBoolean("status", false)) {
            if (key.isBlank()) log(L10n.t(R.string.log_no_key_skip, "SubDL"))
            else log("SubDL: " + json.optString("error", "?"))
            return emptyList()
        }
        val subs = json.optJSONArray("subtitles") ?: return emptyList()
        val out = mutableListOf<SubtitleCandidate>()
        for (i in 0 until subs.length()) {
            val s = subs.optJSONObject(i) ?: continue
            val relName = s.optString("release_name")
            val raw = s.optString("language").lowercase()
            // don't mislabel a stray non-target/non-English result as English
            val lang = when {
                raw.startsWith(release.targetLang) -> release.targetLang
                raw.startsWith("en") -> "en"
                else -> null
            } ?: continue
            val url = s.optString("url")
            if (url.isBlank()) continue
            out += SubtitleCandidate(
                sourceName = name, title = relName, language = lang,
                downloadUrl = if (url.startsWith("http")) url else "https://dl.subdl.com$url",
                score = matchScore(release, relName) + if (lang == release.targetLang) 10 else 0
            )
        }
        return out.sortedByDescending { it.score }
    }
}
