package com.subflow.sources

import com.subflow.models.ContentType
import com.subflow.models.Release
import com.subflow.models.SubtitleCandidate
import com.subflow.utils.ImdbLookup
import com.subflow.utils.Net
import org.jsoup.Jsoup

/**
 * Films, good multi-language coverage. Site search is JS-only now, so resolve to an
 * IMDb id and open the movie page directly. Downloads need a Referer or they 403.
 */
class YifySubtitlesSource : SubtitleSource {
    override val name = "YifySubtitles"
    override val tier = 3

    private val mirrors = listOf("https://yifysubtitles.ch", "https://yts-subs.com")

    override fun prefers(release: Release) = release.type == ContentType.FILM

    override suspend fun search(release: Release, log: suspend (String) -> Unit): List<SubtitleCandidate> {
        if (release.type != ContentType.FILM) return emptyList()
        val imdb = ImdbLookup.idFor(release.title, release.year) ?: return emptyList()
        for (base in mirrors) {
            val movieHtml = Net.getString("$base/movie-imdb/$imdb") ?: continue
            val movieDoc = Jsoup.parse(movieHtml, base)
            val out = mutableListOf<SubtitleCandidate>()
            for (row in movieDoc.select("table tbody tr")) {
                val lang = row.selectFirst("span.sub-lang")?.text()?.lowercase() ?: continue
                val language = when {
                    lang.contains(release.targetLangName.lowercase()) -> release.targetLang
                    lang.contains("english") -> "en"
                    else -> continue
                }
                val a = row.selectFirst("a[href*=/subtitles/]") ?: continue
                val title = a.text().removePrefix("subtitle ").trim()
                val page = a.absUrl("href")
                // /subtitles/xyz maps to the download zip at /subtitle/xyz.zip
                val dl = page.replace("/subtitles/", "/subtitle/") + ".zip"
                out += SubtitleCandidate(
                    sourceName = name, title = title, language = language,
                    pageUrl = page, downloadUrl = dl,
                    headers = mapOf("Referer" to page), // the .zip 403s without it
                    score = matchScore(release, title) + if (language == release.targetLang) 10 else 0
                )
            }
            if (out.isNotEmpty()) return out.sortedByDescending { it.score }
        }
        return emptyList()
    }
}
