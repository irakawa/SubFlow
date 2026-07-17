package com.subflow.sources

import com.subflow.models.ContentType
import com.subflow.models.Release
import com.subflow.models.SubtitleCandidate
import com.subflow.utils.Net
import org.jsoup.Jsoup

class KitsunekkoSource : SubtitleSource {
    override val name = "Kitsunekko.net"
    override val tier = 1

    private val base = "https://kitsunekko.net"

    override fun prefers(release: Release) =
        release.type == ContentType.ANIME || release.type == ContentType.DONGHUA

    companion object {
        // cache the big top listing briefly, search() gets called a few times per release
        @Volatile private var cachedListing: String? = null
        @Volatile private var cachedAt: Long = 0L
        private const val TTL_MS = 60_000L

        private suspend fun topListing(base: String): String? {
            val now = android.os.SystemClock.elapsedRealtime()
            cachedListing?.let { if (now - cachedAt < TTL_MS) return it }
            val fresh = Net.getString("$base/dirlist.php?dir=subtitles%2Fjapanese%2F") ?: return null
            cachedListing = fresh
            cachedAt = now
            return fresh
        }
    }

    override suspend fun search(release: Release, log: suspend (String) -> Unit): List<SubtitleCandidate> {
        val listing = topListing(base) ?: return emptyList()
        val doc = Jsoup.parse(listing, base)
        val titleTokens = release.title.lowercase().split(' ').filter { it.length > 2 }
        if (titleTokens.isEmpty()) return emptyList()

        val dirLink = doc.select("a[href*=dirlist.php]").firstOrNull { a ->
            val t = a.text().lowercase()
            titleTokens.count { t.contains(it) } >= (titleTokens.size + 1) / 2
        } ?: return emptyList()

        val dirHtml = Net.getString(dirLink.absUrl("href")) ?: return emptyList()
        val dirDoc = Jsoup.parse(dirHtml, base)
        val out = mutableListOf<SubtitleCandidate>()
        for (a in dirDoc.select("a[href]")) {
            val href = a.absUrl("href")
            val fname = a.text()
            val lower = fname.lowercase()
            if (!lower.endsWith(".srt") && !lower.endsWith(".ass") && !lower.endsWith(".zip") && !lower.endsWith(".7z")) continue
            if (lower.endsWith(".7z")) continue // 7z not supported
            if (release.episode != null && !fname.contains("%02d".format(java.util.Locale.ROOT, release.episode)) &&
                !Regex("\\b${release.episode}\\b").containsMatchIn(fname)
            ) continue
            out += SubtitleCandidate(
                sourceName = name, title = fname, language = "ja",
                downloadUrl = href, score = matchScore(release, fname)
            )
        }
        return out.sortedByDescending { it.score }.take(5)
    }
}
