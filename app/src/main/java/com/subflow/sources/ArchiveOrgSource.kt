package com.subflow.sources

import com.subflow.models.LangCatalog
import com.subflow.models.Release
import com.subflow.models.SubtitleCandidate
import com.subflow.utils.Net
import org.json.JSONObject

// general archive search. sometimes has snapshots of dead sub sites, srt downloads directly.
class ArchiveOrgSource : SubtitleSource {
    override val name = "Archive.org"
    override val tier = 3

    override suspend fun search(release: Release, log: suspend (String) -> Unit): List<SubtitleCandidate> {
        val q = java.net.URLEncoder.encode("\"${release.title}\" subtitle", "UTF-8")
        val url = "https://archive.org/advancedsearch.php?q=$q&fl%5B%5D=identifier&fl%5B%5D=title&rows=5&output=json"
        val body = Net.getString(url) ?: return emptyList()
        val docs = runCatching {
            JSONObject(body).getJSONObject("response").getJSONArray("docs")
        }.getOrNull() ?: return emptyList()

        val out = mutableListOf<SubtitleCandidate>()
        for (i in 0 until docs.length()) {
            val doc = docs.optJSONObject(i) ?: continue
            val identifier = doc.optString("identifier")
            if (identifier.isBlank()) continue
            val meta = Net.getString("https://archive.org/metadata/$identifier/files") ?: continue
            val files = runCatching { JSONObject(meta).getJSONArray("result") }.getOrNull() ?: continue
            for (j in 0 until files.length()) {
                val f = files.optJSONObject(j) ?: continue
                val fname = f.optString("name")
                val lower = fname.lowercase()
                if (!lower.endsWith(".srt") && !lower.endsWith(".ass")) continue
                val target = release.targetLang
                val target3 = LangCatalog.threeLetter(target)
                // only a delimited lang token counts. "tur" alone false-positives on "Futurama"
                val langTokenRegex = Regex(
                    "(?:^|[._\\-\\s(\\[])(?:$target|$target3|${Regex.escape(release.targetLangName.lowercase())})(?:$|[._\\-\\s)\\]])"
                )
                val lang = if (langTokenRegex.containsMatchIn(lower)) target else "en"
                out += SubtitleCandidate(
                    sourceName = name, title = fname, language = lang,
                    downloadUrl = "https://archive.org/download/$identifier/" +
                        java.net.URLEncoder.encode(fname, "UTF-8").replace("+", "%20"),
                    score = matchScore(release, fname) + if (lang == target) 5 else 0
                )
            }
            if (out.size >= 10) break
        }
        return out.sortedByDescending { it.score }.take(10)
    }
}
