package com.subflow.sources

import com.subflow.models.ContentType
import com.subflow.models.Release
import com.subflow.models.SubtitleCandidate
import com.subflow.utils.Net
import org.jsoup.Jsoup

/**
 * Series human subs. Falls back to EN when the target lang is missing (feeds translation).
 * Download link needs a Referer header.
 */
class Addic7edSource : SubtitleSource {
    override val name = "Addic7ed"
    override val tier = 1

    private val base = "https://www.addic7ed.com"

    override fun prefers(release: Release) = release.type == ContentType.SERIES

    override suspend fun search(release: Release, log: suspend (String) -> Unit): List<SubtitleCandidate> {
        if (release.season == null || release.episode == null) return emptyList()
        // encode the slug, apostrophes/&/Turkish chars break the path otherwise
        val slug = java.net.URLEncoder.encode(release.title.trim().replace(' ', '_'), "UTF-8")
        val pageUrl = "$base/serie/$slug/${release.season}/${release.episode}/addic7ed"
        val html = Net.getString(pageUrl) ?: return emptyList()
        val doc = Jsoup.parse(html, base)
        val out = mutableListOf<SubtitleCandidate>()
        for (table in doc.select("table.tabel95")) {
            val versionText = table.select("td.NewsTitle").text()
            for (row in table.select("tr")) {
                val langCell = row.selectFirst("td.language") ?: continue
                val langText = langCell.text().lowercase()
                val lang = when {
                    langText.contains(release.targetLangName.lowercase()) -> release.targetLang
                    langText.contains("english") -> "en"
                    else -> continue
                }
                // match on href prefix. the buttonDownload class is unreliable, the live
                // markup ships a duplicate class attr that Jsoup collapses.
                val dl = row.select("a[href^=/updated/]").lastOrNull()
                    ?: row.select("a[href*=/original/]").lastOrNull() ?: continue
                val title = "${release.displayName()} $versionText"
                out += SubtitleCandidate(
                    sourceName = name, title = title, language = lang,
                    pageUrl = pageUrl,
                    downloadUrl = base + dl.attr("href"),
                    headers = mapOf("Referer" to pageUrl),
                    score = matchScore(release, versionText) + if (lang == release.targetLang) 10 else 0
                )
            }
        }
        return out.sortedByDescending { it.score }
    }
}
