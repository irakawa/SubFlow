package com.subflow.pipeline

import android.content.Context
import android.content.Intent
import android.util.Log
import com.subflow.BuildConfig
import com.subflow.R
import com.subflow.data.HistoryEntry
import com.subflow.data.ResultStore
import com.subflow.data.Stats
import com.subflow.sources.OpenSubtitlesOrgSource
import com.subflow.data.SubFlowDb
import com.subflow.models.DownloadedSubtitle
import com.subflow.models.Lead
import com.subflow.models.LogEntry
import com.subflow.models.LogLevel
import com.subflow.models.PipelineStatus
import com.subflow.models.Release
import com.subflow.models.SubtitleResult
import com.subflow.optimization.MemoryManager
import com.subflow.service.PipelineService
import com.subflow.service.ResultNotifier
import com.subflow.utils.AppForeground
import com.subflow.utils.ConnectivityWatcher
import com.subflow.utils.FileUtils
import com.subflow.utils.HashUtils
import com.subflow.utils.L10n
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Singleton pipeline runtime. State lives outside the UI process and survives backgrounding via a foreground service. */
object PipelineRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val logId = AtomicLong(0)

    private val _status = MutableStateFlow(PipelineStatus.IDLE)
    val status: StateFlow<PipelineStatus> = _status.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    /** -1f = indeterminate (shimmer) */
    private val _progress = MutableStateFlow(-1f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _currentSource = MutableStateFlow<Triple<String, Int, Int>?>(null)
    val currentSource: StateFlow<Triple<String, Int, Int>?> = _currentSource.asStateFlow()

    private val _results = MutableStateFlow<List<SubtitleResult>>(emptyList())
    val results: StateFlow<List<SubtitleResult>> = _results.asStateFlow()

    private val _leads = MutableStateFlow<List<com.subflow.models.Lead>>(emptyList())
    val leads: StateFlow<List<com.subflow.models.Lead>> = _leads.asStateFlow()

    // a prominent hint shown on the not-found screen (e.g. "that episode doesn't exist")
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _whisperConsentNeeded = MutableStateFlow(false)
    val whisperConsentNeeded: StateFlow<Boolean> = _whisperConsentNeeded.asStateFlow()
    private var whisperConsent: CompletableDeferred<Boolean>? = null

    private val _lastRelease = MutableStateFlow<Release?>(null)
    val lastRelease: StateFlow<Release?> = _lastRelease.asStateFlow()

    /** last search (all episodes in season mode), backs retry */
    private var lastBatch: List<Release> = emptyList()

    // in season mode each episode's 0..1 progress maps onto the global bar
    private var progressBase = 0f
    private var progressSpan = 1f

    private fun setProgress(p: Float) {
        _progress.value = if (p < 0f) {
            if (progressSpan >= 1f) -1f else progressBase
        } else {
            progressBase + p.coerceIn(0f, 1f) * progressSpan
        }
    }

    private const val MAX_LOG_ENTRIES = 1000

    private suspend fun log(level: LogLevel, message: String) {
        // cascade coroutines log concurrently, so append via CAS
        // cap the list so long searches don't bloat memory
        _logs.update { list ->
            val next = list + LogEntry(logId.incrementAndGet(), level, message, System.currentTimeMillis())
            if (next.size > MAX_LOG_ENTRIES) next.takeLast(MAX_LOG_ENTRIES) else next
        }
    }

    fun answerWhisperConsent(allow: Boolean) {
        _whisperConsentNeeded.value = false
        whisperConsent?.complete(allow)
    }

    fun cancel() {
        // a finished/failed pipeline is never flipped to "cancelled"
        if (_status.value != PipelineStatus.RUNNING) return
        // dismiss any pending whisper consent so the sheet doesn't linger
        _whisperConsentNeeded.value = false
        whisperConsent?.complete(false)
        // cancel the job first so no new ffmpeg session starts, then kill the
        // running one. the reverse can hang for minutes between cancel and execute
        job?.cancel()
        runCatching { com.antonkarpenko.ffmpegkit.FFmpegKit.cancel() }
        scope.launch {
            job?.cancelAndJoin()
            _currentSource.value = null // clear stale source name on screen
            // don't overwrite if the job already finished normally (DONE/FAILED)
            if (_status.compareAndSet(PipelineStatus.RUNNING, PipelineStatus.CANCELLED)) {
                log(LogLevel.WARN, L10n.t(R.string.log_cancelled))
            }
        }
    }

    fun reset() {
        if (_status.value != PipelineStatus.RUNNING) {
            _logs.value = emptyList()
            _results.value = emptyList()
            _progress.value = -1f
            _currentSource.value = null
            _status.value = PipelineStatus.IDLE
        }
    }

    fun start(context: Context, release: Release) = start(context, listOf(release))

    fun start(context: Context, releases: List<Release>) =
        launch(context, releases, fullBatch = releases, keepResults = false, writeHist = true)

    /** re-runs only the episodes from the last batch that got no result, appending to what's there. returns false when nothing failed. */
    fun retryFailed(context: Context): Boolean {
        if (_status.value == PipelineStatus.RUNNING) return false
        val failed = failedReleases()
        if (failed.isEmpty()) return false
        launch(context, failed, fullBatch = lastBatch, keepResults = true, writeHist = false)
        return true
    }

    /** Releases from the last batch not covered by any result. */
    fun failedReleases(): List<Release> {
        if (lastBatch.size <= 1) return emptyList()
        val covered = _results.value.map { it.episodeLabel }.toSet()
        return lastBatch.filter { it.displayName() !in covered }
    }

    private fun launch(
        context: Context,
        releases: List<Release>,
        fullBatch: List<Release>,
        keepResults: Boolean,
        writeHist: Boolean
    ) {
        if (_status.value == PipelineStatus.RUNNING || releases.isEmpty()) return
        val appContext = context.applicationContext

        // fail fast when offline instead of letting 15 sources time out one by one
        if (!ConnectivityWatcher.isOnline(appContext)) {
            _logs.value = emptyList()
            _results.value = emptyList()
            _status.value = PipelineStatus.RUNNING
            scope.launch {
                log(LogLevel.ERROR, L10n.t(R.string.no_internet))
                _status.value = PipelineStatus.FAILED
            }
            return
        }

        lastBatch = fullBatch
        _lastRelease.value = fullBatch.first()
        _logs.value = emptyList()
        _notice.value = null
        if (!keepResults) {
            _results.value = emptyList()
            _leads.value = emptyList()
        }
        _progress.value = -1f
        _status.value = PipelineStatus.RUNNING

        // foreground service so long searches aren't killed in the background
        try {
            appContext.startForegroundService(Intent(appContext, PipelineService::class.java))
        } catch (e: Exception) {
            // proceed even without notification permission
        }

        job = scope.launch {
            try {
                for ((i, rel) in releases.withIndex()) {
                    progressBase = i.toFloat() / releases.size
                    progressSpan = 1f / releases.size
                    if (releases.size > 1) {
                        log(LogLevel.STEP, L10n.t(R.string.log_episode_header, i + 1, releases.size))
                    }
                    // one episode's unexpected failure must not kill the rest of the season
                    val episodeResults = try {
                        runPipeline(appContext, rel)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log(LogLevel.ERROR, L10n.t(R.string.log_pipeline_error, e.message ?: "?"))
                        emptyList()
                    }
                    if (episodeResults.isNotEmpty()) _results.update { it + episodeResults }
                }
                _progress.value = 1f
                val done = _results.value.isNotEmpty()
                _status.value = if (done) PipelineStatus.DONE else PipelineStatus.FAILED
                if (writeHist) writeHistory(appContext, fullBatch)
                // if the user has left the app, surface the result with Save/Share actions
                if (done && !AppForeground.isForeground) {
                    try {
                        ResultNotifier.notifyReady(appContext, _results.value.size)
                    } catch (e: Exception) { /* notifications optional */ }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable, not just Exception: an OutOfMemoryError must still flip the
                // status to FAILED, otherwise the RUNNING guard wedges every future search
                log(LogLevel.ERROR, L10n.t(R.string.log_pipeline_error, e.message ?: e.javaClass.simpleName))
                _status.value = PipelineStatus.FAILED
            } finally {
                MemoryManager.clear()
                // never leave the pipeline stuck RUNNING, but not while being cancelled.
                // isActive is false in a cancelled coroutine's finally, otherwise this would
                // win the race with cancel()'s CANCELLED write and show "not found" instead.
                if (isActive) _status.compareAndSet(PipelineStatus.RUNNING, PipelineStatus.FAILED)
                try {
                    appContext.stopService(Intent(appContext, PipelineService::class.java))
                } catch (e: Exception) { /* ignore */ }
            }
        }
    }

    fun retryLastBatch(context: Context): Boolean {
        if (lastBatch.isEmpty()) return false
        reset()
        start(context, lastBatch)
        return true
    }

    private suspend fun writeHistory(context: Context, releases: List<Release>) {
        val first = releases.first()
        val results = _results.value
        try {
            val entry = HistoryEntry(
                title = first.displayName() +
                    if (releases.size > 1) " – E%02d".format(java.util.Locale.ROOT, releases.last().episode ?: 0) else "",
                detail = listOf(first.format, first.codec, first.audio)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                    .ifBlank { context.getString(first.type.labelRes) } +
                    " · " + first.targetLang.uppercase(),
                resultCount = results.size,
                method = results.firstOrNull()?.let { "${it.sourceName} — ${it.method}" }
                    ?: L10n.t(R.string.log_not_found_label),
                timestamp = System.currentTimeMillis(),
                params = searchParamsJson(releases)
            )
            val db = SubFlowDb.get(context)
            val id = db.historyDao().insert(entry)
            db.historyDao().prune(50) // only 50 are ever shown
            // key the result set to the row id, drop any stale file for empty runs.
            // ResultStore does blocking file I/O, keep it off the Default pool.
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                if (results.isNotEmpty()) ResultStore.save(context, id, results)
                else ResultStore.delete(context, id)
            }
            Stats.record(results.size, first.type)

            // advance a followed show's pointer only on a real result, so a not-yet-
            // available episode is retried not skipped. one atomic conditional UPDATE,
            // no read-modify-write, so it can't resurrect a just-unfollowed show
            val ep = first.episode
            if (results.isNotEmpty() && ep != null) {
                db.favoriteDao().advanceEpisode(first.title, ep, System.currentTimeMillis())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // don't let a cancel mid-write look like a normal finish
        } catch (e: Exception) {
            android.util.Log.w("SubFlow", "history write failed", e)
        }
    }

    /** Serializes the search so a history entry can be re-run later. */
    private fun searchParamsJson(releases: List<Release>): String {
        val first = releases.first()
        return org.json.JSONObject().apply {
            put("title", first.title)
            first.season?.let { put("season", it) }
            first.episode?.let { put("episode", it) }
            if (releases.size > 1) {
                put("seasonMode", true)
                put("episodeEnd", releases.last().episode ?: 0)
            }
            put("type", first.type.name)
            put("format", first.format)
            put("codec", first.codec)
            put("audio", first.audio)
            put("tags", first.tags.joinToString(","))
            first.httpUrl?.let { put("httpUrl", it) }
            put("targetLang", first.targetLang)
        }.toString()
    }

    /** Shows a persisted result set without running a search (history reopen). */
    fun showPersisted(list: List<SubtitleResult>) {
        if (_status.value == PipelineStatus.RUNNING) return
        _logs.value = emptyList()
        _leads.value = emptyList()
        _results.value = list
        _progress.value = 1f
        lastBatch = emptyList()        // no retry-failed for a reopened set
        _lastRelease.value = null      // no stale HTTP source, preview hidden
        _status.value = PipelineStatus.DONE
    }

    /** Full pipeline for a single episode; returns the result list. */
    private suspend fun runPipeline(context: Context, releaseInput: Release): List<SubtitleResult> {
        var release = releaseInput
        val outName = FileUtils.srtNameFor(release.fileName, release.displayName())
        val targetUpper = release.targetLang.uppercase()

        // version stamp first so a screenshot of the log identifies the build
        log(LogLevel.INFO, "SubFlow v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

        // release fingerprint
        log(LogLevel.STEP, L10n.t(R.string.log_step1))
        log(LogLevel.INFO, L10n.t(R.string.log_title_info, release.displayName(), context.getString(release.type.labelRes)))

        // alt titles let a search in one language match files named in another.
        // failure just falls back to the typed title
        val altTitles = AltTitles.resolve(release)
        if (altTitles.isNotEmpty()) {
            release = release.copy(altTitles = altTitles)
            log(LogLevel.INFO, L10n.t(R.string.log_alt_titles, altTitles.joinToString(" · ").take(90)))
        }
        if (release.format.isNotBlank()) {
            log(LogLevel.INFO, L10n.t(R.string.log_format_info, release.format, release.codec, release.audio))
        }

        if (release.httpUrl != null && release.hash == null) {
            log(LogLevel.INFO, L10n.t(R.string.log_http_hash))
            val rh = HashUtils.computeFromHttp(release.httpUrl!!)
            if (rh != null) {
                release = release.copy(hash = rh.hash, fileSize = rh.size)
                log(LogLevel.OK, L10n.t(R.string.log_hash_ok, rh.hash, rh.size / (1024 * 1024)))
            } else {
                log(LogLevel.WARN, L10n.t(R.string.log_hash_fail))
            }
        } else if (release.fileSize != null) {
            log(LogLevel.INFO, L10n.t(R.string.log_torrent_meta, release.fileSize!! / (1024 * 1024)))
        }

        // source cascade
        log(LogLevel.STEP, L10n.t(R.string.log_step2))
        setProgress(0.05f)
        val cascade = SubtitleCascade(
            onLog = { log(LogLevel.INFO, it) },
            onSourceChange = { name, i, total ->
                _currentSource.value = Triple(name, i, total)
                setProgress(0.05f + 0.5f * (i.toFloat() / total))
            }
        )
        val cascadeResult = cascade.run(release)
        log(LogLevel.INFO, L10n.t(R.string.log_cascade_done, cascadeResult.anglesTried))
        _currentSource.value = null

        val results = mutableListOf<SubtitleResult>()

        // sync validation, only when found in the target language
        if (cascadeResult.target.isNotEmpty()) {
            log(LogLevel.STEP, L10n.t(R.string.log_step3))
            setProgress(0.75f)
            // same content can arrive from several sources, drop dupes
            val uniqueTargets = cascadeResult.target.distinctBy { it.content }
            for (sub in uniqueTargets.take(5)) {
                val report = SyncEngine.validateAndSync(release, sub.fileName, sub.content)
                report.warning?.let { log(LogLevel.WARN, it) }
                if (report.tagsMatched) log(LogLevel.OK, L10n.t(R.string.log_tags_match, sub.fileName))
                results += SubtitleResult(
                    fileName = outName,
                    content = report.content,
                    sourceName = sub.sourceName,
                    method = L10n.t(R.string.log_method_human),
                    sizeBytes = report.content.toByteArray().size,
                    episodeLabel = release.displayName(),
                    syncWarning = report.warning,
                    // identity already gate-verified, score is provenance not a re-match
                    qualityScore = if (report.tagsMatched) Quality.HUMAN_TAGS_MATCH else Quality.HUMAN
                )
            }
        } else {
            // generation pipeline
            log(LogLevel.STEP, L10n.t(R.string.log_step4, targetUpper))

            // rank candidates by content-verified language: proven target first
            // (no translation needed), then proven EN, then the rest. a file
            // tagged "tr" whose language can't be proven never jumps ahead of EN
            val translationCandidates = cascadeResult.english
                .map { it to LangDetect.detect(it.content) }
                .sortedWith(
                    compareBy<Pair<DownloadedSubtitle, String?>> { (_, det) ->
                        when (det) {
                            release.targetLang -> 0
                            "en" -> 1
                            null -> 3
                            else -> 2
                        }
                    }
                        // among same-language candidates, the best title match
                        // translates first, not just the longest file
                        .thenByDescending { ContentIdentity.matchScore(release, it.first.fileName) }
                        .thenByDescending { it.first.content.length }
                )
                .map { it.first }
                .toMutableList()
            var sourceSub: DownloadedSubtitle? = translationCandidates.firstOrNull()

            // no sub found but there's an http server, pull a track from the ffmpeg stream
            if (sourceSub == null && release.httpUrl != null) {
                log(LogLevel.INFO, L10n.t(R.string.log_ffmpeg_probe))
                setProgress(0.6f)
                val extracted = FFmpegTools.extractSubtitleFromHttp(
                    release.httpUrl!!, context.cacheDir, release.targetLang
                ) { log(LogLevel.INFO, it) }
                if (extracted != null) {
                    val (file, trackLang) = extracted
                    val content = FileUtils.toUtf8(file.readBytes())
                    file.delete()
                    // track language comes from metadata, verify from content
                    // (the "first track" fallback can be any language)
                    val lang = LangDetect.detect(content) ?: trackLang
                    if (lang != trackLang) {
                        log(LogLevel.WARN, L10n.t(R.string.log_lang_mismatch, "MKV track", trackLang.uppercase(), lang.uppercase()))
                    }
                    if (lang == release.targetLang) {
                        // timing comes from the source, no sync needed
                        results += SubtitleResult(
                            fileName = outName, content = content,
                            sourceName = "MKV embedded track",
                            method = L10n.t(R.string.log_method_embedded),
                            sizeBytes = content.toByteArray().size,
                            episodeLabel = release.displayName(), syncWarning = null,
                            qualityScore = Quality.EMBEDDED
                        )
                    } else {
                        sourceSub = DownloadedSubtitle(outName, content, lang, "MKV embedded track")
                    }
                }
            }

            // nothing else worked, whisper transcription
            if (sourceSub == null && results.isEmpty() && release.httpUrl != null) {
                val srt = whisperPath(context, release)
                if (srt != null) {
                    sourceSub = DownloadedSubtitle(outName, srt, "en", "Whisper")
                }
            }
            if (sourceSub != null && translationCandidates.none { it === sourceSub }) {
                translationCandidates.add(0, sourceSub!!)
            }

            // source-language sub, translate then post-process. if the first
            // candidate is rejected at the gate, try the next (max 3). single-shot
            // would report "not found" despite a good EN source
            if (results.isEmpty()) {
                for (cand in translationCandidates.take(5)) {
                    log(LogLevel.STEP, L10n.t(R.string.log_step5, cand.language.uppercase(), targetUpper))
                    setProgress(0.75f)
                    val translated = translateSubtitle(cand, release) ?: continue
                    // our own post-processed output, reject only on a positive
                    // wrong-language reading. null is short/ambiguous, not a contradiction
                    val det = LangDetect.detect(translated.content)
                    if (det == null || det == release.targetLang) {
                        results += translated.copy(fileName = outName, episodeLabel = release.displayName())
                        break
                    }
                    log(LogLevel.ERROR, L10n.t(R.string.log_gate_reject, cand.sourceName, det.uppercase(), targetUpper))
                }
                if (results.isEmpty() && translationCandidates.isNotEmpty()) {
                    log(LogLevel.ERROR, L10n.t(R.string.log_translate_all_down))
                }
            }

            // last resort: the sub lives only inside a torrent (fresh airing episode).
            // stream just its subtitle track, the video is never downloaded, then
            // translate it if it isn't already the target language.
            if (results.isEmpty() && cascadeResult.magnets.isNotEmpty()) {
                log(LogLevel.STEP, L10n.t(R.string.log_torrent_stream))
                // one warm DHT session tries the best-seeded magnets in turn,
                // returns the first subtitle track it can stream
                val srt = TorrentSubtitle.extractFromMagnets(
                    context, cascadeResult.magnets.take(3), release
                ) { log(LogLevel.INFO, it) }
                if (srt != null) {
                    val det = LangDetect.detect(srt)
                    val produced = if (det == release.targetLang) {
                        SubtitleResult(
                            fileName = outName, content = srt, sourceName = "Torrent (streamed)",
                            method = L10n.t(R.string.log_method_embedded),
                            sizeBytes = srt.toByteArray().size,
                            episodeLabel = release.displayName(), syncWarning = null,
                            qualityScore = Quality.TORRENT
                        )
                    } else {
                        translateSubtitle(
                            DownloadedSubtitle(outName, srt, det ?: "en", "Torrent (streamed)"), release
                        )?.copy(fileName = outName, episodeLabel = release.displayName())
                    }
                    if (produced != null) results += produced
                }
            }

            // a verified (right-episode) target sub whose title score was weak still beats
            // a bare "not found" once translation, torrent and everything else came up empty.
            if (results.isEmpty() && cascadeResult.weakTargets.isNotEmpty()) {
                val w = cascadeResult.weakTargets.first()
                val report = SyncEngine.validateAndSync(release, w.fileName, w.content)
                report.warning?.let { log(LogLevel.WARN, it) }
                results += SubtitleResult(
                    fileName = outName,
                    content = report.content,
                    sourceName = w.sourceName,
                    method = L10n.t(R.string.log_method_human),
                    sizeBytes = report.content.toByteArray().size,
                    episodeLabel = release.displayName(),
                    syncWarning = report.warning,
                    qualityScore = Quality.HUMAN
                )
            }

            if (results.isEmpty() && cascadeResult.leads.isNotEmpty()) {
                log(LogLevel.WARN, L10n.t(R.string.log_leads_found, cascadeResult.leads.size))
                // surface tappable leads on the not-found screen (bounded)
                _leads.update { existing ->
                    (existing + cascadeResult.leads.mapNotNull { c ->
                        c.pageUrl?.let { Lead(c.sourceName, c.title, it) }
                    }).takeLast(30)
                }
                for (lead in cascadeResult.leads.take(5)) {
                    log(LogLevel.INFO, "• [${lead.sourceName}] ${lead.title.take(70)} → ${lead.pageUrl}")
                }
            }
        }

        // language gate backstop. upstream already vetted everything, so this only
        // rejects a positive wrong-language reading. a null detection means the cues
        // are too short or ambiguous to classify, so a genuine short target sub survives.
        val gated = results.filter { r ->
            val detected = LangDetect.detect(r.content)
            val pass = detected == null || detected == release.targetLang
            if (!pass) {
                log(LogLevel.ERROR, L10n.t(R.string.log_gate_reject, r.sourceName, detected.uppercase(), targetUpper))
            }
            pass
        }
        results.clear()
        results.addAll(gated)

        // vad sync, real alignment when an http source is available
        if (results.isNotEmpty() && release.httpUrl != null) {
            vadSyncResults(context, release, results)
        }

        // output
        setProgress(1f)
        if (results.isNotEmpty()) {
            log(LogLevel.STEP, L10n.t(R.string.log_step6, results.size, outName))
            if (com.subflow.data.AppSettings.autoSave) {
                results.forEach { FileUtils.saveToDownloads(context, it.fileName, it.content) }
            }
        } else {
            log(LogLevel.ERROR, L10n.t(R.string.log_not_found, cascadeResult.anglesTried))
            // if the episode number is out of range for the series, say so prominently so
            // a wrong number reads as "doesn't exist" rather than "the app failed".
            if (release.season != null && release.episode != null) {
                val hint = try {
                    OpenSubtitlesOrgSource().rangeHint(release)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
                if (hint != null) {
                    _notice.value = hint
                    log(LogLevel.WARN, hint)
                }
            }
        }
        return results
    }

    /** extracts audio from the stream (never writes video), aligns the sub against the speech bitmap, and applies the offset. */
    private suspend fun vadSyncResults(context: Context, release: Release, results: MutableList<SubtitleResult>) {
        log(LogLevel.STEP, L10n.t(R.string.log_vad_start))
        setProgress(0.9f)
        val wav = FFmpegTools.extractAudioFromHttp(release.httpUrl!!, context.cacheDir) {
            log(LogLevel.INFO, it)
        } ?: run { log(LogLevel.WARN, L10n.t(R.string.log_vad_fail)); return }

        try {
            val audioBitmap = VadSync.speechBitmapFromWav(wav) ?: run {
                log(LogLevel.WARN, L10n.t(R.string.log_vad_fail)); return
            }
            for (i in results.indices) {
                val cues = SyncEngine.parseSrt(results[i].content)
                val aligned = VadSync.align(audioBitmap, cues)
                if (aligned == null) {
                    log(LogLevel.WARN, L10n.t(R.string.log_vad_fail))
                    continue
                }
                val confPct = (aligned.confidence * 100).toInt()
                if (VadSync.isSignificant(aligned)) {
                    if (abs(aligned.scaleFactor - 1.0) > 0.001) {
                        log(LogLevel.OK, L10n.t(R.string.log_vad_scale, aligned.scaleFactor))
                    }
                    log(LogLevel.OK, L10n.t(R.string.log_vad_applied, aligned.offsetMs, confPct))
                    results[i] = results[i].copy(
                        content = VadSync.apply(results[i].content, aligned),
                        syncWarning = null,
                        // measured a real drift and corrected it, nudge by measured confidence
                        qualityScore = Quality.withSync(results[i].qualityScore, confPct)
                    )
                } else {
                    log(LogLevel.OK, L10n.t(R.string.log_vad_aligned, confPct))
                    results[i] = results[i].copy(
                        syncWarning = null,
                        // already well-aligned against the audio, same nudge
                        qualityScore = Quality.withSync(results[i].qualityScore, confPct)
                    )
                }
            }
        } finally {
            wav.delete()
        }
    }

    /** whisper flow: if the model is missing, ask consent, download, then transcribe. */
    private suspend fun whisperPath(context: Context, release: Release): String? {
        if (!WhisperEngine.nativeAvailable) {
            log(LogLevel.WARN, L10n.t(R.string.log_whisper_no_native))
            return null
        }
        if (!WhisperEngine.isModelDownloaded(context)) {
            log(LogLevel.INFO, L10n.t(R.string.log_whisper_need_model, WhisperEngine.MODEL_SIZE_MB))
            val consent = CompletableDeferred<Boolean>()
            whisperConsent = consent
            _whisperConsentNeeded.value = true
            val allowed = consent.await()
            if (!allowed) {
                log(LogLevel.WARN, L10n.t(R.string.log_whisper_denied))
                return null
            }
            log(LogLevel.INFO, L10n.t(R.string.log_whisper_downloading))
            val ok = WhisperEngine.downloadModel(context) { p -> setProgress(0.6f + 0.1f * p) }
            if (!ok) {
                log(LogLevel.ERROR, L10n.t(R.string.log_whisper_dl_fail))
                return null
            }
            log(LogLevel.OK, L10n.t(R.string.log_whisper_model_ready))
        }
        log(LogLevel.INFO, L10n.t(R.string.log_audio_extracting))
        val wav = FFmpegTools.extractAudioFromHttp(release.httpUrl!!, context.cacheDir) {
            log(LogLevel.INFO, it)
        } ?: return null
        log(LogLevel.INFO, L10n.t(R.string.log_whisper_running))
        // the wav is deleted whatever happens: transcription is the longest step in the
        // pipeline and a prime cancel target, and a feature film leaves ~230MB of 16kHz
        // pcm in the cache if the delete is only on the success path.
        val srt = try {
            WhisperEngine.transcribeToSrt(context, wav) { log(LogLevel.WARN, it) }
        } finally {
            wav.delete()
        }
        if (srt != null) log(LogLevel.OK, L10n.t(R.string.log_whisper_ok)) else log(LogLevel.ERROR, L10n.t(R.string.log_whisper_fail))
        return srt
    }

    /**
     * translates the SRT cue-by-cue in batches, then post-processes. if a batch is
     * still untranslated after two passes those cues stay in the source language,
     * and we only return null when nothing translated at all.
     */
    private suspend fun translateSubtitle(sub: DownloadedSubtitle, release: Release): SubtitleResult? {
        val cues = SyncEngine.parseSrt(sub.content)
        if (cues.isEmpty()) return null
        Log.d("SubFlow", "=== TRANSLATION START ===")
        Log.d("SubFlow", "Source lang: ${sub.language} | cues: ${cues.size} | dict: ${MegaDictionary.size} entries")
        val post = PostProcessor(release.targetLang)
        val batches = cues.chunked(TranslationEngine.BATCH_SIZE)
        val batchResults = arrayOfNulls<List<String>>(batches.size)
        // providers that failed earlier in this file are tried last on later batches
        val unhealthyProviders = mutableSetOf<String>()
        // true once the sanitize repair has actually rewritten a line somewhere in this
        // file. backs the uncensored badge, so it is measured, never assumed.
        var toneHardened = false

        // two passes: batches that fail the first are retried on the second,
        // a rate-limited provider may have recovered by then
        for (pass in 1..2) {
            for ((bi, batch) in batches.withIndex()) {
                if (batchResults[bi] != null) continue
                setProgress(0.75f + 0.15f * (bi.toFloat() / batches.size))
                val sourceLines = batch.map { it.text.replace('\n', ' ') }
                val mt = TranslationEngine.translateLines(
                    sourceLines, sub.language, release.targetLang, unhealthyProviders
                ) { msg ->
                    if (bi == 0 && pass == 1) log(LogLevel.INFO, msg)
                } ?: continue
                if (bi == 0) Log.d("SubFlow", "Raw MT sample: ${mt.lines.firstOrNull()?.take(80)}")

                // register-restoration dictionary is target-language specific
                val dictApplied = if (release.targetLang == "tr") MegaDictionary.apply(mt.lines) else mt.lines
                if (bi == 0) Log.d("SubFlow", "Post-dict sample: ${dictApplied.firstOrNull()?.take(80)}")

                var processed = post.processBatch(sourceLines, dictApplied)
                if (processed.retrySuggested) {
                    // nonsense, retry with a different provider (same one repeats the result)
                    val retry = TranslationEngine.translateLines(
                        sourceLines, sub.language, release.targetLang, unhealthyProviders, avoid = mt.provider
                    ) { }
                    if (retry != null) {
                        val retryDict = if (release.targetLang == "tr") MegaDictionary.apply(retry.lines) else retry.lines
                        val reprocessed = post.processBatch(sourceLines, retryDict)
                        if (!reprocessed.retrySuggested) processed = reprocessed
                    }
                }
                if (processed.toneHardened) toneHardened = true
                if (bi == 0) Log.d("SubFlow", "Post-processor sample: ${processed.lines.firstOrNull()?.take(80)}")
                // final term policy: "God" always renders as "Tanrı", never "Allah".
                // guarded per-line so a post-processing quirk can't abort a translation
                // that already succeeded, worst case the original line passes through.
                batchResults[bi] =
                    if (release.targetLang == "tr")
                        processed.lines.map { runCatching { Localize.godToTanri(it) }.getOrDefault(it) }
                    else processed.lines
                if (bi % 5 == 0) log(LogLevel.INFO, L10n.t(R.string.log_batch_progress, bi + 1, batches.size))
            }
            if (batchResults.none { it == null }) break
            // cooldown before the retry pass, rate limits need a moment to recover
            delay(3000)
            unhealthyProviders.clear()
        }

        // always deliver. if some batches couldn't be translated, keep the ones that
        // did and leave the rest in the source language rather than drop the whole
        // file. only give up when nothing translated at all.
        val failed = batchResults.count { it == null }
        if (failed == batches.size) {
            log(LogLevel.ERROR, L10n.t(R.string.log_translate_all_down))
            return null
        }
        if (failed > 0) log(LogLevel.WARN, L10n.t(R.string.log_batches_failed, failed))

        // cues that stay in the source language. batches differ in size (the last one is
        // short), so count cues, not batches — the log line above is transient, this
        // number ships with the result.
        val untranslatedCues = batches.filterIndexed { bi, _ -> batchResults[bi] == null }.sumOf { it.size }
        val untranslatedPct = Quality.untranslatedPercent(untranslatedCues, cues.size)

        // final grammar stage: singular/plural address fix (SUBFLOW_LANGUAGE_RULES 3.2).
        // one ordered pass so the scene tracker sees every cue exactly once. TR only.
        val addressTracker = if (release.targetLang == "tr") SceneParticipantTracker() else null
        val translated = ArrayList<SyncEngine.SrtCue>(cues.size)
        batches.forEachIndexed { bi, batch ->
            val lines = batchResults[bi]
            batch.forEachIndexed { i, cue ->
                var text = lines?.getOrElse(i) { cue.text } ?: cue.text
                if (addressTracker != null) {
                    // read honorific / "you all" / stutter cues off the source, fix the TR line
                    val addressee = addressTracker.next(cue.text)
                    text = runCatching {
                        var t = GrammarFixer.fix(text, addressee)          // 3.2 singular/plural
                        t = GrammarFixer.fixSurpriseParticle(cue.text, t)  // 3.3 stray mı/mi
                        StutterPreserver.apply(cue.text, t)                // 2.1 stutter
                    }.getOrDefault(text)
                }
                translated += cue.copy(text = text)
            }
        }

        log(LogLevel.OK, L10n.t(R.string.log_translate_done, cues.size))
        Log.d("SubFlow", "=== TRANSLATION DONE ===")
        val content = SyncEngine.renderSrt(translated)
        // every quality layer above is gated on "tr" (SUBFLOW_LANGUAGE_RULES 8.1), so any
        // other target language leaves here as raw provider output. It must not be
        // labelled "post-processor": that stage did not run for it.
        val postProcessed = release.targetLang == "tr"
        val fromWhisper = sub.sourceName.contains("Whisper")
        val method = when {
            fromWhisper && postProcessed -> L10n.t(R.string.log_method_whisper)
            fromWhisper -> L10n.t(R.string.log_method_whisper_raw)
            postProcessed -> L10n.t(R.string.log_method_mt, sub.language.uppercase(), release.targetLang.uppercase())
            else -> L10n.t(R.string.log_method_mt_raw, sub.language.uppercase(), release.targetLang.uppercase())
        }
        return SubtitleResult(
            fileName = sub.fileName,
            content = content,
            sourceName = sub.sourceName,
            method = method,
            sizeBytes = content.toByteArray().size,
            episodeLabel = "",
            syncWarning = null,
            // our own EN/JA to TR production is a first-class result, the source was
            // identity-gated and translated in full. whisper transcripts and raw
            // (non-TR) provider output each carry a genuine extra uncertainty, so they
            // sit lower. cues we could not translate come straight off the top: they are
            // the part of the file that does not do what the result claims to do.
            qualityScore = Quality.withUntranslated(
                Quality.forTranslation(fromWhisper, postProcessed),
                untranslatedPct
            ),
            // only claimed when the sanitize repair measurably rewrote a line. the same
            // rail as Quality.CEILING: we don't assert what we couldn't verify, so a file
            // MT never softened simply carries no badge.
            tonePreserved = toneHardened,
            untranslatedPct = untranslatedPct,
            rawMachineTranslation = !postProcessed
        )
    }
}
