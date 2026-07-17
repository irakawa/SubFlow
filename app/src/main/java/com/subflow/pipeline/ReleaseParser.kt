package com.subflow.pipeline

import com.subflow.models.ContentType
import com.subflow.models.Release

// parses scene/fansub release names and OCR text into a Release
object ReleaseParser {

    private val sceneRegex = Regex(
        """^(?<title>.+?)[. _]S(?<s>\d{1,2})[. _]?E(?<e>\d{1,3})""",
        RegexOption.IGNORE_CASE
    )
    private val altEpisodeRegex = Regex(
        """^(?<title>.+?)[. _](?<s>\d{1,2})x(?<e>\d{1,3})""",
        RegexOption.IGNORE_CASE
    )
    private val fansubRegex = Regex(
        """^\[(?<group>[^\]]+)]\s*(?<title>.+?)\s*-\s*(?<e>\d{1,3})(?:v\d)?\b"""
    )
    private val fansubSeasonRegex = Regex(
        """^\[(?<group>[^\]]+)]\s*(?<title>.+?)\s+Season\s+(?<s>\d{1,2})""",
        RegexOption.IGNORE_CASE
    )
    private val movieRegex = Regex(
        """^(?<title>.+?)[. _(\[]+(?<y>(?:19|20)\d{2})[)\]]?[. _]"""
    )
    private val codecRegex = Regex("""\b(x264|x265|H[. ]?264|H[. ]?265|HEVC|AVC|AV1)\b""", RegexOption.IGNORE_CASE)
    private val audioRegex = Regex(
        """\b(FLAC(?:\s?[257]\.[01])?|DTS-HD[. ]?MA(?:[. ]?[257]\.[01])?|DDP?[257][. ]?[01]|E-?AC-?3|TrueHD(?:[. ]?Atmos)?|Atmos|AAC(?:[. ]?[257]\.[01])?|OPUS|DTS)\b""",
        RegexOption.IGNORE_CASE
    )
    private val formatRegex = Regex(
        """\b(BD[. ]?Remux|Remux|WEB[-. ]?DL|WEBRip|BluRay|Blu-Ray|BDRip|HDTV|WEB)\b""",
        RegexOption.IGNORE_CASE
    )
    private val groupTailRegex = Regex("""-(?<g>[A-Za-z0-9@]+)(?:\.\w{2,4})?\s*$""")
    private val tagCandidates = listOf(
        "Dual Audio", "Dual-Audio", "8-bit", "8bit", "10-bit", "10bit", "HDR10+", "HDR10", "HDR",
        "DV", "Dolby Vision", "REPACK", "PROPER", "REMASTERED", "UNCENSORED", "Multi-Sub", "Multi Sub"
    )
    private val resolutionRegex = Regex("""\b(2160p|1080p|720p|480p|4K)\b""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): Release {
        val line = raw.trim()
        if (line.isBlank()) return Release()

        val codec = codecRegex.find(line)?.value?.normalizeCodec() ?: ""
        val audio = audioRegex.find(line)?.value?.normalizeAudio() ?: ""
        val formatRaw = formatRegex.find(line)?.value ?: ""
        val format = normalizeFormat(line, formatRaw)
        val tags = tagCandidates.filter { line.contains(it, ignoreCase = true) }
            .map { it.replace("8bit", "8-bit").replace("10bit", "10-bit") }
            .distinct()

        fansubSeasonRegex.find(line)?.let { m ->
            return Release(
                title = clean(m.groups["title"]!!.value),
                season = m.groups["s"]!!.value.toInt(),
                type = ContentType.ANIME,
                format = format, codec = codec, audio = audio,
                releaseGroup = m.groups["group"]!!.value,
                tags = tags, fileName = fileNameOrNull(raw)
            )
        }
        fansubRegex.find(line)?.let { m ->
            return Release(
                title = clean(m.groups["title"]!!.value),
                episode = m.groups["e"]!!.value.toInt(),
                type = ContentType.ANIME,
                format = format, codec = codec, audio = audio,
                releaseGroup = m.groups["group"]!!.value,
                tags = tags, fileName = fileNameOrNull(raw)
            )
        }
        (sceneRegex.find(line) ?: altEpisodeRegex.find(line))?.let { m ->
            return Release(
                title = clean(m.groups["title"]!!.value),
                season = m.groups["s"]!!.value.toInt(),
                episode = m.groups["e"]!!.value.toInt(),
                type = ContentType.SERIES,
                format = format, codec = codec, audio = audio,
                releaseGroup = groupTailRegex.find(line)?.groups?.get("g")?.value,
                tags = tags, fileName = fileNameOrNull(raw)
            )
        }
        movieRegex.find(line)?.let { m ->
            return Release(
                title = clean(m.groups["title"]!!.value),
                year = m.groups["y"]!!.value.toInt(),
                type = ContentType.FILM,
                format = format, codec = codec, audio = audio,
                releaseGroup = groupTailRegex.find(line)?.groups?.get("g")?.value,
                tags = tags, fileName = fileNameOrNull(raw)
            )
        }
        // unrecognized, fall back to free text
        return Release(
            title = clean(line.substringBefore('.').ifBlank { line }.take(80)),
            format = format, codec = codec, audio = audio, tags = tags
        )
    }

    // ocr is multi-line, pick the line that looks most like a release name
    fun parseFromOcr(text: String): Release {
        val lines = text.lines().map { it.trim() }.filter { it.length > 4 }
        if (lines.isEmpty()) return Release()
        val best = lines.maxByOrNull { scoreLine(it) } ?: return Release()
        return if (scoreLine(best) > 0) parse(best) else Release(title = best.take(80))
    }

    private fun scoreLine(line: String): Int {
        var score = 0
        if (sceneRegex.containsMatchIn(line)) score += 6
        if (fansubRegex.containsMatchIn(line)) score += 6
        if (fansubSeasonRegex.containsMatchIn(line)) score += 5
        if (movieRegex.containsMatchIn(line)) score += 3
        if (resolutionRegex.containsMatchIn(line)) score += 2
        if (codecRegex.containsMatchIn(line)) score += 2
        if (audioRegex.containsMatchIn(line)) score += 2
        if (formatRegex.containsMatchIn(line)) score += 2
        if (line.contains(".mkv", true) || line.contains(".mp4", true)) score += 2
        return score
    }

    private fun fileNameOrNull(raw: String): String? {
        val t = raw.trim()
        return if (t.endsWith(".mkv", true) || t.endsWith(".mp4", true) || t.endsWith(".avi", true)) t else null
    }

    private fun clean(title: String): String =
        title.replace('.', ' ').replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim().trimEnd('-', ' ')

    private fun String.normalizeCodec(): String = when {
        contains("265") || equals("HEVC", true) -> "x265"
        contains("264") || equals("AVC", true) -> "x264"
        else -> uppercase()
    }

    private fun String.normalizeAudio(): String = when {
        startsWith("FLAC", true) -> "FLAC"
        contains("DTS-HD", true) || (contains("DTS", true) && contains("MA", true)) -> "DTS-HD MA"
        startsWith("DDP", true) || startsWith("DD", true) || contains("E-AC", true) || contains("EAC", true) -> "DDP5.1"
        contains("TrueHD", true) || contains("Atmos", true) -> "TrueHD Atmos"
        else -> uppercase()
    }

    private fun normalizeFormat(line: String, raw: String): String = when {
        raw.contains("Remux", true) -> "BD Remux"
        raw.contains("WEB", true) -> "WEB-DL"
        raw.contains("BluRay", true) || raw.contains("Blu-Ray", true) || raw.contains("BDRip", true) -> "BluRay Encode"
        raw.contains("HDTV", true) -> "HDTV"
        else -> raw
    }
}
