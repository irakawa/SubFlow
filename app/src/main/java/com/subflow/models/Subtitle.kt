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
    val sourceName: String
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
    val untranslatedPct: Int = 0
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
