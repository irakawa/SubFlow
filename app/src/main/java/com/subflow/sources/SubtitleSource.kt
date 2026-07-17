package com.subflow.sources

import com.subflow.models.Release
import com.subflow.models.SubtitleCandidate

// tier 1 = guaranteed human translation, tier 2 = mixed, tier 3 = aggregator / high MT risk
interface SubtitleSource {
    val name: String
    val tier: Int

    fun prefers(release: Release): Boolean = false

    // trust this source's language tag even when content detection is inconclusive.
    // only safe for single-language human sources, aggregators stay false.
    val trustsOwnLanguageTag: Boolean get() = false

    suspend fun search(release: Release, log: suspend (String) -> Unit): List<SubtitleCandidate>
}

// match score between release tokens and a candidate title
fun matchScore(release: Release, candidateTitle: String): Int {
    var score = 0
    val lower = candidateTitle.lowercase()
    val titleTokens = release.title.lowercase().split(' ').filter { it.length > 2 }
    score += titleTokens.count { lower.contains(it) } * 2
    release.releaseGroup?.let { if (lower.contains(it.lowercase())) score += 5 }
    val root = java.util.Locale.ROOT
    if (release.season != null) {
        if (lower.contains("s%02d".format(root, release.season)) || lower.contains("season ${release.season}")) score += 3
    }
    if (release.episode != null) {
        if (lower.contains("e%02d".format(root, release.episode)) ||
            lower.contains("- %02d".format(root, release.episode)) ||
            Regex("\\b0?${release.episode}\\b").containsMatchIn(lower)
        ) score += 3
    }
    if (release.codec.isNotBlank() && lower.contains(release.codec.lowercase())) score += 2
    if (release.format.isNotBlank() && lower.contains(release.format.lowercase().substringBefore(' '))) score += 2
    if (lower.contains("web-dl") && release.format == "WEB-DL") score += 1
    return score
}
