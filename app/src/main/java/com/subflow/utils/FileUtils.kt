package com.subflow.utils

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

object FileUtils {

    /** .srt name from the video name, extension swapped */
    fun srtNameFor(videoName: String?, fallback: String): String {
        val base = videoName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: fallback
        return sanitize(base) + ".srt"
    }

    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "subtitle" }

    /**
     * downloaded subtitle bytes to a utf-8 string.
     * reads a utf-8/16 BOM, otherwise strict utf-8 then falls back to
     * cp1254 (windows-1254), common in turkish subs.
     */
    fun toUtf8(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            // cp1254 for turkish
            try {
                String(bytes, Charset.forName("windows-1254"))
            } catch (e2: Exception) {
                String(bytes, Charsets.ISO_8859_1)
            }
        }
    }

    /** subs are small. anything bigger is junk or a zip bomb. */
    private const val MAX_SUBTITLE_BYTES = 25 * 1024 * 1024

    /** reads the whole stream, returns null past [max] bytes. */
    private fun java.io.InputStream.readBounded(max: Int = MAX_SUBTITLE_BYTES): ByteArray? {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = read(chunk)
            if (n < 0) break
            total += n
            if (total > max) return null
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    fun extractSubtitlesFromZip(zipBytes: ByteArray): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val lower = entry.name.lowercase()
                    if (!entry.isDirectory &&
                        (lower.endsWith(".srt") || lower.endsWith(".ass") ||
                         lower.endsWith(".ssa") || lower.endsWith(".vtt"))
                    ) {
                        zis.readBounded()?.let { out += entry!!.name.substringAfterLast('/') to it }
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            // corrupt archive
        }
        return out
    }

    fun gunzip(bytes: ByteArray): ByteArray? = try {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBounded() }
    } catch (e: Exception) {
        null
    }

    fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    fun looksLikeGzip(bytes: ByteArray): Boolean =
        bytes.size > 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()

    /** xz magic FD 37 7A 58 5A. animetosho serves .ass.xz / .srt.xz. */
    fun looksLikeXz(bytes: ByteArray): Boolean =
        bytes.size > 6 && bytes[0] == 0xFD.toByte() && bytes[1] == 0x37.toByte() &&
            bytes[2] == 0x7A.toByte() && bytes[3] == 0x58.toByte() && bytes[4] == 0x5A.toByte()

    fun unxz(bytes: ByteArray): ByteArray? = try {
        org.tukaani.xz.XZInputStream(ByteArrayInputStream(bytes)).use { it.readBounded() }
    } catch (e: Throwable) {
        null
    }

    /** "Rar!", shared by RAR4 and RAR5. */
    fun looksLikeRar(bytes: ByteArray): Boolean =
        bytes.size > 7 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'a'.code.toByte() &&
            bytes[2] == 'r'.code.toByte() && bytes[3] == '!'.code.toByte()

    /**
     * turkish sources mostly ship .rar, so without this every download from them
     * quietly degraded to a manual lead. junrar is pure java.
     */
    fun extractSubtitlesFromRar(rarBytes: ByteArray): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        try {
            com.github.junrar.Archive(ByteArrayInputStream(rarBytes)).use { archive ->
                for (header in archive.fileHeaders) {
                    if (header.isDirectory) continue
                    val name = (header.fileName ?: continue).replace('\\', '/')
                    val lower = name.lowercase()
                    if (lower.endsWith(".srt") || lower.endsWith(".ass") ||
                        lower.endsWith(".ssa") || lower.endsWith(".vtt")
                    ) {
                        val buf = ByteArrayOutputStream()
                        // cap extraction so a crafted archive can't blow up to gigabytes
                        val bounded = object : java.io.OutputStream() {
                            var written = 0
                            override fun write(b: Int) { if (++written > MAX_SUBTITLE_BYTES) throw java.io.IOException("bomb"); buf.write(b) }
                            override fun write(b: ByteArray, off: Int, len: Int) {
                                written += len
                                if (written > MAX_SUBTITLE_BYTES) throw java.io.IOException("bomb")
                                buf.write(b, off, len)
                            }
                        }
                        runCatching { archive.extractFile(header, bounded) }
                            .onSuccess { out += name.substringAfterLast('/') to buf.toByteArray() }
                    }
                }
            }
        } catch (e: Throwable) {
            // return what we got
        }
        return out
    }

    /**
     * saves to Downloads/SubFlow in one tap. MediaStore.Downloads needs no
     * permission on API 29+.
     */
    fun saveToDownloads(context: Context, fileName: String, content: String): Boolean {
        return try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, sanitize(fileName))
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip")
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS + "/SubFlow"
                )
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return false
            val ok = try {
                resolver.openOutputStream(uri)?.use { os ->
                    os.write(content.toByteArray(Charsets.UTF_8))
                    os.flush()
                } != null
            } catch (e: Exception) {
                false
            }
            // on failure, drop the empty MediaStore entry so retries don't
            // pile up as "name (1).srt"
            if (!ok) runCatching { resolver.delete(uri, null, null) }
            ok
        } catch (e: Exception) {
            false
        }
    }

    private fun cacheUri(context: Context, fileName: String, content: String): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, sanitize(fileName))
        file.writeText(content, Charsets.UTF_8)
        return androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
    }

    /** cache file + system share sheet. */
    fun shareSubtitle(context: Context, fileName: String, content: String) {
        try {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/x-subrip"
                putExtra(android.content.Intent.EXTRA_STREAM, cacheUri(context, fileName, content))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                android.content.Intent.createChooser(send, fileName)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // sharing unavailable
        }
    }

    /** writes subtitles to cache, returns their Uris. off the main thread. */
    fun writeShareFiles(context: Context, items: List<Pair<String, String>>): ArrayList<Uri> =
        ArrayList(items.mapNotNull { runCatching { cacheUri(context, it.first, it.second) }.getOrNull() })

    /** multi-file share sheet for pre-written Uris. main thread. */
    fun fireShareMultiple(context: Context, uris: ArrayList<Uri>) {
        if (uris.isEmpty()) return
        try {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/x-subrip"
                putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                android.content.Intent.createChooser(send, null)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // sharing unavailable
        }
    }

    /** writes to a SAF-picked Uri */
    fun writeToUri(context: Context, uri: Uri, content: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
                os.flush()
            } != null
        } catch (e: Exception) {
            false
        }
    }

    /** ASS/SSA to plain SRT */
    fun assToSrt(ass: String): String {
        val cues = StringBuilder()
        var index = 1
        for (line in ass.lineSequence()) {
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val parts = line.substringAfter(":").split(",", limit = 10)
            if (parts.size < 10) continue
            val start = assTime(parts[1].trim()) ?: continue
            val end = assTime(parts[2].trim()) ?: continue
            var text = parts[9]
                .replace(Regex("\\{[^}]*\\}"), "")   // style tags
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .trim()
            if (text.isBlank()) continue
            cues.append(index++).append('\n')
                .append(srtTime(start)).append(" --> ").append(srtTime(end)).append('\n')
                .append(text).append("\n\n")
        }
        return cues.toString()
    }

    private fun assTime(t: String): Long? {
        // H:MM:SS.cc
        val m = Regex("(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})").matchEntire(t) ?: return null
        val (h, min, s, cs) = m.destructured
        return h.toLong() * 3600_000 + min.toLong() * 60_000 + s.toLong() * 1000 + cs.toLong() * 10
    }

    fun srtTime(ms: Long): String {
        val h = ms / 3600_000
        val m = (ms % 3600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val mil = ms % 1000
        // Locale.ROOT matters: an arabic locale makes %d emit eastern arabic
        // digits and the SRT timestamps go invalid
        return "%02d:%02d:%02d,%03d".format(java.util.Locale.ROOT, h, m, s, mil)
    }
}
