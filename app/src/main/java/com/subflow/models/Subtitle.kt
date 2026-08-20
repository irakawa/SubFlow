package com.subflow.models

import androidx.compose.runtime.Immutable

/** candidate from the cascade, not downloaded yet */
@Immutable
data class SubtitleCandidate(
    val sourceName: String,
    val title: String,
    val language: String,          // "tr", "en", "ja", ...
    val pageUrl: String? = null,
    val downloadUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val magnet: String? = null,    // torrent magnet, last-resort streamer
    val score: Int = 0             // release match score
) {
    val directlyDownloadable: Boolean get() = downloadUrl != null
}

/** downloaded subtitle, already utf-8 */
@Immutable
data class DownloadedSubtitle(
    val fileName: String,
    val content: String,           // UTF-8 .srt content
    val language: String,
    val sourceName: String,
    /**
     * true when the candidate's name actually stated the requested episode
     * (ContentIdentity.episodeConfirmed). Carried from the cascade so the result can
     * say so, whether this file is served directly or translated first.
     */
    val episodeVerified: Boolean = true
)

@Immutable
data class SubtitleResult(
    val fileName: String,          // .srt name matching the original MKV
    val content: String,
    val sourceName: String,
    val method: String,            // how it was produced: human translation, MT + post-processor, Whisper
    val sizeBytes: Int,
    val episodeLabel: String,
    val syncWarning: String? = null,
    /** 0-100 quality/fit score: tag match + source tier + sync confidence */
    val qualityScore: Int = 0,
    /** true only when our post-processor applied uncensoring and tone preservation. false for human translations. */
    val tonePreserved: Boolean = false,
    /**
     * percentage of cues still in the source language, 0 for a complete file. The pipeline
     * delivers a partly translated file rather than nothing; this is how the user finds
     * that out without opening it.
     */
    val untranslatedPct: Int = 0,
    /**
     * true when the file is raw provider output. Every quality layer is written for
     * Turkish (SUBFLOW_LANGUAGE_RULES 8.1), so any other target language gets the
     * machine translation and nothing else. The user is told rather than left to assume.
     */
    val rawMachineTranslation: Boolean = false,
    /**
     * false when nothing in the candidate's name proved this is the requested episode.
     * The identity gate can pass a bare show title, so "it got through" and "we checked
     * the episode" are not the same statement and are not shown as one.
     */
    val episodeVerified: Boolean = true
)

/** page that might host the subtitle but couldn't be auto-downloaded. offered as a manual lead. */
@Immutable
data class Lead(
    val sourceName: String,
    val title: String,
    val url: String
)

enum class LogLevel { INFO, OK, WARN, ERROR, STEP }

@Immutable
data class LogEntry(
    val id: Long,
    val level: LogLevel,
    val message: String,
    val timeMs: Long
)

enum class PipelineStatus { IDLE, RUNNING, DONE, FAILED, CANCELLED }
