package com.subflow.sources

import com.subflow.models.ContentType
import com.subflow.models.Release
import com.subflow.models.SubtitleCandidate
import com.subflow.utils.Net
import org.jsoup.Jsoup

/**
 * Fansub torrent index. We never download the torrent, only report results that
 * look like they carry a sub track.
 */
class NyaaSource : SubtitleSource {
    override val name = "Nyaa.si"
    override val tier = 1

    override fun prefers(release: Release) =
        release.type == ContentType.ANIME || release.type == ContentType.DONGHUA

    override suspend fun search(release: Release, log: suspend (String) -> Unit): List<SubtitleCandidate> {
        val targetName = release.targetLangName
        // search the plain title plus episode marker, how a human searches nyaa.
        // sub-word queries like "Title subtitle Turkish" matched nothing.
        val se = when {
            release.season != null && release.episode != null ->
                "S%02dE%02d".format(java.util.Locale.ROOT, release.season, release.episode)
            release.episode != null -> "%02d".format(java.util.Locale.ROOT, release.episode)
            else -> null
        }
        val queries = buildList {
            add(release.title)
            se?.let { add("${release.title} $it") }
            release.altTitles.firstOrNull()?.let { add(it) }
            release.releaseGroup?.let { add("$it ${release.title}") }
        }.distinct()

        val out = mutableListOf<SubtitleCandidate>()
        for (q in queries) {
            // a match may sit past page 1, stop on an empty page
            for (page in 1..3) {
                val html = Net.getString(
                    "https://nyaa.si/?f=0&c=0_0&p=$page&q=" + java.net.URLEncoder.encode(q, "UTF-8")
                ) ?: break
                val doc = Jsoup.parse(html, "https://nyaa.si")
                val rows = doc.select("table.torrent-list tbody tr")
                if (rows.isEmpty()) break
                for (row in rows.take(12)) {
                    val link = row.selectFirst("td:nth-child(2) a:not(.comments)") ?: continue
                    val magnet = row.select("a[href^=magnet:]").firstOrNull()?.attr("href")
                    val title = link.text()
                    val lower = title.lowercase()
                    // accept a fansub group tag or an explicit sub marker. the streamer
                    // extracts the real track and the identity gate already checked episode,
                    // so accept generously rather than drop subbed releases by name.
                    val fansubTag = title.trimStart().startsWith("[")
                    val subMarker = listOf("sub", "srt", "ass", "vostfr", "multi").any { lower.contains(it) }
                    if (!fansubTag && !subMarker) continue
                    if (magnet == null) continue // no magnet, nothing to stream
                    val lang = when {
                        lower.contains(targetName.lowercase()) ||
                            (release.targetLang == "tr" && lower.contains("türkçe")) -> release.targetLang
                        lower.contains("vostfr") -> "fr"
                        lower.contains("pt-br") || lower.contains("[pt") -> "pt"
                        else -> "en" // multi-sub or english fansubs carry an en track to translate
                    }
                    out += SubtitleCandidate(
                        sourceName = name, title = title, language = lang,
                        pageUrl = link.absUrl("href"),
                        magnet = magnet,
                        // bias toward releases most likely to be subbed
                        score = matchScore(release, title) +
                            when { lower.contains("multi") -> 8; subMarker -> 5; else -> 0 }
                    )
                }
                if (out.size >= 6) break
            }
            if (out.isNotEmpty()) break
        }
        return out.distinctBy { it.magnet ?: it.title }.sortedByDescending { it.score }
    }
}
