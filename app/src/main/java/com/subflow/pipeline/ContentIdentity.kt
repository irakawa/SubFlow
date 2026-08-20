package com.subflow.pipeline

import com.subflow.models.ContentType
import com.subflow.models.Release
import kotlin.math.abs
import kotlin.math.max

/**
 * Content identity verification.
 *
 * Fuzzy similarity isn't enough: "Transformers Prime" and "Transformers Prime
 * Beast Hunters Predacons Rising" score high but are different shows. A candidate
 * also has to pass season/episode/year agreement and an extra-token check.
 */
object ContentIdentity {

    /** accept threshold for matchScore. below this a hit can't stop the cascade. */
    const val SCORE_GATE = 60

    /** lowercase, strip diacritics, fold Turkish dotless i so accented and plain forms compare equal. */
    private fun fold(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('ı', 'i')
            .lowercase()

    fun fuzzyMatch(a: String, b: String): Float {
        val na = fold(a).replace(Regex("[^a-z0-9]"), "")
        val nb = fold(b).replace(Regex("[^a-z0-9]"), "")
        val maxLen = max(na.length, nb.length)
        return if (maxLen == 0) 1f else 1f - levenshtein(na, nb).toFloat() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
            else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
        }
        return dp[a.length][b.length]
    }

    private val romanSeasons = mapOf(
        "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6, "vii" to 7, "viii" to 8, "ix" to 9
    )

    fun extractSeason(filename: String): Int? {
        val patterns = listOf(
            Regex("[Ss](\\d{1,2})[ ._-]?[Ee]\\d{1,3}"),      // "S01E05", "S1 E5", "S01.E05"
            Regex("[Ss]eason[.\\s](\\d{1,2})", RegexOption.IGNORE_CASE),
            Regex("(\\d{1,2})(?:st|nd|rd|th)\\s+[Ss]eason"),   // "2nd Season - 05"
            Regex("(\\d{1,2})\\.?\\s*[Ss]ezon"),             // Turkish listings: "5. Sezon"
            Regex("\\b(\\d{1,2})x\\d{1,3}\\b"),              // bounded: "1920x1080" must not read as 19x108
            Regex("\\bS(\\d{1,2})\\b(?!\\s*[Ee]\\d)")        // bare "S2" marker
        )
        for (p in patterns) {
            p.find(filename)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        // trailing roman numeral is the fansub season convention ("Overlord II - 05").
        // require 2+ letters: a lone "v" is the version tag ("05v2"), not season 5.
        for (token in filename.lowercase().split(Regex("[^a-z]+"))) {
            if (token.length >= 2) romanSeasons[token]?.let { return it }
        }
        return null
    }

    /**
     * type gates the bare-3-digit fallback to anime so "The 100" isn't misread.
     * release lets title numbers get blanked out first.
     */
    fun extractEpisode(filename: String, type: ContentType? = null, release: Release? = null): Int? {
        // explicit patterns are safe for any type
        val strong = listOf(
            Regex("[Ss]\\d{1,2}[ ._-]?[Ee](\\d{1,4})(?!\\d)"),   // "S01E05", "S1 E5", "S02.E08"
            Regex("[Ee]pisode[.\\s](\\d{1,4})(?!\\d)", RegexOption.IGNORE_CASE),
            Regex("(?<!\\d)(\\d{1,4})\\.?\\s*[Bb][öo]l[üu]m"), // Turkish listings: "14. Bölüm"
            Regex("\\b\\d{1,2}x(\\d{1,3})\\b")
        )
        for (p in strong) p.find(filename)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { return it }
        }
        // title numbers ("The 100") must not be read as an episode
        val stripped = release?.let { stripTitles(filename, it) } ?: filename
        // fansub dash style "Title - 06". a year after the dash isn't an episode
        Regex("-\\s?(?!(?:19|20)\\d{2}\\b)(\\d{1,4})(?:v\\d)?\\b").find(stripped)?.let {
            it.groupValues[1].toIntOrNull()?.let { n -> return n }
        }
        // bare 3-digit only for anime/donghua. "H.265" is a codec
        if (type == null || type == ContentType.ANIME || type == ContentType.DONGHUA) {
            Regex("(?<![Hh][. ])\\b(\\d{3})\\b").find(stripped)?.let {
                it.groupValues[1].toIntOrNull()?.let { n -> return n }
            }
        }
        return null
    }

    /** last release year, ignoring year-shaped title numbers ("Blade Runner 2049", "1917"). */
    fun extractYear(filename: String, release: Release? = null): Int? {
        val hay = release?.let { stripTitles(filename, it) } ?: filename
        return Regex("\\b(19|20)\\d{2}\\b").findAll(hay).lastOrNull()?.value?.toIntOrNull()
    }

    private val technicalTags = setOf(
        "1080p", "720p", "2160p", "480p", "576p", "web-dl", "webdl", "web", "dl", "bluray", "brrip",
        "webrip", "hdrip", "hdtv", "bdrip", "bd", "uhd", "x264", "x265", "h264", "h265", "h", "x",
        "hevc", "avc", "av1", "aac", "ddp5", "ddp", "dd", "dts", "ma", "hd", "flac", "opus",
        // legacy scene tags
        "xvid", "divx", "dvdrip", "dvd", "dvdscr", "cam", "ts", "tc", "hdcam", "r5", "webhd",
        "eac3", "ac3", "truehd", "atmos", "hdr10", "hdr", "dv", "remux", "hybrid", "dual",
        "audio", "10bit", "8bit", "amzn", "nf", "dsnp", "hulu", "srt", "ass", "sub", "subs",
        "subtitle", "subtitles", "multi", "repack", "proper", "v2", "v3",
        // edition/cut descriptors, a re-release is the same film
        "extended", "cut", "uncut", "unrated", "remastered", "restored", "theatrical",
        "directors", "director", "internal", "limited", "anniversary", "criterion",
        "imax", "ultimate", "complete", "collection", "batch", "uncensored",
        // language labels are naming noise
        "english", "japanese", "korean", "chinese", "spanish", "french", "german",
        "italian", "portuguese", "russian", "arabic", "indonesian", "vietnamese",
        // localized listing noise (Turkish sites)
        "türkçe", "turkce", "turkish", "altyazı", "altyazi", "sezon", "bölüm", "bolum", "indir",
        // season/part markers, not distinctive words
        "season", "part", "cour"
    )

    private val stopWords = setOf("the", "a", "an", "of", "and")

    private fun tokenize(title: String): List<String> =
        fold(title).replace(Regex("[^a-z0-9]+"), " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }

    /** every name this release goes by, primary first. */
    private fun titlesOf(release: Release): List<String> =
        (listOf(release.title) + release.altTitles).filter { it.isNotBlank() }

    /** strip known titles so leftover numbers read as episode/year. */
    private fun stripTitles(filename: String, release: Release): String {
        var s = filename.replace(Regex("[._]"), " ")
        for (t in titlesOf(release)) {
            s = s.replace(t.replace(Regex("[._]"), " "), " ", ignoreCase = true)
        }
        return s
    }

    /**
     * meaningful words the candidate has that the release title doesn't.
     * bracketed group tags and the release group name don't count.
     */
    fun extraTokenCount(candidateTitle: String, releaseTitle: String, releaseGroup: String? = null): Int {
        val releaseTokens = tokenize(releaseTitle).filterNot { it in stopWords }.toSet()
        val groupTokens = releaseGroup?.let { tokenize(it).toSet() } ?: emptySet()
        val cleaned = candidateTitle.replace(Regex("\\[[^\\]]*\\]"), " ")
        return tokenize(cleaned)
            .filterNot { it in stopWords }
            .filterNot { it in technicalTags }
            .filterNot { it in groupTokens }
            .filterNot { it.toIntOrNull() != null }
            .filterNot { it in sequelRomans }                     // roman season numeral ("Overlord II")
            .filterNot { Regex("\\d+(st|nd|rd|th)").matches(it) } // ordinal season ("2nd Season")
            // season/episode markers, joined or split
            .filterNot { Regex("s\\d{1,2}e\\d{1,3}|s\\d{1,2}|e\\d{1,3}|\\d{1,2}x\\d{1,3}").matches(it) }
            .count { cand -> releaseTokens.none { tokenMatches(cand, it) } }
    }

    /** 2+ extra meaningful words, likely a different production. */
    fun hasSuspiciousExtraTokens(candidateTitle: String, releaseTitle: String): Boolean =
        extraTokenCount(candidateTitle, releaseTitle) >= 2

    /** candidate title with technical noise removed, for scoring. */
    private fun cleanTitle(raw: String): String =
        tokenize(raw)
            .filterNot { it in technicalTags }
            .filterNot { Regex("s\\d{1,2}e\\d{1,3}|\\d{1,2}x\\d{1,3}|(19|20)\\d{2}|\\d+").matches(it) }
            .joinToString(" ")

    /**
     * fraction of the requested title's words present in the candidate, 0..1.
     * containment not edit distance, so appended alt titles don't penalize.
     */
    /** tokens equal, or both >=4 chars within one edit (sites misspell titles). */
    private fun tokenMatches(a: String, b: String): Boolean =
        a == b || (a.length >= 4 && b.length >= 4 &&
            kotlin.math.abs(a.length - b.length) <= 1 && levenshtein(a, b) <= 1)

    fun containment(candidateTitle: String, release: Release): Float {
        val candTokens = tokenize(candidateTitle.replace(Regex("\\[[^\\]]*\\]"), " ")).toSet()
        val titles = titlesOf(release).ifEmpty { return 0f } // a titleless release matches nothing
        return titles.maxOf { title ->
            val relTokens = tokenize(title).filterNot { it in stopWords }
            if (relTokens.isEmpty()) 1f
            else relTokens.count { rt -> candTokens.any { tokenMatches(rt, it) } }.toFloat() / relTokens.size
        }
    }

    /**
     * candidate must resemble the requested title: half its words present, or high
     * cleaned-title similarity for respellings. season/episode can confirm a title
     * but never substitute for it, every show has an S02E01.
     */
    fun titleResembles(candidateTitle: String, release: Release): Boolean {
        if (containment(candidateTitle, release) >= 0.5f) return true
        // dotted/suffixed spellings that token overlap misses
        val clean = cleanTitle(candidateTitle)
        return titlesOf(release).any { fuzzyMatch(it, clean) >= 0.55f }
    }

    /**
     * hard accept/reject. drops candidates that don't resemble the title, have the
     * wrong season/episode or film year (+/-1), or hit same-name-different-show traps.
     */
    /** cuts that can never be an episode, whatever the numbering. */
    private val nonEpisodeCuts = Regex(
        "\\b(recap|teaser|trailer|preview|sample|pv|ncop|nced|creditless)\\b|\\b\\d+\\.5\\b",
        RegexOption.IGNORE_CASE
    )

    fun verify(candidateTitle: String, release: Release): Boolean {
        // hard first gate: no resemblance means never accepted
        if (!titleResembles(candidateTitle, release)) return false
        if (release.episode != null && nonEpisodeCuts.containsMatchIn(candidateTitle)) return false
        if (release.type == ContentType.FILM && nonEpisodeCuts.containsMatchIn(candidateTitle)) return false

        val candSeason = extractSeason(candidateTitle)
        val candEpisode = extractEpisode(candidateTitle, release.type, release)
        val candYear = extractYear(candidateTitle, release)

        // series: season/episode must agree when both sides declare them
        if (release.type != ContentType.FILM) {
            if (release.season != null && candSeason != null && release.season != candSeason) return false
            if (release.episode != null && candEpisode != null && release.episode != candEpisode) return false
        }

        // film: year within +/-1
        if (release.type == ContentType.FILM && release.year != null && candYear != null) {
            if (abs(candYear - release.year) > 1) return false
        }

        // film sequel trap: with no year, an appended sequel marker the title lacks
        // ("Bad Boys 2", "Rocky IV") means a different film.
        if (release.type == ContentType.FILM && release.year == null &&
            appendsSequelMarker(candidateTitle, release)
        ) return false

        // spin-off trap: 2+ distinctive words no known title explains means a
        // different production.
        val knownTitles = titlesOf(release).joinToString(" ")
        val fullExtras = extraTokenCount(candidateTitle, knownTitles, release.releaseGroup)
        // for a series only the show-name portion (before the episode marker) counts.
        // words after the marker are the episode title, not a different-show signal.
        // films count the whole title, the year disambiguates them.
        val nameExtras = if (release.type == ContentType.FILM) fullExtras
        else extraTokenCount(candidateTitle.take(nameCutoff(candidateTitle)), knownTitles, release.releaseGroup)
        if (nameExtras >= 2) {
            // "Transformers Prime Beast Hunters S01E06" must not pass on the episode
            // number alone. only a matching film year confirms. for series the
            // unexplained show-name words settle it, different production.
            val confirmed = release.type == ContentType.FILM &&
                candYear != null && release.year != null && abs(candYear - release.year) <= 1
            if (!confirmed) return false
        }
        // unexplained show-name word and no episode marker can't prove it's this
        // episode ("A Prime Problem" case)
        if (nameExtras >= 1 && release.episode != null && candEpisode == null) return false

        return true
    }

    /**
     * True when the candidate's own name proves it is the requested episode.
     *
     * This is a narrower question than [verify]. verify() accepts a candidate that says
     * nothing about the episode at all — a bare "Transformers Prime" for an S01E06
     * request reaches 45 + 15 + 10 = 70 and clears [SCORE_GATE] on the title alone.
     * Rejecting those would turn real single-episode uploads into "not found", so they
     * are still delivered; they just must not be presented as a checked match. That is
     * what this answers, and the caller downgrades the score and badges the result.
     *
     * A bare number carries its season implicitly only for season 1, the fansub
     * convention ("[Group] Show - 06"). For any later season the same name could have
     * come from anywhere, so the season has to be stated too.
     */
    fun episodeConfirmed(candidateTitle: String, release: Release): Boolean {
        val wanted = release.episode ?: return true // nothing was asked, nothing to confirm
        if (extractEpisode(candidateTitle, release.type, release) != wanted) return false
        val wantedSeason = release.season ?: return true
        val candSeason = extractSeason(candidateTitle)
        return candSeason == wantedSeason || (candSeason == null && wantedSeason == 1)
    }

    /** markers separating a show name from its episode designation. */
    private val episodeMarkers = listOf(
        Regex("[Ss]\\d{1,2}[ ._-]?[Ee]\\d{1,4}"),
        Regex("\\b\\d{1,2}x\\d{1,3}\\b"),
        Regex("[Ss]eason[.\\s_-]*\\d", RegexOption.IGNORE_CASE),
        Regex("\\d{1,2}\\.?\\s*[Ss]ezon"),
        Regex("[Ee]pisode[.\\s_-]*\\d", RegexOption.IGNORE_CASE),
        Regex("\\d{1,3}\\.?\\s*[Bb][öo]l[üu]m"),
        Regex("(?<!\\d)-\\s?(?!(?:19|20)\\d{2}\\b)\\d{1,4}(?!\\d)"), // fansub dash "- 05" (a year is not an episode)
        Regex("\\d{1,2}(?:st|nd|rd|th)\\s+[Ss]eason", RegexOption.IGNORE_CASE)
    )

    /** where the episode designation begins, or full length if none. */
    private fun nameCutoff(candidateTitle: String): Int =
        episodeMarkers.mapNotNull { it.find(candidateTitle)?.range?.first }.minOrNull() ?: candidateTitle.length

    /** roman/arabic sequel numerals appended to a shared base title. */
    private val sequelRomans = setOf("ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x")

    private fun appendsSequelMarker(candidateTitle: String, release: Release): Boolean {
        val known = titlesOf(release).flatMap { tokenize(it) }.toSet()
        val cleaned = candidateTitle.replace(Regex("\\[[^\\]]*\\]"), " ")
        return tokenize(cleaned).any { t ->
            t !in known && t !in technicalTags &&
                (t in sequelRomans || (t.toIntOrNull()?.let { it in 2..29 } == true))
        }
    }

    /**
     * containment + cleaned-title similarity + season/episode-or-year agreement plus a
     * release-group bonus. containment carries most of the weight. only scores at or
     * above SCORE_GATE stop the cascade.
     */
    fun matchScore(release: Release, candidateTitle: String): Int {
        val clean = cleanTitle(candidateTitle).ifBlank { candidateTitle }
        val sim = titlesOf(release).ifEmpty { return 0 }.maxOf { fuzzyMatch(it, clean) }
        var score = (containment(candidateTitle, release) * 45).toInt() + (sim * 15).toInt()

        if (release.type != ContentType.FILM) {
            val candSeason = extractSeason(candidateTitle)
            val candEpisode = extractEpisode(candidateTitle, release.type, release)
            score += when {
                candSeason != null && candEpisode != null &&
                    candSeason == release.season && candEpisode == release.episode -> 30
                candEpisode != null && candEpisode == release.episode -> 20
                candSeason == null && candEpisode == null -> 10 // undetectable, mild neutral, not enough alone
                else -> 0
            }
        } else {
            val candYear = extractYear(candidateTitle, release)
            score += when {
                candYear != null && release.year != null && abs(candYear - release.year) <= 1 -> 30
                candYear == null -> 15
                else -> 0
            }
        }

        release.releaseGroup?.let {
            if (candidateTitle.contains(it, ignoreCase = true)) score += 10
        }
        // count extras the same way verify() does. for a series only the show-name
        // portion before the episode marker counts, episode-title words aren't a
        // spin-off signal. films count the whole title.
        val known = titlesOf(release).joinToString(" ")
        val extras = if (release.type == ContentType.FILM)
            extraTokenCount(candidateTitle, known, release.releaseGroup)
        else extraTokenCount(candidateTitle.take(nameCutoff(candidateTitle)), known, release.releaseGroup)
        score -= 12 * minOf(extras, 2)
        return score.coerceIn(0, 100)
    }
}
