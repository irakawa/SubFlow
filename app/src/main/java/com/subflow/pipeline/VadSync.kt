package com.subflow.pipeline

import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * offline VAD subtitle sync, ffsubsync style. builds a speech bitmap from audio RMS
 * energy and one from the cues, then correlates to find the best offset and fps scale.
 */
object VadSync {

    const val FRAME_MS = 10
    private const val COARSE_FACTOR = 10          // 100ms coarse window
    private const val MAX_OFFSET_MS = 180_000     // 3 min search each way
    private val FPS_RATIOS = listOf(1.0, 25.0 / 23.976, 23.976 / 25.0, 24.0 / 25.0, 25.0 / 24.0)

    data class SyncResult(
        val offsetMs: Long,
        val scaleFactor: Double,
        val confidence: Double    // 0..1, fraction of aligned speech
    )

    /** 16kHz mono s16le WAV to a 10ms speech bitmap. streams frame by frame, never loads the whole file. */
    fun speechBitmapFromWav(wav: File): BooleanArray? {
        val samplesPerFrame = 16000 * FRAME_MS / 1000   // 160 samples = 320 bytes
        val frameBytes = samplesPerFrame * 2
        val energies = ArrayList<Double>(1 shl 16)
        try {
            java.io.BufferedInputStream(java.io.FileInputStream(wav), 1 shl 16).use { input ->
                val header = ByteArray(12)
                if (!readFully(input, header)) return null
                if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                    String(header, 8, 4, Charsets.US_ASCII) != "WAVE"
                ) return null

                val chunkHead = ByteArray(8)
                var dataSize = -1L
                while (readFully(input, chunkHead)) {
                    val id = String(chunkHead, 0, 4, Charsets.US_ASCII)
                    val size = le32(chunkHead, 4).toLong() and 0xFFFFFFFFL
                    if (id == "data") { dataSize = size; break }
                    skipFully(input, size + (size and 1L)) // including the pad byte
                }
                if (dataSize <= 0) return null

                val buf = ByteArray(frameBytes)
                var remaining = dataSize
                while (remaining >= frameBytes) {
                    if (!readFully(input, buf)) break
                    remaining -= frameBytes
                    var acc = 0.0
                    var i = 0
                    while (i < frameBytes) {
                        val v = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
                        acc += v.toDouble() * v
                        i += 2
                    }
                    energies.add(sqrt(acc / samplesPerFrame))
                }
            }
        } catch (e: Exception) {
            return null
        }
        val frames = energies.size
        if (frames < 100) return null
        // noise floor + 15% of the range
        val sorted = DoubleArray(frames) { energies[it] }.also { it.sort() }
        val noiseFloor = sorted[frames / 10]              // bottom 10%
        val loud = sorted[frames - 1 - frames / 20]       // top 5%
        val threshold = noiseFloor + (loud - noiseFloor) * 0.15
        return BooleanArray(frames) { energies[it] > threshold }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private fun skipFully(input: java.io.InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) return
                remaining--
            } else remaining -= skipped
        }
    }

    /** cues to a speech bitmap */
    fun bitmapFromCues(cues: List<SyncEngine.SrtCue>, totalFrames: Int, scale: Double = 1.0): BooleanArray {
        val bitmap = BooleanArray(totalFrames)
        for (cue in cues) {
            val from = ((cue.startMs * scale) / FRAME_MS).toInt().coerceIn(0, totalFrames)
            val to = ((cue.endMs * scale) / FRAME_MS).toInt().coerceIn(0, totalFrames)
            for (i in from until to) bitmap[i] = true
        }
        return bitmap
    }

    /** best offset and fps scale, or null if confidence is too low. */
    fun align(audio: BooleanArray, cues: List<SyncEngine.SrtCue>): SyncResult? {
        if (cues.isEmpty() || audio.size < 500) return null
        var best: SyncResult? = null
        for (ratio in FPS_RATIOS) {
            val sub = bitmapFromCues(cues, audio.size, ratio)
            val (offsetFrames, score) = bestOffset(audio, sub) ?: continue
            val result = SyncResult(offsetFrames.toLong() * FRAME_MS, ratio, score)
            if (best == null || result.confidence > best!!.confidence) best = result
        }
        // below 55% overlap is unreliable
        return best?.takeIf { it.confidence >= 0.55 }
    }

    /** coarse then fine correlation scan */
    private fun bestOffset(audio: BooleanArray, sub: BooleanArray): Pair<Int, Double>? {
        val maxOffsetFrames = MAX_OFFSET_MS / FRAME_MS

        // coarse scan: 100ms windows
        val audioCoarse = downsample(audio, COARSE_FACTOR)
        val subCoarse = downsample(sub, COARSE_FACTOR)
        val maxCoarse = maxOffsetFrames / COARSE_FACTOR
        var bestCoarse = 0
        var bestScore = -1.0
        for (off in -maxCoarse..maxCoarse) {
            val s = overlapScore(audioCoarse, subCoarse, off)
            if (s > bestScore) { bestScore = s; bestCoarse = off }
        }
        if (bestScore <= 0) return null

        // fine scan around the coarse result, 10ms step
        val center = bestCoarse * COARSE_FACTOR
        var bestFine = center
        var bestFineScore = -1.0
        for (off in (center - 15)..(center + 15)) {
            val s = overlapScore(audio, sub, off)
            if (s > bestFineScore) { bestFineScore = s; bestFine = off }
        }
        return bestFine to bestFineScore
    }

    private fun downsample(src: BooleanArray, factor: Int): BooleanArray {
        val out = BooleanArray(src.size / factor)
        for (i in out.indices) {
            var hits = 0
            for (j in 0 until factor) if (src[i * factor + j]) hits++
            out[i] = hits > factor / 2
        }
        return out
    }

    /** overlap ratio of sub speech against audio */
    private fun overlapScore(audio: BooleanArray, sub: BooleanArray, offset: Int): Double {
        var overlap = 0
        var subSpeech = 0
        for (i in sub.indices) {
            if (!sub[i]) continue
            subSpeech++
            val j = i + offset
            if (j in audio.indices && audio[j]) overlap++
        }
        if (subSpeech < 50) return -1.0
        return overlap.toDouble() / subSpeech
    }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    /**
     * Apply the offset + scale to the SRT content.
     *
     * Intentionally unused right now: PipelineRunner.withVadObservation explains why a
     * reading is not acted on while [align]'s confidence is a measure of the audio's
     * speech density rather than of agreement. Kept because it is correct in itself and
     * is what the pipeline will call again once that statistic is replaced.
     */
    fun apply(content: String, result: SyncResult): String {
        val cues = SyncEngine.parseSrt(content).map {
            it.copy(
                startMs = maxOf(0, (it.startMs * result.scaleFactor).toLong() + result.offsetMs),
                endMs = maxOf(1, (it.endMs * result.scaleFactor).toLong() + result.offsetMs)
            )
        }
        return SyncEngine.renderSrt(cues)
    }

    fun isSignificant(result: SyncResult): Boolean =
        abs(result.offsetMs) > 100 || abs(result.scaleFactor - 1.0) > 0.001
}
