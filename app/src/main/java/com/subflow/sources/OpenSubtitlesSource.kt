package com.subflow.sources

import com.subflow.R
import com.subflow.models.Release
import com.subflow.models.SubtitleCandidate
import com.subflow.utils.L10n
import com.subflow.utils.Net
import org.json.JSONObject

/**
 * OpenSubtitles REST API. Hash search first, then title+season. Needs an Api-Key or the
 * source is skipped.
 */
class OpenSubtitlesSource(private val apiKey: String? = null) : SubtitleSource {
    override val name = "OpenSubtitles"
    // a key unlocks hash search and full quota, so tier 1
    override val tier = if (apiKey != null) 1 else 2

    private val base = "https://api.opensubtitles.com/api/v1"

    override suspend fun search(release: Release, log: suspend (String) -> Unit): List<SubtitleCandidate> {
        if (apiKey == null) { log(L10n.t(R.string.log_no_key_skip, name)); return emptyList() }
        val headers = buildMap {
            put("Api-Key", apiKey ?: "")
            put("Content-Type", "application/json")
            // the REST API rejects generic browser UAs
            put("User-Agent", "SubFlow/${com.subflow.BuildConfig.VERSION_NAME}")
        }
        val target = release.targetLang
        val queries = buildList {
            release.hash?.let { add("moviehash=$it&languages=$target,en") }
            val q = java.net.URLEncoder.encode(release.title, "UTF-8")
            val se = buildString {
                release.season?.let { append("&season_number=$it") }
                release.episode?.let { append("&episode_number=$it") }
            }
            add("query=$q$se&languages=$target")
            if (target != "en") add("query=$q$se&languages=en")
        }
        val out = mutableListOf<SubtitleCandidate>()
        for (q in queries) {
            val resp = Net.get("$base/subtitles?$q", headers)
            resp.use {
                if (it.code == 401 || it.code == 403) {
                    log(L10n.t(R.string.log_no_key_skip, "OpenSubtitles"))
                    return out
                }
                if (!it.isSuccessful) return@use
                val body = it.body?.string() ?: return@use
                val json = runCatching { JSONObject(body) }.getOrNull() ?: return@use
                val data = json.optJSONArray("data") ?: return@use
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val attrs = item.optJSONObject("attributes") ?: continue
                    val lang = attrs.optString("language").lowercase()
                    // don't mislabel a stray result as english
                    val outLang = when {
                        lang.startsWith(target) -> target
                        lang.startsWith("en") -> "en"
                        else -> null
                    } ?: continue
                    val relName = attrs.optString("release")
                    val files = attrs.optJSONArray("files")
                    val fileId = files?.optJSONObject(0)?.optLong("file_id") ?: 0L
                    if (fileId == 0L) continue
                    out += SubtitleCandidate(
                        sourceName = name, title = relName.ifBlank { attrs.optString("feature_details") },
                        language = outLang,
                        // file_id resolves via a POST to the download endpoint
                        downloadUrl = "$base/download#file_id=$fileId",
                        headers = headers,
                        score = matchScore(release, relName) + if (outLang == target) 10 else 0
                    )
                }
            }
            if (out.any { it.language == target }) break
        }
        return out.sortedByDescending { it.score }
    }

    companion object {
        /** download endpoint needs a POST, resolved separately */
        suspend fun resolveDownload(candidate: SubtitleCandidate): String? {
            val fileId = candidate.downloadUrl?.substringAfter("#file_id=") ?: return null
            val body = Net.postJson(
                "https://api.opensubtitles.com/api/v1/download",
                """{"file_id":$fileId}""",
                candidate.headers
            ) ?: return null
            return runCatching { JSONObject(body).optString("link").ifBlank { null } }.getOrNull()
        }
    }
}
