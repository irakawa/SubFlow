package com.subflow.utils

import com.subflow.optimization.DeviceProfiler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * OpenSubtitles hash over HTTP Range requests, no full download.
 * hash = fileSize + uint64 sum of first 64KB + uint64 sum of last 64KB.
 */
object HashUtils {

    private const val CHUNK = 65536L

    data class RemoteHash(val hash: String, val size: Long)

    suspend fun computeFromHttp(url: String): RemoteHash? = withContext(DeviceProfiler.ioDispatcher) {
        try {
            val size = contentLength(url) ?: return@withContext null
            if (size < CHUNK * 2) return@withContext null
            val head = rangeBytes(url, 0, CHUNK - 1) ?: return@withContext null
            val tail = rangeBytes(url, size - CHUNK, size - 1) ?: return@withContext null
            var hash = size
            hash += sumChunk(head)
            hash += sumChunk(tail)
            RemoteHash("%016x".format(hash), size)
        } catch (e: Exception) {
            null
        }
    }

    private fun sumChunk(bytes: ByteArray): Long {
        var sum = 0L
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= 8) {
            sum += buf.long
        }
        return sum
    }

    private suspend fun contentLength(url: String): Long? {
        Net.awaitRateLimit(url) // anti-ban
        val req = Request.Builder().url(url).head()
            .header("User-Agent", Net.USER_AGENT).build()
        Net.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.header("Content-Length")?.toLongOrNull()
        }
    }

    private suspend fun rangeBytes(url: String, from: Long, to: Long): ByteArray? {
        Net.awaitRateLimit(url)
        val req = Request.Builder().url(url)
            .header("Range", "bytes=$from-$to")
            .header("User-Agent", Net.USER_AGENT)
            .build()
        Net.client.newCall(req).execute().use { resp ->
            // must be 206. a 200 means Range was ignored and we'd pull the whole file
            if (resp.code != 206) return null
            val bytes = resp.body?.bytes() ?: return null
            return if (bytes.size > (to - from + 1)) bytes.copyOfRange(0, (to - from + 1).toInt()) else bytes
        }
    }
}
