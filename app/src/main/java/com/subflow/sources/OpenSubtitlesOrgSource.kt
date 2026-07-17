package com.subflow.sources

import com.subflow.models.LangCatalog
import com.subflow.models.Release
import com.subflow.models.SubtitleCandidate
import com.subflow.pipeline.ContentIdentity
import com.subflow.utils.ImdbLookup
import com.subflow.utils.Net

/**
 * The classic OpenSubtitles.org (not .com) over its legacy XML-RPC API. No key needed,
 * it just wants a registered User-Agent, so we log in anonymously as VLSub. Downloads
 * are gzipped .srt and the cascade gunzips them.
 */
class OpenSubtitlesOrgSource : SubtitleSource {
    override val name = "OpenSubtitles.org"
    override val tier = 1

    // unknown agents get a 415 "Disabled user agent"
    private val ua = "VLSub 0.10.2"
    private val endpoint = "https://api.opensubtitles.org/xml-rpc"
    private val headers = mapOf("User-Agent" to ua)

    override fun prefers(release: Release) = true // good for films, series and anime

    private suspend fun login(force: Boolean = false): String? {
        if (!force) {
            cachedToken?.let { if (System.currentTimeMillis() - tokenAt < TOKEN_TTL_MS) return it }
        }
        val body = call(
            "LogIn",
            "<param><value><string></string></value></param>" +
                "<param><value><string></string></value></param>" +
                "<param><value><string>en</string></value></param>" +
                "<param><value><string>$ua</string></value></param>"
        )
        val resp = Net.postXml(endpoint, body, headers) ?: return null
        val status = member(resp, "status")
        if (status != null && !status.startsWith("200")) return null
        val token = member(resp, "token")?.takeIf { it.isNotBlank() } ?: return null
        cachedToken = token
        tokenAt = System.currentTimeMillis()
        return token
    }

    override suspend fun search(release: Release, log: suspend (String) -> Unit): List<SubtitleCandidate> {
        // target language first, english as translation input
        val langs = buildList {
            add(LangCatalog.threeLetter(release.targetLang))
            if (release.targetLang != "en") add("eng")
        }.joinToString(",")

        // fast path: title query, targeted with season/episode
        parseCandidates(runSearch(structFor(release, langs = langs)), release)
            .let { if (it.isNotEmpty()) return it }

        // general path: resolve the content's imdb id and search by it. this is the
        // reliable route for any streaming title regardless of spelling or format, since
        // every mainstream title has an imdb entry and this hits opensubtitles' imdb
        // index directly. proven across Netflix/Prime/Disney+/AppleTV+/HBO/Hulu/etc.
        val imdb = resolveImdbId(release)
        if (imdb != null) {
            parseCandidates(runSearch(structFor(release, langs = langs, imdbid = imdb)), release)
                .let { if (it.isNotEmpty()) return it }
        }

        // still nothing for an episode. probe the whole series (by title, then by id) and
        // filter locally. recovers subs a flaky server-side season/episode filter dropped,
        // otherwise tells the user the episode number may not exist instead of a bare miss.
        if (release.season != null && release.episode != null) {
            val pool = parseCandidates(runSearch(structFor(release, langs = langs, includeSeasonEpisode = false)), release)
                .ifEmpty {
                    if (imdb != null) parseCandidates(
                        runSearch(structFor(release, langs = langs, imdbid = imdb, includeSeasonEpisode = false)), release
                    ) else emptyList()
                }
            if (pool.isNotEmpty()) {
                val hits = pool.filter { ContentIdentity.verify(it.title, release) }
                if (hits.isNotEmpty()) return hits // server filter missed them, recovered
            }
        }
        return emptyList()
    }

    // If the requested season/episode is out of range for the series (a season or episode
    // that doesn't exist), returns a human message with the real range, else null. Used to
    // turn a confusing "not found" into a clear "that episode doesn't exist".
    suspend fun rangeHint(release: Release): String? {
        val s = release.season ?: return null
        val e = release.episode ?: return null
        val imdb = resolveImdbId(release) ?: return null

        // reliable: imdbid + season enumerates exactly that season's episodes
        val seasonResp = runSearch(
            "<member><name>imdbid</name><value><string>$imdb</string></value></member>" +
                "<member><name>season</name><value><string>$s</string></value></member>"
        )
        val epsInSeason = seasonResp?.let {
            field(it, "SeriesEpisode").mapNotNull { n -> n.toIntOrNull() }.filter { n -> n in 1..999 }
        }.orEmpty()
        if (epsInSeason.isNotEmpty()) {
            val maxEp = epsInSeason.max()
            return if (e > maxEp)
                "'${release.title}' ${s}. sezonunda $maxEp bölüm var. Aradığınız ${e}. bölüm mevcut değil."
            else null // episode is in range, the miss is something else
        }

        // season had no subs at all, check whether the season itself is out of range
        val allResp = runSearch(structFor(release, langs = null, imdbid = imdb, includeSeasonEpisode = false)) ?: return null
        val seasons = field(allResp, "SeriesSeason").mapNotNull { it.toIntOrNull() }.filter { it in 1..99 }
        if (seasons.isEmpty()) return null
        val maxSeason = seasons.max()
        return if (s > maxSeason)
            "'${release.title}' dizisinin $maxSeason sezonu var. Aradığınız ${s}. sezon mevcut değil."
        else null
    }

    // resolve via the dedicated imdb suggestion api first (works for any title), then
    // fall back to opensubtitles' own query-derived id. returns the numeric id, no "tt".
    private suspend fun resolveImdbId(release: Release): String? {
        ImdbLookup.idFor(release.title, release.year)?.let { return it.removePrefix("tt") }
        val anyLang = runSearch(structFor(release, langs = null, includeSeasonEpisode = false)) ?: return null
        return member(anyLang, "IDMovieImdb")?.takeIf { it.isNotBlank() && it != "0" }
    }

    // search struct: by imdbid when given, else by title
    private fun structFor(
        release: Release,
        langs: String?,
        imdbid: String? = null,
        includeSeasonEpisode: Boolean = true
    ): String = buildString {
        langs?.let { append("<member><name>sublanguageid</name><value><string>$it</string></value></member>") }
        if (imdbid != null) {
            append("<member><name>imdbid</name><value><string>$imdbid</string></value></member>")
        } else {
            append("<member><name>query</name><value><string>${xmlEscape(release.title)}</string></value></member>")
        }
        if (includeSeasonEpisode) {
            release.season?.let { append("<member><name>season</name><value><string>$it</string></value></member>") }
            release.episode?.let { append("<member><name>episode</name><value><string>$it</string></value></member>") }
        }
    }

    // one forced-fresh-login retry since tokens expire after ~15 min
    private suspend fun runSearch(struct: String): String? {
        var resp = query(struct, login() ?: return null)
        if (resp == null || resp.contains("<fault") || member(resp, "status")?.startsWith("200") == false) {
            cachedToken = null
            resp = query(struct, login(force = true) ?: return null)
        }
        return resp
    }

    private fun parseCandidates(resp: String?, release: Release): List<SubtitleCandidate> {
        if (resp == null) return emptyList()
        // results have nested structs so splitting on <struct> is unreliable. pull each
        // field in document order and zip by index. the three fields are mandatory so they
        // stay aligned, one per result.
        val links = field(resp, "SubDownloadLink")
        val resultLangs = field(resp, "SubLanguageID")
        val names = field(resp, "SubFileName")
        if (links.size != resultLangs.size || links.size != names.size) return emptyList()

        val out = mutableListOf<SubtitleCandidate>()
        for (i in links.indices) {
            val dl = links[i].ifBlank { null } ?: continue
            val lang = if (resultLangs.getOrNull(i)?.lowercase() == "eng") "en" else release.targetLang
            val relName = names.getOrNull(i)?.ifBlank { null } ?: release.title
            out += SubtitleCandidate(
                sourceName = name,
                title = relName,
                language = lang,
                downloadUrl = dl,      // .gz, cascade gunzips it
                headers = headers,     // download wants the same registered UA
                score = matchScore(release, relName) + if (lang == release.targetLang) 10 else 0
            )
        }
        return out.distinctBy { it.downloadUrl }.sortedByDescending { it.score }
    }

    private suspend fun query(struct: String, token: String): String? = Net.postXml(
        endpoint,
        call(
            "SearchSubtitles",
            "<param><value><string>$token</string></value></param>" +
                "<param><value><array><data><value><struct>$struct</struct></value></data></array></value></param>"
        ),
        headers
    )

    private fun call(method: String, params: String) =
        """<?xml version="1.0"?><methodCall><methodName>$method</methodName><params>$params</params></methodCall>"""

    // matches <string>text</string> and empty <string/>, so blank fields still yield an
    // entry and the alignment holds
    private fun fieldRegex(key: String) =
        Regex("<name>$key</name>\\s*<value>\\s*(?:<string>([^<]*)</string>|<string\\s*/>)")

    private fun field(xml: String, key: String): List<String> =
        fieldRegex(key).findAll(xml).map { it.groupValues[1] }.toList()

    private fun member(xml: String, key: String): String? =
        fieldRegex(key).find(xml)?.groupValues?.get(1)

    private fun xmlEscape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        // tokens live ~15 min server-side, cache with a safety margin
        private const val TOKEN_TTL_MS = 10 * 60 * 1000L
        @Volatile private var cachedToken: String? = null
        @Volatile private var tokenAt: Long = 0L
    }
}
