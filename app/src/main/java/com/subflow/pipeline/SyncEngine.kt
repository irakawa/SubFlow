package com.subflow.pipeline

import com.subflow.R
import com.subflow.models.Release
import com.subflow.utils.FileUtils
import com.subflow.utils.L10n

// matching tags means the sub is used as-is, otherwise a heuristic sync runs and warns past 500ms drift.
object SyncEngine {

    data class SrtCue(val index: Int, val startMs: Long, val endMs: Long, val text: String)

    data class SyncReport(
        val content: String,
        val tagsMatched: Boolean,
        val estimatedErrorMs: Long,
        val warning: String?
    )

    // text group stops at a blank line, EOF, or the next cue header. that last case
    // rescues subs missing the blank separator between cues.
    private val cueRegex = Regex(
        """(\d+)\s*\n(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*\n(.*?)(?=\n\s*\n|\n\d+\s*\n\d{2}:\d{2}:\d{2}[,.]\d{3}\s*-->|\Z)""",
        RegexOption.DOT_MATCHES_ALL
    )

    fun parseSrt(content: String): List<SrtCue> {
        val cues = mutableListOf<SrtCue>()
        for (m in cueRegex.findAll(content.replace("\r\n", "\n"))) {
            val g = m.groupValues
            val start = g[2].toLong() * 3600_000 + g[3].toLong() * 60_000 + g[4].toLong() * 1000 + g[5].toLong()
            val end = g[6].toLong() * 3600_000 + g[7].toLong() * 60_000 + g[8].toLong() * 1000 + g[9].toLong()
            cues += SrtCue(g[1].toIntOrNull() ?: cues.size + 1, start, end, g[10].trim())
        }
        return cues
    }

    fun renderSrt(cues: List<SrtCue>): String = buildString {
        cues.forEachIndexed { i, cue ->
            append(i + 1).append('\n')
            append(FileUtils.srtTime(cue.startMs)).append(" --> ").append(FileUtils.srtTime(cue.endMs)).append('\n')
            append(cue.text).append("\n\n")
        }
    }

    fun tagsMatch(release: Release, subFileName: String): Boolean {
        val lower = subFileName.lowercase()
        var hits = 0
        var checks = 0
        release.releaseGroup?.let { checks++; if (lower.contains(it.lowercase())) hits++ }
        if (release.format.isNotBlank()) {
            checks++
            val key = release.format.lowercase().substringBefore(' ')
            if (lower.contains(key) || (release.format == "WEB-DL" && lower.contains("web"))) hits++
        }
        if (release.codec.isNotBlank()) {
            checks++; if (lower.contains(release.codec.lowercase())) hits++
        }
        if (checks == 0) return true // no tags to compare, treat as neutral
        return hits >= (checks + 1) / 2
    }

    // heuristic sync, no reference audio. fixes early starts and overlaps, reports drift on tag mismatch.
    fun validateAndSync(release: Release, subFileName: String, content: String): SyncReport {
        val cues = parseSrt(content)
        if (cues.isEmpty()) {
            return SyncReport(content, tagsMatched = false, estimatedErrorMs = 0, warning = L10n.t(R.string.log_srt_parse_fail))
        }
        val matched = tagsMatch(release, subFileName)

        var fixed = cues
        var estimatedError = 0L

        // correct a negative start
        val first = fixed.first().startMs
        if (first < 0) {
            fixed = fixed.map { it.copy(startMs = it.startMs - first, endMs = it.endMs - first) }
            estimatedError = -first
        }
        // fix overlaps, endMs must never fall below startMs
        fixed = fixed.mapIndexed { i, cue ->
            if (i < fixed.size - 1 && cue.endMs > fixed[i + 1].startMs) {
                cue.copy(endMs = maxOf(cue.startMs + 1, fixed[i + 1].startMs - 1))
            } else cue
        }

        // tag mismatch, estimate the typical drift from a format difference
        if (!matched) {
            // web-dl vs bluray gap comes from ads/intros, can't measure it without a reference
            estimatedError = maxOf(estimatedError, 600L)
        }

        val warning = if (!matched && estimatedError > 500) {
            L10n.t(R.string.log_sync_warn, estimatedError)
        } else null

        return SyncReport(renderSrt(fixed), matched, estimatedError, warning)
    }

    fun shift(content: String, offsetMs: Long): String {
        val cues = parseSrt(content).map {
            it.copy(startMs = maxOf(0, it.startMs + offsetMs), endMs = maxOf(1, it.endMs + offsetMs))
        }
        return renderSrt(cues)
    }
}
