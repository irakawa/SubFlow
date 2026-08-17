package com.subflow.pipeline

import android.content.Context
import android.util.Log
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.swig.settings_pack
import com.subflow.R
import com.subflow.utils.ConnectivityWatcher
import com.subflow.utils.FileUtils
import com.subflow.utils.L10n
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pulls a subtitle out of a torrent without fetching the whole video.
 *
 * The video file is exposed via a tiny loopback HTTP server backed by libtorrent.
 * ffmpeg reads only the subtitle track over HTTP range requests and libtorrent
 * fetches just the pieces those ranges touch. Best-effort: any failure or the
 * timeout returns null, and everything is torn down in finally.
 *
 * Note what this is not: the pieces it does fetch are pieces of the video file, they
 * land in cacheDir until the finally block removes them, and joining the swarm means
 * libtorrent can upload the pieces it holds while the session runs. "Only part of the
 * video" is the honest claim here, not "no video" — see the README disclaimer.
 */
object TorrentSubtitle {

    private const val TAG = "SubFlow"
    private const val OVERALL_TIMEOUT_MS = 150_000L
    private const val METADATA_TIMEOUT_MS = 75_000L
    private const val DHT_WARMUP_MS = 12_000L // max time to wait for the DHT to find nodes

    /**
     * Hard ceiling on payload pulled from the swarm, across every magnet in one attempt.
     *
     * ffmpeg has no index for the container, so it seeks around to find the subtitle
     * track and libtorrent fetches whatever pieces those seeks land on. Parsing the MKV
     * SeekHead/Cues would make the reads targeted; until then this is what keeps a
     * subtitle lookup from quietly pulling a gigabyte of video onto the device. It is a
     * harm bound, not a technical requirement — hitting it aborts the attempt.
     */
    const val MAX_DOWNLOAD_BYTES = 256L * 1024 * 1024

    /** libtorrent reports -1 before a handle produces real status; that is not over budget. */
    fun overBudget(downloadedBytes: Long): Boolean =
        downloadedBytes >= MAX_DOWNLOAD_BYTES

    fun megabytes(bytes: Long): Int =
        if (bytes <= 0) 0 else (bytes / (1024 * 1024)).toInt()

    // dht bootstrap routers. on mobile the default start() often reaches no dht nodes,
    // so magnet metadata never resolves. setting these and waiting for the node count to
    // climb before fetching fixes the stall.
    private const val DHT_ROUTERS =
        "dht.libtorrent.org:25401,router.bittorrent.com:6881,router.utorrent.com:6881," +
            "dht.transmissionbt.com:6881,router.bitcomet.com:6881,dht.aelitis.com:6881"

    private fun tunedSession(): SessionManager {
        val sp = SettingsPack()
            .enableDht(true)
            .broadcastLSD(true) // local peer discovery (same Wi-Fi)
            .setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), DHT_ROUTERS)
            .setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
            .setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
        return SessionManager().apply { start(SessionParams(sp)) }
    }

    // open trackers. a fresh magnet's own trackers are often dead, these give libtorrent
    // peer sources immediately so metadata resolves without waiting on the dht.
    private val extraTrackers = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://open.demonii.com:1337/announce",
        "http://nyaa.tracker.wf:7777/announce",
        "udp://tracker.coppersurfer.tk:6969/announce"
    )

    private fun withTrackers(magnet: String): String {
        if (!magnet.startsWith("magnet:")) return magnet
        val sb = StringBuilder(magnet)
        for (t in extraTrackers) {
            val enc = java.net.URLEncoder.encode(t, "UTF-8")
            if (!magnet.contains(t) && !magnet.contains(enc)) sb.append("&tr=").append(enc)
        }
        return sb.toString()
    }

    /** Single magnet or raw .torrent bytes (kept for callers that have exactly one). */
    suspend fun extract(
        context: Context,
        source: Any,
        release: com.subflow.models.Release,
        onLog: suspend (String) -> Unit
    ): String? = if (source is String) extractFromMagnets(context, listOf(source), release, onLog)
    else withContext(Dispatchers.IO) {
        val session = tunedSession()
        try {
            withTimeoutOrNull(OVERALL_TIMEOUT_MS) {
                try { run(context, session, source, release, onLog) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { null }
            }
        } finally { runCatching { session.stop() } }
    }

    /**
     * Tries several magnets with one shared, dht-warmed session so the bootstrap is paid
     * once and every attempt reuses the growing node table. Stops at the first success.
     */
    suspend fun extractFromMagnets(
        context: Context,
        magnets: List<String>,
        release: com.subflow.models.Release,
        onLog: suspend (String) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        if (magnets.isEmpty()) return@withContext null

        // this path costs real data, so say so before spending any of it
        onLog(L10n.t(R.string.log_torrent_data_warning, megabytes(MAX_DOWNLOAD_BYTES)))
        if (ConnectivityWatcher.isMetered(context)) {
            // the user asked for a subtitle, not a data bill. Wi-Fi is one tap away.
            onLog(L10n.t(R.string.log_torrent_metered_skip))
            return@withContext null
        }
        // a previous run that was killed outright never reached its finally block
        runCatching { File(context.cacheDir, "torrent").deleteRecursively() }

        val session = tunedSession()
        try {
            // wait for the dht to acquire nodes before asking for peers. done once here,
            // shared by every magnet below. a 0 node count means the network blocks p2p;
            // a healthy count with no metadata means the torrent has no reachable seeders.
            var waited = 0L
            while (session.dhtNodes() < 10 && waited < DHT_WARMUP_MS) { delay(500); waited += 500 }
            onLog("torrent: DHT ${session.dhtNodes()} düğüm hazır")
            for (magnet in magnets) {
                val r = withTimeoutOrNull(OVERALL_TIMEOUT_MS) {
                    // don't runCatching the suspend call, it would swallow real cancellation.
                    // run() handles its own errors and rethrows cancels.
                    try { run(context, session, magnet, release, onLog) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Throwable) { null }
                }
                if (r != null) return@withContext r
            }
            null
        } finally {
            runCatching { session.stop() }
        }
    }

    private suspend fun run(
        context: Context,
        session: SessionManager,
        source: Any,
        release: com.subflow.models.Release,
        onLog: suspend (String) -> Unit
    ): String? {
        var dlDir: File? = null // per-magnet dir, set once the info-hash is known
        var handle: TorrentHandle? = null
        var server: LoopbackFileServer? = null
        try {
            val ti: TorrentInfo = when (source) {
                is ByteArray -> TorrentInfo(source)
                is String -> {
                    onLog("torrent: fetching metadata… (bağlanılıyor)")
                    // fetchMagnet is a blocking native call. runInterruptible lets cancellation
                    // interrupt the thread instead of hanging on it. the timeout arg still bounds it.
                    val data = runInterruptible {
                        session.fetchMagnet(withTrackers(source), (METADATA_TIMEOUT_MS / 1000).toInt(), false)
                    }
                    if (data == null) {
                        // most common causes: no seeders reachable, or the network blocks P2P
                        onLog("torrent: metadata alınamadı — eş (peer) bulunamadı; WiFi/farklı ağ dene")
                        return null
                    }
                    TorrentInfo(data)
                }
                else -> return null
            }

            // pick the episode's video file (largest video whose name matches)
            val files = ti.files()
            var fileIndex = -1
            var bestSize = -1L
            for (i in 0 until files.numFiles()) {
                val name = files.fileName(i).lowercase()
                val isVideo = name.endsWith(".mkv") || name.endsWith(".mp4")
                if (!isVideo) continue
                val epOk = release.episode?.let { ep ->
                    ContentIdentity.extractEpisode(files.fileName(i), release.type, release) == ep
                } ?: true
                if (epOk && files.fileSize(i) > bestSize) {
                    bestSize = files.fileSize(i); fileIndex = i
                }
            }
            if (fileIndex < 0) { onLog("torrent: no matching video file"); return null }

            // dir keyed by info-hash so a slow async remove() of a prior magnet can't
            // collide with this one's storage. each magnet owns its own subdir.
            val dir = File(File(context.cacheDir, "torrent"), ti.infoHash().toString()).apply { mkdirs() }
            dlDir = dir
            session.download(ti, dir)
            handle = waitForHandle(session, ti) ?: return null

            // download only the chosen file so ffmpeg's range reads resolve quickly
            val priorities = Array(files.numFiles()) { if (it == fileIndex) Priority.NORMAL else Priority.IGNORE }
            handle.prioritizeFiles(priorities)
            handle.resume()

            val videoFile = File(dir, files.filePath(fileIndex))
            // measured at session level so the ceiling covers every magnet in this attempt,
            // not each one separately
            server = LoopbackFileServer(handle, ti, fileIndex, videoFile) {
                runCatching { session.totalDownload() }.getOrDefault(0L)
            }.also { it.start() }
            val url = "http://127.0.0.1:${server.port}/video"
            // shown to the user, so it says what actually happens: the pieces the subtitle
            // track spans are fetched, not the whole file, and not nothing
            onLog("torrent: altyazı izi akıtılıyor — yalnızca izin kapsadığı parçalar iniyor")

            val extracted = FFmpegTools.extractSubtitleFromHttp(url, context.cacheDir, release.targetLang) { onLog(it) }
            if (server.budgetExceeded) {
                // say why it stopped, otherwise this reads as "no subtitle in this torrent"
                onLog(L10n.t(R.string.log_torrent_budget_hit, megabytes(MAX_DOWNLOAD_BYTES)))
                return null
            }
            val content = extracted?.let { (file, _) ->
                val text = FileUtils.toUtf8(file.readBytes()); file.delete(); text
            }
            return content?.takeIf { it.contains("-->") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // never swallow cancellation, let it unwind
        } catch (e: Throwable) {
            Log.w(TAG, "torrent extract failed: ${e.message}")
            return null
        } finally {
            // per-magnet teardown only. the caller stops the shared session, so the dht
            // stays warm for the next magnet.
            runCatching { server?.close() }
            runCatching { handle?.let { session.remove(it) } }
            runCatching { dlDir?.deleteRecursively() }
        }
    }

    private suspend fun waitForHandle(session: SessionManager, ti: TorrentInfo): TorrentHandle? {
        val hash = ti.infoHash()
        repeat(60) {
            val h = session.find(hash)
            if (h != null && h.isValid) return h
            delay(500)
        }
        return null
    }

    /**
     * Minimal loopback HTTP/1.1 server that serves the torrent file's bytes, honoring
     * Range requests and blocking until libtorrent has the covering pieces.
     */
    private class LoopbackFileServer(
        private val handle: TorrentHandle,
        private val ti: TorrentInfo,
        private val fileIndex: Int,
        private val file: File,
        private val downloadedBytes: () -> Long
    ) {
        /** set when the attempt was stopped by the data ceiling rather than by not finding a track. */
        @Volatile
        var budgetExceeded = false
            private set

        private val socket = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        @Volatile private var running = true
        private val fileOffset = ti.files().fileOffset(fileIndex)
        private val fileSize = ti.files().fileSize(fileIndex)
        private val pieceLen = ti.pieceLength().toLong()
        // in-flight workers so close() can join them before teardown
        private val workers = java.util.concurrent.CopyOnWriteArrayList<Thread>()
        private val activeConns = java.util.concurrent.atomic.AtomicInteger(0)

        fun start() {
            Thread {
                while (running) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    // loopback port is reachable by any local app, cap concurrency so a
                    // rogue client can't pile up blocked threads
                    if (activeConns.get() >= MAX_CONNS) { runCatching { client.close() }; continue }
                    activeConns.incrementAndGet()
                    val t = Thread {
                        try { runCatching { serve(client) } } finally { activeConns.decrementAndGet() }
                    }
                    workers += t
                    t.isDaemon = true
                    t.start()
                }
            }.also { it.isDaemon = true }.start()
        }

        private fun serve(client: java.net.Socket) { client.use { c ->
            c.soTimeout = SOCKET_TIMEOUT_MS // don't let a silent client block a thread forever
            val reader = c.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return@use
            // only the one known path is served
            if (!requestLine.contains("/video")) {
                runCatching { c.getOutputStream().write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray()) }
                return@use
            }
            var rangeStart = 0L
            var rangeEnd = fileSize - 1
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                if (line.startsWith("Range:", true)) {
                    val spec = line.substringAfter("bytes=").trim()
                    val parts = spec.split("-")
                    parts.getOrNull(0)?.toLongOrNull()?.let { rangeStart = it }
                    parts.getOrNull(1)?.toLongOrNull()?.let { rangeEnd = it }
                }
                line = reader.readLine()
            }
            rangeStart = rangeStart.coerceIn(0, fileSize - 1)
            rangeEnd = rangeEnd.coerceIn(rangeStart, fileSize - 1)
            val length = rangeEnd - rangeStart + 1

            // stream the range in bounded chunks. an open-ended "bytes=0-" must never
            // buffer a multi-GB file into one array.
            val head = buildString {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Type: video/x-matroska\r\n")
                append("Content-Range: bytes $rangeStart-$rangeEnd/$fileSize\r\n")
                append("Content-Length: $length\r\n\r\n")
            }
            val outStream = c.getOutputStream()
            outStream.write(head.toByteArray())
            val chunk = ByteArray(256 * 1024)
            RandomAccessFile(file, "r").use { raf ->
                var pos = rangeStart
                while (pos <= rangeEnd && running) {
                    val end = minOf(pos + chunk.size - 1, rangeEnd)
                    if (!awaitPieces(pos, end)) return@use
                    raf.seek(pos)
                    val toRead = (end - pos + 1).toInt()
                    raf.readFully(chunk, 0, toRead)
                    outStream.write(chunk, 0, toRead)
                    pos += toRead
                }
            }
            outStream.flush()
        } }

        /** Bumps priority for the pieces covering [start,end] and blocks until present. */
        private fun awaitPieces(start: Long, end: Long): Boolean {
            val first = ((fileOffset + start) / pieceLen).toInt()
            val last = ((fileOffset + end) / pieceLen).toInt()
            for (p in first..last) {
                handle.piecePriority(p, Priority.SEVEN)
                handle.setPieceDeadline(p, 0)
            }
            val deadline = System.currentTimeMillis() + 60_000L
            for (p in first..last) {
                while (!handle.havePiece(p)) {
                    // abort on shutdown, otherwise we'd serve zero-filled bytes as if real
                    if (!running) return false
                    if (System.currentTimeMillis() > deadline) return false
                    // the ceiling is checked here because this is the only place that waits
                    // on the swarm: every byte this attempt costs arrives during this loop
                    if (overBudget(downloadedBytes())) {
                        budgetExceeded = true
                        running = false // stop serving, don't start another range
                        return false
                    }
                    Thread.sleep(120)
                }
            }
            return true
        }

        fun close() {
            running = false
            runCatching { socket.close() }
            // wait for workers so teardown can't race a thread still reading the file
            workers.forEach { runCatching { it.join(2000) } }
        }

        private companion object {
            const val MAX_CONNS = 4
            const val SOCKET_TIMEOUT_MS = 20_000
        }
    }
}
