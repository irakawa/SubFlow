package com.subflow.sources

import com.subflow.R
import com.subflow.models.ContentType
import com.subflow.models.Release
import com.subflow.models.SubtitleCandidate
import com.subflow.utils.L10n
import com.subflow.utils.Net
import org.json.JSONObject

/**
 * Last resort. Reddit, public Telegram previews, Wayback snapshots. Most hits aren't
 * downloadable, they're surfaced as leads.
 */
class FallbackSource : SubtitleSource {
    override val name = "Fallback (Reddit/Telegram/Wayback)"
    override val tier = 3

    override suspend fun search(release: Release, log: suspend (String) -> Unit): List<SubtitleCandidate> {
        val out = mutableListOf<SubtitleCandidate>()

        // reddit json search
        val subreddit = when (release.type) {
            ContentType.ANIME, ContentType.ANIMATION -> "anime"
            ContentType.DONGHUA -> "Donghua"
            else -> "television"
        }
        val q = java.net.URLEncoder.encode(
            "${release.title} ${release.targetLangName.lowercase()} subtitle", "UTF-8"
        )
        val reddit = Net.getString(
            "https://www.reddit.com/r/$subreddit/search.json?q=$q&restrict_sr=0&limit=5",
            mapOf("User-Agent" to "subflow/1.0")
        )
        if (reddit != null) {
            runCatching {
                val children = JSONObject(reddit).getJSONObject("data").getJSONArray("children")
                for (i in 0 until children.length()) {
                    val post = children.getJSONObject(i).getJSONObject("data")
                    val title = post.optString("title")
                    if (matchScore(release, title) < 2) continue
                    out += SubtitleCandidate(
                        sourceName = "Reddit", title = title, language = release.targetLang,
                        pageUrl = "https://www.reddit.com" + post.optString("permalink"),
                        score = matchScore(release, title)
                    )
                }
            }
        }

        // wayback snapshots of dead subtitle sites
        val waybackTargets = listOf(
            "subscene.com/subtitles/searchbytitle?query=${java.net.URLEncoder.encode(release.title, "UTF-8")}",
            "planetdp.org/?s=${java.net.URLEncoder.encode(release.title, "UTF-8")}"
        )
        for (target in waybackTargets) {
            val avail = Net.getString("https://archive.org/wayback/available?url=" +
                java.net.URLEncoder.encode(target, "UTF-8")) ?: continue
            runCatching {
                val snap = JSONObject(avail).getJSONObject("archived_snapshots")
                if (snap.has("closest")) {
                    val url = snap.getJSONObject("closest").optString("url")
                    if (url.isNotBlank()) {
                        out += SubtitleCandidate(
                            sourceName = "WaybackMachine",
                            title = "Archive: ${target.substringBefore('/')}",
                            language = release.targetLang, pageUrl = url, score = 1
                        )
                    }
                }
            }
        }

        // public telegram channel preview
        val tgChannels = listOf("altyazi", "turkanime")
        for (ch in tgChannels) {
            val html = Net.getString("https://t.me/s/$ch?q=" +
                java.net.URLEncoder.encode(release.title, "UTF-8")) ?: continue
            if (html.contains(release.title, ignoreCase = true)) {
                out += SubtitleCandidate(
                    sourceName = "Telegram", title = "t.me/$ch match",
                    language = release.targetLang, pageUrl = "https://t.me/s/$ch", score = 1
                )
            }
        }

        if (out.isEmpty()) log(L10n.t(R.string.log_source_none, name).trim())
        return out
    }
}
