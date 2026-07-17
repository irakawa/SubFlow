package com.subflow.sources

import com.subflow.models.ContentType
import com.subflow.models.Release
import com.subflow.models.SubtitleCandidate
import com.subflow.utils.Net
import org.json.JSONObject

/**
 * SubSource REST API. Needs a free key from subsource.net/api-docs, sent as a
 * bearer token. Skipped without one. Good Turkish coverage.
 */
class SubSourceSource(private val apiKey: String? = null) : SubtitleSource {
    override val name = "SubSource"
    override val tier = 1

    private val api = "https://api.subsource.net/api"
    private val auth: Map<String, String>
        get() = apiKey?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    override fun prefers(release: Release) =
        release.type == ContentType.FILM || release.type == ContentType.SERIES

    override suspend fun search(release: Release, log: suspend (String) -> Unit): List<SubtitleCandidate> {
        if (apiKey == null) return emptyList() // no key, skip
        val searchBody = Net.postJson(
            "$api/searchMovie",
            JSONObject().put("query", release.title).toString(),
            auth
        ) ?: return emptyList()
        val found = runCatching { JSONObject(searchBody).optJSONArray("found") }.getOrNull()
            ?: return emptyList()
        if (found.length() == 0) return emptyList()

        val first = found.optJSONObject(0) ?: return emptyList()
        val linkName = first.optString("linkName")
        if (linkName.isBlank()) return emptyList()

        val targetName = release.targetLangName.lowercase()
        val movieReq = JSONObject().put("movieName", linkName)
            .put("langs", org.json.JSONArray().put(targetName).put("english"))
        if (release.season != null) movieReq.put("season", "season-${release.season}")
        val movieBody = Net.postJson("$api/getMovie", movieReq.toString(), auth) ?: return emptyList()
        val subs = runCatching { JSONObject(movieBody).optJSONArray("subs") }.getOrNull() ?: return emptyList()

        val out = mutableListOf<SubtitleCandidate>()
        for (i in 0 until subs.length()) {
            val s = subs.optJSONObject(i) ?: continue
            val lang = s.optString("lang").lowercase()
            if (lang != targetName && lang != "english") continue
            val relName = s.optString("releaseName")
            val subId = s.optString("subId").ifBlank { s.optLong("id").toString() }
            out += SubtitleCandidate(
                sourceName = name, title = relName,
                language = if (lang == targetName) release.targetLang else "en",
                pageUrl = "https://subsource.net/subtitle/$linkName/$subId",
                downloadUrl = "$api/downloadSub/$subId",
                headers = auth,
                score = matchScore(release, relName) + if (lang == targetName) 10 else 0
            )
        }
        return out.sortedByDescending { it.score }
    }
}
