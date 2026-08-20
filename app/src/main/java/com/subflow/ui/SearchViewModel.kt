package com.subflow.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.subflow.data.AppSettings
import com.subflow.data.FavoriteEntry
import com.subflow.data.HistoryEntry
import com.subflow.data.ResultStore
import com.subflow.data.SearchQueue
import com.subflow.data.SubFlowDb
import com.subflow.input.ScreenshotParser
import com.subflow.input.TorrentParser
import com.subflow.models.ContentType
import com.subflow.models.Release
import com.subflow.models.SubtitleResult
import com.subflow.models.TorrentFile
import com.subflow.pipeline.PipelineRunner
import com.subflow.pipeline.ReleaseParser
import com.subflow.BuildConfig
import com.subflow.utils.ApkInstaller
import com.subflow.utils.ConnectivityWatcher
import com.subflow.utils.FileUtils
import com.subflow.utils.ImdbLookup
import com.subflow.utils.TitleSuggestion
import com.subflow.utils.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class InputForm(
    val title: String = "",
    val type: ContentType = ContentType.SERIES,
    val format: String = "WEB-DL",
    val codec: String = "x264",
    val audio: String = "DDP5.1",
    val extraTags: String = "",
    val httpUrl: String = "",
    val season: String = "",
    val episode: String = "",
    // season mode runs episode..episodeEnd in one search
    val seasonMode: Boolean = false,
    val episodeEnd: String = "",
    val fileName: String? = null,
    val fileSize: Long? = null,
    val torrentFiles: List<TorrentFile> = emptyList(),
    // target subtitle language, ISO 639-1
    val targetLang: String = "tr",
    // triggers the field-fill animation after a parse
    val autoFillStamp: Long = 0L
)

// true when new is a different show from old, not the same title typed forward,
// backspaced, or typo-corrected. a different show clears the old episode/edition so
// it can't constrain the new search. we use prefix + edit distance rather than
// contains() so "select-all then retype" isn't treated as the same show.
internal fun isDifferentShow(old: String, new: String): Boolean {
    val a = old.trim().lowercase()
    val b = new.trim().lowercase()
    if (b.isEmpty()) return true
    if (a.isEmpty()) return false
    if (a.startsWith(b) || b.startsWith(a)) return false
    // scale tolerance to the shorter title so long titles allow bigger typos
    val tolerance = maxOf(2, minOf(a.length, b.length) / 4)
    return editDistance(a, b) > tolerance
}

// levenshtein distance
private fun editDistance(a: String, b: String): Int {
    val prev = IntArray(b.length + 1) { it }
    val curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
        }
        System.arraycopy(curr, 0, prev, 0, curr.size)
    }
    return prev[b.length]
}

class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val db = SubFlowDb.get(app)

    val history = db.historyDao().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _form = MutableStateFlow(InputForm(targetLang = AppSettings.defaultTargetLang))
    val form: StateFlow<InputForm> = _form.asStateFlow()

    // title autocomplete suggestions ("youjo" -> Youjo Senki / Saga of Tanya the Evil)
    private val _suggestions = MutableStateFlow<List<TitleSuggestion>>(emptyList())
    val suggestions: StateFlow<List<TitleSuggestion>> = _suggestions.asStateFlow()
    private var suggestJob: Job? = null

    // in-app update: latest github release when it's newer than this build, else null
    private val _update = MutableStateFlow<UpdateChecker.Update?>(null)
    val update: StateFlow<UpdateChecker.Update?> = _update.asStateFlow()
    private val _updateDownloading = MutableStateFlow(false)
    val updateDownloading: StateFlow<Boolean> = _updateDownloading.asStateFlow()
    private var updateChecked = false

    fun checkForUpdate() {
        if (updateChecked) return
        updateChecked = true
        viewModelScope.launch {
            _update.value = try {
                UpdateChecker.check(BuildConfig.VERSION_NAME)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    fun dismissUpdate() {
        _update.value = null
    }

    // downloads the new apk and opens the installer; android updates over the current app
    fun installUpdate() {
        val u = _update.value ?: return
        if (_updateDownloading.value) return
        _updateDownloading.value = true
        viewModelScope.launch {
            val ok = ApkInstaller.downloadAndInstall(getApplication(), u.apkUrl)
            if (!ok) _updateDownloading.value = false // failed, let them retry
        }
    }

    private val _ocrBusy = MutableStateFlow(false)
    val ocrBusy: StateFlow<Boolean> = _ocrBusy.asStateFlow()

    private val _episodePicker = MutableStateFlow<List<TorrentFile>>(emptyList())
    val episodePicker: StateFlow<List<TorrentFile>> = _episodePicker.asStateFlow()

    // one-shot: data arrived via SEND intent, route to the input screen
    private val _pendingShareNav = MutableStateFlow(false)
    val pendingShareNav: StateFlow<Boolean> = _pendingShareNav.asStateFlow()

    fun consumeShareNav() {
        _pendingShareNav.value = false
    }

    // the picked/shared video, kept so a result can be played with it
    private val _pickedVideoUri = MutableStateFlow<Uri?>(null)
    val pickedVideoUri: StateFlow<Uri?> = _pickedVideoUri.asStateFlow()

    // drop the video without clearing the form (used on "Search again")
    fun clearPickedVideo() { _pickedVideoUri.value = null }

    // pipeline state straight from the runner
    val pipelineStatus = PipelineRunner.status
    val logs = PipelineRunner.logs
    val progress = PipelineRunner.progress
    val currentSource = PipelineRunner.currentSource
    val results = PipelineRunner.results
    val leads = PipelineRunner.leads
    val notice = PipelineRunner.notice
    val whisperConsentNeeded = PipelineRunner.whisperConsentNeeded

    fun updateForm(transform: (InputForm) -> InputForm) {
        _form.value = transform(_form.value)
    }

    // switching to a different show clears the old episode/edition, otherwise a stale
    // season/episode from an earlier search carries over and rejects the new one
    fun onTitleChanged(v: String) {
        _form.value =
            if (isDifferentShow(_form.value.title, v))
                // different show, start clean but keep the cross-show prefs (type + lang)
                InputForm(title = v, type = _form.value.type, targetLang = _form.value.targetLang)
            else _form.value.copy(title = v)
        fetchSuggestions(v)
    }

    // debounced title autocomplete. cancels the in-flight lookup on each keystroke.
    private fun fetchSuggestions(query: String) {
        suggestJob?.cancel()
        val q = query.trim()
        if (q.length < 2) {
            _suggestions.value = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(280) // wait for the user to pause typing
            val res = try {
                ImdbLookup.suggest(q)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            // don't offer a suggestion identical to what's already typed
            _suggestions.value = res.filterNot { it.title.equals(q, ignoreCase = true) }
        }
    }

    // user tapped a suggestion, fill the full title (keep an anime/donghua type the user set)
    fun applySuggestion(s: TitleSuggestion) {
        suggestJob?.cancel()
        _suggestions.value = emptyList()
        val cur = _form.value
        val keepType = cur.type == ContentType.ANIME || cur.type == ContentType.DONGHUA
        _form.value = cur.copy(title = s.title, type = if (keepType) cur.type else s.type)
    }

    fun clearSuggestions() {
        suggestJob?.cancel()
        _suggestions.value = emptyList()
    }

    fun resetForm() {
        clearSuggestions()
        _form.value = InputForm(targetLang = AppSettings.defaultTargetLang)
        _pickedVideoUri.value = null // fresh manual search, no video
    }

    // screenshot/clipboard image, ML Kit OCR, then ReleaseParser fills the form
    fun applyScreenshot(uri: Uri, fromShare: Boolean = false) {
        if (fromShare) _pendingShareNav.value = true
        viewModelScope.launch {
            _ocrBusy.value = true
            try {
                val text = ScreenshotParser.extractText(getApplication(), uri)
                if (text.isNotBlank()) applyParsedText(text, fromOcr = true)
            } finally {
                _ocrBusy.value = false
            }
        }
    }

    fun applyParsedText(text: String, fromOcr: Boolean = false) {
        val release = if (fromOcr) ReleaseParser.parseFromOcr(text) else ReleaseParser.parse(text)
        applyRelease(release)
    }

    private fun applyRelease(release: Release) {
        val newTitle = release.title.ifBlank { _form.value.title }
        // parsing a different show starts clean so no stale episode/edition/url leaks in
        val base = if (isDifferentShow(_form.value.title, newTitle))
            InputForm(type = release.type, targetLang = _form.value.targetLang)
        else _form.value
        _form.value = base.copy(
            title = newTitle,
            type = release.type,
            format = release.format.ifBlank { base.format },
            codec = release.codec.ifBlank { base.codec },
            audio = release.audio.ifBlank { base.audio },
            extraTags = release.tags.joinToString(", ").ifBlank { base.extraTags },
            season = release.season?.toString() ?: base.season,
            episode = release.episode?.toString() ?: base.episode,
            episodeEnd = base.episodeEnd,
            seasonMode = base.seasonMode,
            fileName = release.fileName ?: base.fileName,
            autoFillStamp = System.currentTimeMillis()  // trigger the fill animation
        )
    }

    // parses a release from a video file name so the output .srt matches the video
    fun applyVideoFile(uri: Uri, fromShare: Boolean = false) {
        viewModelScope.launch {
            // off the main thread so a slow DocumentsProvider can't ANR
            val name = withContext(Dispatchers.IO) {
                queryDisplayName(uri)
            } ?: return@launch
            applyParsedText(name)
            updateForm { it.copy(fileName = name) }
            _pickedVideoUri.value = uri // enables "open in player" on the result
            // flag nav only after the name resolves so we don't route to an empty form
            if (fromShare) _pendingShareNav.value = true
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    // parses shared .torrent metadata into an episode list
    fun applyTorrent(uri: Uri, fromShare: Boolean = false) {
        if (fromShare) _pendingShareNav.value = true
        viewModelScope.launch {
            val bytes = try {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) { null } ?: return@launch
            val info = TorrentParser.parse(bytes) ?: return@launch
            val videoFiles = info.files.filter {
                it.path.endsWith(".mkv", true) || it.path.endsWith(".mp4", true) || it.path.endsWith(".avi", true)
            }.ifEmpty { info.files }
            applyParsedText(info.name)
            _form.value = _form.value.copy(torrentFiles = videoFiles)
            if (videoFiles.size > 1) {
                // let the user pick from the episode list
                _episodePicker.value = videoFiles
            } else if (videoFiles.size == 1) {
                selectTorrentFile(videoFiles.first())
            }
        }
    }

    fun selectTorrentFile(file: TorrentFile) {
        _episodePicker.value = emptyList()
        val name = file.path.substringAfterLast('/')
        applyParsedText(name)
        _form.value = _form.value.copy(fileName = name, fileSize = file.size)
    }

    fun dismissEpisodePicker() {
        _episodePicker.value = emptyList()
    }

    fun buildRelease(): Release = buildRelease(_form.value)

    fun buildRelease(f: InputForm): Release {
        return Release(
            title = f.title.trim(),
            season = f.season.toIntOrNull(),
            episode = f.episode.toIntOrNull(),
            type = f.type,
            format = f.format,
            codec = f.codec,
            audio = f.audio,
            tags = f.extraTags.split(',').map { it.trim() }.filter { it.isNotBlank() },
            fileSize = f.fileSize,
            httpUrl = f.httpUrl.trim().ifBlank { null },
            fileName = f.fileName,
            torrentFiles = f.torrentFiles,
            targetLang = f.targetLang
        )
    }

    // expands the season-mode episode range into releases (max 50). httpUrl/hash are
    // dropped since a single-file url is meaningless across episodes
    fun buildReleases(): List<Release> = buildReleases(_form.value)

    fun buildReleases(f: InputForm): List<Release> {
        val base = buildRelease(f)
        if (!f.seasonMode) return listOf(base)
        val from = f.episode.toIntOrNull() ?: return listOf(base)
        // blank/invalid end episode means season mode is effectively off, leave base as-is
        val toRaw = f.episodeEnd.toIntOrNull() ?: return listOf(base)
        if (toRaw <= from) return listOf(base)
        val to = minOf(toRaw, from + 49) // clamp to 50
        return (from..to).map { ep ->
            base.copy(episode = ep, fileName = null, httpUrl = null, hash = null, fileSize = null)
        }
    }

    // one-shot: the last search was queued because we were offline
    private val _justQueued = MutableStateFlow(false)
    val justQueued: StateFlow<Boolean> = _justQueued.asStateFlow()
    fun consumeJustQueued() { _justQueued.value = false }

    // live count of searches waiting for connectivity
    val queueSize: StateFlow<Int> = SearchQueue.queue
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchQueue.size)

    fun startPipeline(): SearchStart {
        // offline, queue instead of letting every source time out
        if (!ConnectivityWatcher.isOnline(getApplication())) {
            SearchQueue.enqueue(_form.value)
            _justQueued.value = true
            return SearchStart.QUEUED_OFFLINE
        }
        PipelineRunner.reset()
        return startOutcome(online = true) {
            PipelineRunner.start(getApplication(), buildReleases())
        }
    }

    // runs every queued search as one batch. the queue survives anything short of an
    // actual start. builds from each queued form without touching the live form.
    fun runQueue(): SearchStart {
        if (isBusy()) return SearchStart.REFUSED_BUSY
        val forms = SearchQueue.all()
        if (forms.isEmpty()) return SearchStart.NOTHING_TO_RUN
        if (!ConnectivityWatcher.isOnline(getApplication())) return SearchStart.QUEUED_OFFLINE
        val releases = forms.flatMap { buildReleases(it) }
        // nothing buildable came out of them, so they are not worth keeping either
        if (releases.isEmpty()) { SearchQueue.clear(); return SearchStart.NOTHING_TO_RUN }
        PipelineRunner.reset()
        return queueOutcome(
            busy = false, online = true,
            start = { PipelineRunner.start(getApplication(), releases) },
            clear = SearchQueue::clear
        )
    }



    fun clearQueue() = SearchQueue.clear()

    // re-runs the last search with the same params, season mode included
    fun retryLast(): SearchStart {
        if (isBusy()) return SearchStart.REFUSED_BUSY
        // a false here means there is no last batch, not that we were turned away
        return if (PipelineRunner.retryLastBatch(getApplication())) SearchStart.STARTED
        else SearchStart.NOTHING_TO_RUN
    }

    // episodes from the last batch with no result (season mode only)
    val retryableFailedCount: StateFlow<Int> = PipelineRunner.results
        .map { PipelineRunner.failedReleases().size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // re-runs only the failed episodes, appending to results
    fun retryFailed(): Boolean = PipelineRunner.retryFailed(getApplication())

    val hasLastRelease: StateFlow<Boolean> = PipelineRunner.lastRelease
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** true while a run is in flight, so every entry point can refuse before it acts. */
    fun isBusy(): Boolean = PipelineRunner.status.value == com.subflow.models.PipelineStatus.RUNNING

    fun cancelPipeline() = PipelineRunner.cancel()

    fun answerWhisperConsent(allow: Boolean) = PipelineRunner.answerWhisperConsent(allow)

    fun deleteHistory(entry: HistoryEntry) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ResultStore.delete(getApplication(), entry.id) // off the main thread
            }
            db.historyDao().delete(entry)
        }
    }

    // continue watching, derived from the newest series search

    data class ContinueHint(val title: String, val label: String, val params: String)

    val continueWatching: StateFlow<ContinueHint?> = history
        .map { list -> list.firstNotNullOfOrNull { hintFromHistory(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private fun hintFromHistory(entry: HistoryEntry): ContinueHint? {
        val j = runCatching { JSONObject(entry.params) }.getOrNull() ?: return null
        if (j.optString("type") !in setOf("SERIES", "ANIME", "DONGHUA", "ANIMATION")) return null
        val season = j.optInt("season", 0).takeIf { it > 0 } ?: return null
        val lastEp = (if (j.optBoolean("seasonMode", false)) j.optInt("episodeEnd", 0) else j.optInt("episode", 0))
            .takeIf { it > 0 } ?: return null
        val title = j.optString("title").ifBlank { return null }
        val label = "$title " + "S%02dE%02d".format(java.util.Locale.ROOT, season, lastEp + 1)
        return ContinueHint(title, label, entry.params)
    }

    // searches the next episode behind a continue-watching hint
    fun searchContinue(hint: ContinueHint): SearchStart {
        val j = runCatching { JSONObject(hint.params) }.getOrNull() ?: return SearchStart.NOTHING_TO_RUN
        val lastEp = if (j.optBoolean("seasonMode", false)) j.optInt("episodeEnd", 0) else j.optInt("episode", 0)
        return startFromStoredParams(busy = isBusy(), applyForm = {
            _pickedVideoUri.value = null // different show than any picked video
            _form.value = InputForm(
                title = j.optString("title"),
                season = j.optInt("season", 0).let { if (it > 0) it.toString() else "" },
                episode = (lastEp + 1).toString(),
                type = runCatching { ContentType.valueOf(j.optString("type")) }.getOrDefault(ContentType.SERIES),
                format = j.optString("format"),
                codec = j.optString("codec"),
                audio = j.optString("audio"),
                extraTags = j.optString("tags"),
                targetLang = j.optString("targetLang", "tr")
            )
        }, start = ::startPipeline)
    }

    // favorites / watchlist

    val favorites = db.favoriteDao().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // the last-searched release if it's a followable series with an episode
    val followableRelease: Release?
        get() = PipelineRunner.lastRelease.value?.takeIf {
            it.season != null && it.episode != null &&
                it.type in setOf(ContentType.SERIES, ContentType.ANIME, ContentType.DONGHUA, ContentType.ANIMATION)
        }

    // follow/unfollow the last-searched show, remembering the episode reached
    fun toggleFollow() {
        val r = followableRelease ?: return
        viewModelScope.launch {
            // decide from a fresh DB read, not the WhileSubscribed UI flow, which can
            // fall back to empty when no screen collects it and leave unfollow stuck
            val existing = db.favoriteDao().snapshot().firstOrNull { it.title.equals(r.title, true) }
            if (existing != null) {
                db.favoriteDao().delete(existing)
            } else {
                db.favoriteDao().upsert(
                    FavoriteEntry(
                        title = r.title, type = r.type.name, season = r.season ?: 1,
                        lastEpisode = r.episode ?: 1, targetLang = r.targetLang,
                        format = r.format, codec = r.codec, audio = r.audio,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun unfollow(fav: FavoriteEntry) {
        viewModelScope.launch { db.favoriteDao().delete(fav) }
    }

    // searches the episode after the last reached for a followed show. the favorite's
    // pointer isn't advanced here, the pipeline does that only on a hit so we never
    // skip a not-yet-available episode.
    fun searchNextEpisode(fav: FavoriteEntry): SearchStart {
        val next = fav.lastEpisode + 1
        return startFromStoredParams(busy = isBusy(), applyForm = {
            _pickedVideoUri.value = null // followed show, not the picked video
            _form.value = InputForm(
                title = fav.title, season = fav.season.toString(), episode = next.toString(),
                type = runCatching { ContentType.valueOf(fav.type) }.getOrDefault(ContentType.SERIES),
                format = fav.format, codec = fav.codec, audio = fav.audio, targetLang = fav.targetLang
            )
        }, start = ::startPipeline)
    }

    // one-shot nav target after a history entry is opened
    private val _historyNav = MutableStateFlow<String?>(null)
    val historyNav: StateFlow<String?> = _historyNav.asStateFlow()

    fun consumeHistoryNav() {
        _historyNav.value = null
    }

    // one-shot nav request from a widget or launcher shortcut
    private val _externalNav = MutableStateFlow<String?>(null)
    val externalNav: StateFlow<String?> = _externalNav.asStateFlow()

    fun requestNav(route: String) {
        _externalNav.value = route
    }

    fun consumeExternalNav() {
        _externalNav.value = null
    }

    // opens a history entry. if the saved results are still on disk they load off the
    // main thread and show the result screen, otherwise the search is re-run.
    fun openHistory(entry: HistoryEntry) {
        _pickedVideoUri.value = null // history is unrelated to any picked video
        // rerun only goes to progress if a search actually started (offline gets queued)
        if (entry.resultCount <= 0) { if (rerunHistory(entry).opensProgress) _historyNav.value = "progress"; return }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                ResultStore.load(getApplication(), entry.id)
            }
            if (saved != null) {
                PipelineRunner.showPersisted(saved)
                _historyNav.value = "result"
            } else if (rerunHistory(entry).opensProgress) {
                _historyNav.value = "progress"
            }
        }
    }

    private fun rerunHistory(entry: HistoryEntry): SearchStart {
        val j = runCatching { JSONObject(entry.params) }.getOrNull() ?: return SearchStart.NOTHING_TO_RUN
        fun posInt(key: String) = j.optInt(key, 0).let { if (it > 0) it.toString() else "" }
        return startFromStoredParams(busy = isBusy(), applyForm = {
            _form.value = InputForm(
                title = j.optString("title"),
                season = posInt("season"),
                episode = posInt("episode"),
                seasonMode = j.optBoolean("seasonMode", false),
                episodeEnd = posInt("episodeEnd"),
                type = runCatching { ContentType.valueOf(j.optString("type")) }.getOrDefault(ContentType.SERIES),
                format = j.optString("format"),
                codec = j.optString("codec"),
                audio = j.optString("audio"),
                extraTags = j.optString("tags"),
                httpUrl = j.optString("httpUrl"),
                targetLang = j.optString("targetLang", "tr")
            )
        }, start = ::startPipeline)
    }

    fun saveResultTo(uri: Uri, result: SubtitleResult): Boolean =
        FileUtils.writeToUri(getApplication(), uri, result.content)

    // one-tap save to the Downloads/SubFlow folder
    fun saveResultToDownloads(result: SubtitleResult): Boolean =
        FileUtils.saveToDownloads(getApplication(), result.fileName, result.content)

    // http source for the preview player, if any
    val lastHttpUrl: String?
        get() = PipelineRunner.lastRelease.value?.httpUrl
}

/**
 * What came of a start attempt.
 *
 * A boolean could not say this. "false" meant both "queued because you are offline" and
 * "refused because a search is already running", and the second one used to not be
 * expressible at all — startPipeline() returned a constant true and the UI opened the
 * progress screen on a run that had never started.
 */
enum class SearchStart {
    STARTED,
    QUEUED_OFFLINE,
    REFUSED_BUSY,

    /** the stored parameters would not parse, so there was no search to attempt. */
    NOTHING_TO_RUN;

    /** only a run that actually began has a progress screen worth showing. */
    val opensProgress: Boolean get() = this == STARTED
}

/**
 * Maps a start attempt to its outcome. [start] is only called when online, and its
 * answer is the outcome — a refusal from the pipeline has to survive the trip out.
 */
internal fun startOutcome(online: Boolean, start: () -> Boolean): SearchStart = when {
    !online -> SearchStart.QUEUED_OFFLINE
    start() -> SearchStart.STARTED
    else -> SearchStart.REFUSED_BUSY
}

/**
 * Runs a search built from stored parameters, but only once it is clear one can run.
 *
 * The order is the whole point. These entry points overwrite the input form and drop
 * the picked video before starting, so doing that first and asking afterwards meant a
 * refused search still wiped whatever the user had typed — a refusal that costs
 * something is not a refusal, it is a silent failure with a side effect.
 */
internal fun startFromStoredParams(
    busy: Boolean,
    applyForm: () -> Unit,
    start: () -> SearchStart
): SearchStart {
    if (busy) return SearchStart.REFUSED_BUSY
    applyForm()
    return start()
}

/**
 * Runs the offline queue, and only drops it once something is actually running.
 *
 * SearchQueue.clear() writes through to SharedPreferences, so it cannot be taken back.
 * It used to be called before the start that can refuse, which meant a refused queue
 * run deleted the very searches it declined to run. Clearing is the last thing that
 * happens and only on STARTED, so every other outcome leaves the queue where it was.
 */
internal fun queueOutcome(
    busy: Boolean,
    online: Boolean,
    start: () -> Boolean,
    clear: () -> Unit
): SearchStart = when {
    busy -> SearchStart.REFUSED_BUSY
    !online -> SearchStart.QUEUED_OFFLINE
    else -> startOutcome(online = true, start = start).also {
        if (it == SearchStart.STARTED) clear()
    }
}
