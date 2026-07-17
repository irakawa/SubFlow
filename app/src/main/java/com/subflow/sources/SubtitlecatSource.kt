package com.subflow.sources

import com.subflow.models.Release
import com.subflow.models.SubtitleCandidate
import com.subflow.utils.Net
import org.jsoup.Jsoup

// aggregator, quick pass when the dedicated sources come up empty. high MT risk so tier 3.
class SubtitlecatSource : SubtitleSource {
    override val name = "Subtitlecat"
    override val tier = 3

    private val base = "https://www.subtitlecat.com"

    override suspend fun search(release: Release, log: suspend (String) -> Unit): List<SubtitleCandidate> {
        val variant = release.queryVariants().firstOrNull() ?: return emptyList()
        val q = java.net.URLEncoder.encode(variant, "UTF-8")
        val html = Net.getString("$base/index.php?search=$q") ?: return emptyList()
        val doc = Jsoup.parse(html, base)
        val out = mutableListOf<SubtitleCandidate>()
        for (a in doc.select("table.sub-table td a[href]").take(6)) {
            val relName = a.text()
            if (matchScore(release, relName) < 2) continue
            val detailHtml = Net.getString(a.absUrl("href")) ?: continue
            val detail = Jsoup.parse(detailHtml, base)
            val target = release.targetLang
            // strict -<lang>.srt selector. the loose div:contains fallback grabbed the wrong language.
            val targetLink = detail.select("a[href$=-$target.srt]").firstOrNull()
            if (targetLink != null) {
                out += SubtitleCandidate(
                    sourceName = name, title = relName, language = target,
                    downloadUrl = targetLink.absUrl("href"),
                    score = matchScore(release, relName)
                )
            }
            if (target != "en") {
                val enLink = detail.select("a[href$=-en.srt]").firstOrNull()
                if (enLink != null) {
                    out += SubtitleCandidate(
                        sourceName = name, title = relName, language = "en",
                        downloadUrl = enLink.absUrl("href"),
                        score = matchScore(release, relName) - 1
                    )
                }
            }
            if (out.any { it.language == target }) break
        }
        return out.sortedByDescending { it.score }
    }
}
