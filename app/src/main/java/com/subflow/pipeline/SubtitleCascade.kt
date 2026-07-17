package com.subflow.pipeline

import com.subflow.R
import com.subflow.data.ApiKeys
import com.subflow.models.ContentType
import com.subflow.models.DownloadedSubtitle
import com.subflow.models.Release
import com.subflow.models.SubtitleCandidate
import com.subflow.optimization.DeviceProfiler
import com.subflow.sources.Addic7edSource
import com.subflow.sources.AnimeToshoSource
import com.subflow.sources.ArchiveOrgSource
import com.subflow.sources.BSPlayerSource
import com.subflow.sources.FallbackSource
import com.subflow.sources.KitsunekkoSource
import com.subflow.sources.NyaaSource
import com.subflow.sources.OpenSubtitlesOrgSource
import com.subflow.sources.OpenSubtitlesSource
import com.subflow.sources.SubDLSource
import com.subflow.sources.SubSourceSource
import com.subflow.sources.SubtitleSource
import com.subflow.sources.SubtitlecatSource
import com.subflow.sources.YifySubtitlesSource
import com.subflow.utils.FileUtils
import com.subflow.utils.L10n
import com.subflow.utils.Net
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

data class CascadeResult(
    val target: List<DownloadedSubtitle>,    // target language
    val english: List<DownloadedSubtitle>,   // translation input
    val leads: List<SubtitleCandidate>,      // found but not downloadable
    val magnets: List<String>,               // verified torrent magnets
    val anglesTried: Int,
    val weakTargets: List<DownloadedSubtitle> = emptyList() // verified, low title-score, last resort
)

// source cascade. tries every source before giving up, human subs first.
class SubtitleCascade(
    private val onLog: suspend (String) -> Unit,
    private val onSourceChange: suspend (name: String, index: Int, total: Int) -> Unit
) {

    private fun orderedSources(release: Release): List<SubtitleSource> {
        val keys = ApiKeys
        val all = listOf(
            // tier 1, human translations
            AnimeToshoSource(), NyaaSource(), KitsunekkoSource(),
            Addic7edSource(), OpenSubtitlesOrgSource(), SubSourceSource(keys.orNull(keys.subsource)),
            // tier 2, mixed
            SubDLSource(keys.orNull(keys.subdl)),
            OpenSubtitlesSource(keys.orNull(keys.openSubtitles)),
            // tier 3, aggregators (last resort)
            SubtitlecatSource(), YifySubtitlesSource(), BSPlayerSource(), ArchiveOrgSource(),
            FallbackSource()
        )
        // sources that prefer this release move ahead within their tier
        return all.sortedWith(compareBy({ it.tier }, { if (it.prefers(release)) 0 else 1 }))
    }

    // title spellings tried per source, deduped case-insensitively
    private fun titleVariants(release: Release): List<String> = buildList {
        add(release.title)
        add(release.title.replace(' ', '.'))
        if (release.title.contains('\'')) add(release.title.replace("'", ""))
        release.year?.let { add("${release.title} $it") }
        // a site may only know the show by its alternate name
        addAll(release.altTitles.take(2))
    }.filter { it.isNotBlank() }.distinctBy { it.lowercase(java.util.Locale.ROOT) }

    // runs one source through the title variants. each variant gets its own
    // timeout so a slow one can't discard what the others already found.
    private suspend fun multiQuerySearch(source: SubtitleSource, release: Release): List<SubtitleCandidate> {
        val all = mutableListOf<SubtitleCandidate>()
        for (variant in titleVariants(release)) {
            val found = withTimeoutOrNull(10_000L) {
                runCatching {
                    source.search(release.copy(title = variant)) { msg -> onLog("   $msg") }
                }.getOrElse {
                    if (it is CancellationException) throw it
                    onLog(L10n.t(R.string.log_source_error, source.name, it.message?.take(80) ?: "?"))
                    emptyList()
                }
            } ?: emptyList()
            all += found
            if (all.size >= 5) break // enough angles for this source
        }
        return all.distinctBy { it.downloadUrl ?: it.pageUrl ?: it.title }
    }

    suspend fun run(release: Release): CascadeResult = coroutineScope {
        val sources = orderedSources(release)
        // sources whose language tag we trust when detection is inconclusive
        val trustedTargetSources = sources.filter { it.trustsOwnLanguageTag }.map { it.name }.toSet()
        val targetSubs = mutableListOf<DownloadedSubtitle>()   // real success
        val weakTargets = mutableListOf<DownloadedSubtitle>()  // low-score, fallback only
        val english = mutableListOf<DownloadedSubtitle>()      // translation input, not a hit
        val leads = mutableListOf<SubtitleCandidate>()
        val magnets = mutableListOf<String>()
        val tried = mutableListOf<String>()

        val semaphore = Semaphore(DeviceProfiler.maxParallelSources)
        val total = sources.size

        // queries a group concurrently, stops early once a confirmed target is in hand
        suspend fun processGroup(group: List<SubtitleSource>) {
            group.forEach { tried += it.name }
            val results = group.map { source ->
                async {
                    semaphore.withPermit {
                        onSourceChange(source.name, sources.indexOf(source) + 1, total)
                        onLog(L10n.t(R.string.log_source_query, source.name))
                        source to multiQuerySearch(source, release)
                    }
                }
            }.map { it.await() }

            for ((source, candidates) in results) {
                if (targetSubs.isNotEmpty()) break // confirmed target, stop downloading
                if (candidates.isEmpty()) {
                    onLog(L10n.t(R.string.log_source_none, source.name))
                    continue
                }
                onLog(L10n.t(R.string.log_source_found, source.name, candidates.size))

                // identity gate before download so wrong episode/year never costs a
                // fetch. page-only candidates still surface as leads.
                val (identityOk, rejected) = candidates.partition { ContentIdentity.verify(it.title, release) }
                rejected.forEach {
                    onLog(L10n.t(R.string.log_identity_reject, it.title.take(80)))
                    if (it.pageUrl != null && it.downloadUrl == null &&
                        ContentIdentity.titleResembles(it.title, release)
                    ) leads += it
                }

                identityOk.forEach { c -> c.magnet?.let { magnets += it } } // verified torrents
                for (candidate in identityOk.sortedByDescending { ContentIdentity.matchScore(release, it.title) }.take(5)) {
                    // a malformed archive must not abort the whole cascade
                    var downloaded = try {
                        download(candidate, release)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        null
                    }
                    if (downloaded == null) {
                        if (candidate.pageUrl != null) leads += candidate
                        continue
                    }
                    // site language tag is unreliable, detect from content
                    val detected = LangDetect.detect(downloaded.content)
                    if (detected != null && detected != downloaded.language) {
                        onLog(
                            L10n.t(
                                R.string.log_lang_mismatch, candidate.sourceName,
                                downloaded.language.uppercase(), detected.uppercase()
                            )
                        )
                        downloaded = downloaded.copy(language = detected)
                    }
                    val target = release.targetLang
                    // a short target-language sub yields no detection, so trust the
                    // tag only from single-language human sources
                    val trustedTag = detected == null && candidate.language == target &&
                        candidate.sourceName in trustedTargetSources
                    val verifiedTarget = detected == target || trustedTag

                    // right episode already. a strong title match ends the search, a
                    // weak one is kept as fallback but lets a later source win.
                    // non-target languages become translation input.
                    when {
                        verifiedTarget -> {
                            val idScore = ContentIdentity.matchScore(release, candidate.title)
                            if (idScore >= ContentIdentity.SCORE_GATE) {
                                targetSubs += downloaded
                                onLog(L10n.t(R.string.log_found_target, target.uppercase(), idScore, downloaded.fileName))
                            } else {
                                weakTargets += downloaded
                            }
                        }
                        // proven foreign, or any non-target tag: translate it ourselves
                        (detected != null && detected != target) || candidate.language != target -> {
                            english += downloaded
                            onLog(L10n.t(R.string.log_translation_source, downloaded.fileName, downloaded.language.uppercase()))
                        }
                        // target-tagged but unverifiable, route to translation. the final
                        // language gate keeps it if it really was the target language.
                        else -> {
                            english += downloaded
                            onLog(L10n.t(R.string.log_translation_source, downloaded.fileName, "?"))
                        }
                    }
                }
            }
        }

        // reliable tiers first
        processGroup(sources.filter { it.tier <= 2 })

        if (targetSubs.isNotEmpty()) {
            onLog(L10n.t(R.string.log_tier_found, 2))
        } else {
            // no confirmed target yet. always consult the tier-3 aggregators even if we
            // only found an english sub, they often carry the actual target-language one,
            // and translation can fail. try them before committing to machine translation.
            onLog(L10n.t(R.string.log_last_resort))
            processGroup(sources.filter { it.tier >= 3 })
            if (targetSubs.isEmpty() && english.isNotEmpty()) onLog(L10n.t(R.string.log_own_production))
        }

        // strong target is the direct result. english becomes translation input. the weak
        // (verified but low-score) targets ride along as a last resort so a bare "not found"
        // never wins over a real, right-episode sub if translation later fails.
        CascadeResult(
            target = targetSubs,
            english = english,
            leads = leads.distinctBy { it.pageUrl ?: it.title },
            magnets = magnets.distinct(),
            anglesTried = tried.size,
            weakTargets = weakTargets.distinctBy { it.content }
        )
    }

    // downloads a candidate, then unzips, fixes encoding, converts ass to srt
    private suspend fun download(candidate: SubtitleCandidate, release: Release): DownloadedSubtitle? {
        var url = candidate.downloadUrl
        var headers = candidate.headers

        if (candidate.sourceName == "OpenSubtitles" && url?.contains("#file_id=") == true) {
            url = OpenSubtitlesSource.resolveDownload(candidate)
        }
        if (url == null) return null

        val bytes = Net.getBytes(url, headers) ?: return null
        return bytesToSubtitle(bytes, candidate, release)
    }

    // season packs hold many episodes, so pick the entry whose name carries the
    // requested episode. refuse the archive if entries are numbered but ours isn't there.
    private fun pickArchiveEntry(
        entries: List<Pair<String, ByteArray>>,
        release: Release
    ): Pair<String, ByteArray>? {
        if (entries.isEmpty()) return null
        val srtFirst = entries.sortedByDescending { it.first.endsWith(".srt", true) }
        val wanted = release.episode ?: return srtFirst.first()
        val numbered = srtFirst.mapNotNull { e ->
            ContentIdentity.extractEpisode(e.first, release.type, release)?.let { e to it }
        }
        numbered.firstOrNull { it.second == wanted }?.let { return it.first }
        if (numbered.isNotEmpty()) return null // episodes are labeled and ours isn't here
        return if (entries.size == 1) srtFirst.first() else null
    }

    private fun bytesToSubtitle(raw: ByteArray, candidate: SubtitleCandidate, release: Release): DownloadedSubtitle? {
        var bytes = raw
        if (FileUtils.looksLikeXz(bytes)) {
            bytes = FileUtils.unxz(bytes) ?: return null // AnimeTosho .ass.xz / .srt.xz
        }
        if (FileUtils.looksLikeGzip(bytes)) {
            bytes = FileUtils.gunzip(bytes) ?: return null
        }
        var fileName = candidate.title.take(120)
        val archiveEntries = when {
            FileUtils.looksLikeZip(bytes) -> FileUtils.extractSubtitlesFromZip(bytes)
            FileUtils.looksLikeRar(bytes) -> FileUtils.extractSubtitlesFromRar(bytes) // Turkish sources mostly ship .rar
            else -> null
        }
        if (archiveEntries != null) {
            val best = pickArchiveEntry(archiveEntries, release) ?: return null
            fileName = best.first
            bytes = best.second
        }
        var content = FileUtils.toUtf8(bytes)
        if (content.isBlank()) return null
        // guard against an HTML error page
        val head = content.trimStart().take(60).lowercase()
        if (head.startsWith("<!doctype") || head.startsWith("<html")) return null
        if (fileName.endsWith(".ass", true) || fileName.endsWith(".ssa", true) ||
            content.contains("[Script Info]", ignoreCase = true)
        ) {
            content = FileUtils.assToSrt(content)
            if (content.isBlank()) return null
        }
        // must contain at least one cue
        if (!Regex("\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->").containsMatchIn(content)) return null
        return DownloadedSubtitle(
            fileName = fileName,
            content = content,
            language = candidate.language,
            sourceName = candidate.sourceName
        )
    }
}
