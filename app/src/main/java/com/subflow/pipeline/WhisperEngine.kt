package com.subflow.pipeline

import android.content.Context
import com.subflow.utils.Net
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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

    /** WAV (16kHz mono pcm_s16le) to SRT. returns srt content, or null. */
    suspend fun transcribeToSrt(context: Context, wavFile: File, onLog: suspend (String) -> Unit): String? {
        if (!nativeAvailable) {
            onLog(com.subflow.utils.L10n.t(com.subflow.R.string.log_whisper_no_native))
            return null
        }
        if (!isModelDownloaded(context)) return null
        return withContext(Dispatchers.Default) {
            try {
                val raw = nativeTranscribe(modelFile(context).absolutePath, wavFile.absolutePath)
                raw?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e // long transcription is a prime cancel target, stay cancelled
            } catch (e: Throwable) {
                null
            }
        }
    }

    // returns SRT as ByteArray, not String. NewStringUTF's Modified-UTF-8 would break 4-byte UTF-8.
    @JvmStatic
    private external fun nativeTranscribe(modelPath: String, wavPath: String): ByteArray?
}
