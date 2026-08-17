package com.subflow.pipeline

import android.content.Context
import com.subflow.R
import com.subflow.optimization.DeviceProfiler
import com.subflow.optimization.DeviceTier
import com.subflow.utils.L10n
import com.subflow.utils.Net
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Request

// offline whisper.cpp bridge. model isn't in the APK, it's downloaded on demand (~150MB).
// if libsubflow_whisper.so is missing from this build the feature just degrades.
object WhisperEngine {

    const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
    const val MODEL_SIZE_MB = 148

    val nativeAvailable: Boolean by lazy {
        try {
            System.loadLibrary("subflow_whisper")
            true
        } catch (e: Throwable) {
            false
        }
    }

    fun modelFile(context: Context): File = File(context.filesDir, "ggml-base.bin")

    fun isModelDownloaded(context: Context): Boolean =
        modelFile(context).let { it.exists() && it.length() > 100L * 1024 * 1024 }

    /** bytes on disk, or 0 if absent. */
    fun modelSizeBytes(context: Context): Long =
        modelFile(context).let { if (it.exists()) it.length() else 0L }

    fun deleteModel(context: Context): Boolean = modelFile(context).delete()

    /** progress is 0..1. */
    suspend fun downloadModel(context: Context, onProgress: suspend (Float) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val target = modelFile(context)
            val tmp = File(target.absolutePath + ".part")
            try {
                val req = Request.Builder().url(MODEL_URL)
                    .header("User-Agent", Net.USER_AGENT).build()
                Net.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext false
                    val body = resp.body ?: return@withContext false
                    val total = body.contentLength().takeIf { it > 0 } ?: (MODEL_SIZE_MB * 1024L * 1024L)
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            val buf = ByteArray(256 * 1024)
                            var read: Int
                            var done = 0L
                            while (input.read(buf).also { read = it } != -1) {
                                coroutineContext.ensureActive() // if cancelled, don't keep pulling 150MB
                                output.write(buf, 0, read)
                                done += read
                                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                tmp.renameTo(target)
            } catch (e: CancellationException) {
                // rethrow so the pipeline stays cancelled, not failed
                tmp.delete()
                throw e
            } catch (e: Exception) {
                tmp.delete()
                false
            }
        }

    private const val SAMPLE_RATE = 16_000
    private const val WAV_HEADER_BYTES = 44L
    private const val BYTES_PER_SAMPLE = 2 // pcm_s16le, mono

    /**
     * Float PCM budget for the native transcriber, scaled by device class.
     *
     * The transcriber holds one float per sample for the whole file, so the ceiling is
     * a memory budget, not a taste call: 4 bytes × 16 kHz = 64 KB per second of audio,
     * about 230 MB per hour. Nothing above the JNI boundary survives that allocation
     * failing, so the length has to be refused before it is attempted.
     */
    private val pcmBudgetBytes: Long
        get() = when (DeviceProfiler.detect()) {
            DeviceTier.LOW -> 96L * 1024 * 1024   // ~26 min
            DeviceTier.MID -> 224L * 1024 * 1024  // ~61 min
            DeviceTier.HIGH -> 384L * 1024 * 1024 // ~105 min
        }

    /** how many samples this device is allowed to transcribe in one go. */
    val maxSamples: Int get() = (pcmBudgetBytes / 4).toInt()

    /** samples carried by a 16 kHz mono s16le wav of [fileBytes], header excluded. */
    fun samplesInWav(fileBytes: Long): Int {
        val payload = fileBytes - WAV_HEADER_BYTES
        if (payload <= 0) return 0
        return (payload / BYTES_PER_SAMPLE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun exceedsBudget(fileBytes: Long, maxSamples: Int): Boolean =
        samplesInWav(fileBytes) > maxSamples

    fun minutesForSamples(samples: Int): Int = samples / (SAMPLE_RATE * 60)

    /** WAV (16kHz mono pcm_s16le) to SRT. returns srt content, or null. */
    suspend fun transcribeToSrt(context: Context, wavFile: File, onLog: suspend (String) -> Unit): String? {
        if (!nativeAvailable) {
            onLog(L10n.t(R.string.log_whisper_no_native))
            return null
        }
        if (!isModelDownloaded(context)) return null

        // refuse loudly instead of dying quietly. an oversized file used to reach the
        // native allocator and take the process with it.
        val budget = maxSamples
        if (exceedsBudget(wavFile.length(), budget)) {
            onLog(
                L10n.t(
                    R.string.log_whisper_too_long,
                    minutesForSamples(samplesInWav(wavFile.length())),
                    minutesForSamples(budget)
                )
            )
            return null
        }

        return withContext(Dispatchers.Default) {
            val raw = runNative(modelFile(context).absolutePath, wavFile.absolutePath, budget)
            raw?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * Runs the blocking native call on its own thread so cancellation is not merely
     * recorded but delivered.
     *
     * withContext alone could not cancel this: the coroutine was already inside a native
     * frame that never checks for interruption, so cancelling flipped the UI to
     * "cancelled" while a core kept decoding to the end of the film. invokeOnCancellation
     * fires the moment cancel() is called, and the native side polls that flag before
     * every ggml graph.
     */
    private suspend fun runNative(modelPath: String, wavPath: String, budget: Int): ByteArray? =
        suspendCancellableCoroutine { cont ->
            val worker = Thread {
                val out = try {
                    nativeTranscribe(modelPath, wavPath, budget)
                } catch (e: Throwable) {
                    null
                }
                if (cont.isActive) cont.resume(out) { nativeRequestAbort() }
            }
            cont.invokeOnCancellation { nativeRequestAbort() }
            worker.isDaemon = true
            worker.name = "subflow-whisper"
            worker.start()
        }

    // returns SRT as ByteArray, not String. NewStringUTF's Modified-UTF-8 would break 4-byte UTF-8.
    @JvmStatic
    private external fun nativeTranscribe(modelPath: String, wavPath: String, maxSamples: Int): ByteArray?

    /** asks the running transcription to stop. safe to call when nothing is running. */
    @JvmStatic
    private external fun nativeRequestAbort()
}
