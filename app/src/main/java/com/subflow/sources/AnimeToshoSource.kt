package com.subflow.sources

import com.subflow.models.ContentType
import com.subflow.models.Release
import com.subflow.models.SubtitleCandidate
import com.subflow.utils.Net
import org.json.JSONArray

// extracts subtitle tracks from fansub torrents and serves them as direct
// downloads (.ass.xz/.srt.xz). usually english, so output goes to translation.
class AnimeToshoSource : SubtitleSource {
    override val name = "AnimeTosho"
    override val tier = 1

    override fun prefers(release: Release) =
        release.type == ContentType.ANIME || release.type == ContentType.DONGHUA

    private val attachRegex = Regex(
        """href="(https://animetosho\.org/storage/attach/[^"]+?\.(?:ass|srt|ssa)(?:\.xz)?)"[^>]*>([^<]*)""",
        RegexOption.IGNORE_CASE
    )

    // track codes embedded in the filename
    private val iso3to2 = mapOf(
        "eng" to "en", "jpn" to "ja", "jap" to "ja", "chi" to "zh", "zho" to "zh",
        "ara" to "ar", "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de",
        "spa" to "es", "por" to "pt", "rus" to "ru", "kor" to "ko", "ita" to "it",
        "tur" to "tr", "vie" to "vi", "ind" to "id", "tha" to "th", "pol" to "pl",
        "dut" to "nl", "nld" to "nl"
    )

    // language from the url token first, then the label
    private fun langOf(url: String, label: String): String {
        Regex("""[._]([a-z]{2,3})\.(?:ass|srt|ssa)""").find(url.lowercase())?.groupValues?.get(1)?.let { tok ->
            iso3to2[tok]?.let { return it }
            if (tok.length == 2) return tok
        }
        val l = label.lowercase()
        return when {
            l.contains("turk") || l.contains("türk") -> "tr"
            l.contains("english") -> "en"
            l.contains("japanese") -> "ja"
            l.contains("chinese") -> "zh"
            l.contains("korean") -> "ko"
            l.contains("arabic") -> "ar"
            l.contains("french") -> "fr"
            l.contains("german") -> "de"
            l.contains("spanish") -> "es"
            else -> "en" // fansub attachments skew english
        }
    }

    private fun langBonus(lang: String, target: String): Int = when (lang) {
        target -> 20
        "en" -> 10
        "ja" -> 5
        else -> 0
    }

    override suspend fun search(release: Release, log: suspend (String) -> Unit): List<SubtitleCandidate> {
        if (release.type != ContentType.ANIME && release.type != ContentType.DONGHUA) return emptyList()

        val q = java.net.URLEncoder.encode(release.title, "UTF-8")
        val json = Net.getString("https://feed.animetosho.org/json?q=$q&limit=25") ?: return emptyList()
        val entries = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()

        // rank by name match, only fetch the best few view pages
        data class Entry(val title: String, val link: String)
        val ranked = (0 until entries.length())
            .mapNotNull { i ->
                val o = entries.optJSONObject(i) ?: return@mapNotNull null
                val title = o.optString("title").ifBlank { return@mapNotNull null }
                val link = o.optString("link").ifBlank { return@mapNotNull null }
                Entry(title, link)
            }
            .filter { matchScore(release, it.title) >= 2 }
            .sortedByDescending { matchScore(release, it.title) }
            .take(4)

        val all = mutableListOf<SubtitleCandidate>()
        for (entry in ranked) {
            val page = Net.getString(entry.link) ?: continue
            for (m in attachRegex.findAll(page)) {
                val url = m.groupValues[1]
                val lang = langOf(url, m.groupValues[2])
                all += SubtitleCandidate(
                    sourceName = name,
                    title = entry.title,
                    language = lang,
                    downloadUrl = url,
                    score = matchScore(release, entry.title) + langBonus(lang, release.targetLang)
                )
            }
            if (all.size >= 12) break
        }
        // keep target/en/ja so the download budget isn't wasted. fall back to
        // everything only when none of those are present.
        val useful = all.filter { it.language == release.targetLang || it.language == "en" || it.language == "ja" }
        val chosen = if (useful.isNotEmpty()) useful else all
        return chosen.distinctBy { it.downloadUrl }.sortedByDescending { it.score }.take(6)
    }
}
