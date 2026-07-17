package com.subflow.pipeline

import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.subflow.R
import com.subflow.models.LangCatalog
import com.subflow.optimization.DeviceProfiler
import com.subflow.utils.L10n
import java.io.File
import kotlinx.coroutines.withContext

// ffmpeg can't run natively on Android, everything goes through FFmpegKit.
// video is streamed over HTTP, never written to disk.
object FFmpegTools {

    // pulls just the subtitle track: target lang, then english, then first track.
    // returns the srt and its lang code, or null.
    suspend fun extractSubtitleFromHttp(
        httpUrl: String,
        outDir: File,
        targetLang: String = "tr",
        onLog: suspend (String) -> Unit
    ): Pair<File, String>? {
        if (!isSafeArg(httpUrl)) return null // a " or newline could inject ffmpeg args
        val target3 = LangCatalog.threeLetter(targetLang)
        val attempts = buildList {
            add(target3 to "-map 0:s:m:language:$target3")
            if (target3 != "eng") add("eng" to "-map 0:s:m:language:eng")
            add("first" to "-map 0:s:0")
        }
        for ((lang, mapArg) in attempts) {
            val out = File(outDir, "extracted_$lang.srt")
            out.delete()
            val cmd = "-y -i \"$httpUrl\" $mapArg -c:s srt \"${out.absolutePath}\""
            onLog(L10n.t(R.string.log_ffmpeg_track_probe, lang))
            val ok = execute(cmd)
            if (ok && out.exists() && out.length() > 200) {
                val language = if (lang == target3) targetLang else "en"
                onLog(L10n.t(R.string.log_ffmpeg_track_ok, lang, out.length() / 1024))
                return out to language
            }
            out.delete()
        }
        onLog(L10n.t(R.string.log_ffmpeg_no_track))
        return null
    }

    // audio only, as 16 kHz mono wav for whisper.
    suspend fun extractAudioFromHttp(httpUrl: String, outDir: File, onLog: suspend (String) -> Unit): File? {
        if (!isSafeArg(httpUrl)) return null
        val out = File(outDir, "audio_16k.wav")
        out.delete()
        onLog(L10n.t(R.string.log_ffmpeg_audio_extract))
        val cmd = "-y -i \"$httpUrl\" -vn -sn -ac 1 -ar 16000 -c:a pcm_s16le \"${out.absolutePath}\""
        val ok = execute(cmd)
        return if (ok && out.exists() && out.length() > 44) {
            onLog(L10n.t(R.string.log_ffmpeg_audio_ready, out.length() / (1024 * 1024)))
            out
        } else {
            out.delete()
            onLog(L10n.t(R.string.log_ffmpeg_audio_fail))
            null
        }
    }

    // mux the subtitle into the mkv, stream copy, no re-encode.
    suspend fun muxSubtitle(inputVideo: String, srtPath: String, outputPath: String): Boolean {
        val cmd = "-y -i \"$inputVideo\" -i \"$srtPath\" -map 0 -map 1 -c copy -c:s srt " +
            "-metadata:s:s:0 language=tur \"$outputPath\""
        return execute(cmd)
    }

    // a real url never has a quote or newline, those could inject ffmpeg args.
    private fun isSafeArg(s: String): Boolean =
        !s.contains('"') && !s.contains('\n') && !s.contains('\r')

    private suspend fun execute(command: String): Boolean =
        withContext(DeviceProfiler.ioDispatcher) {
            try {
                ReturnCode.isSuccess(FFmpegKit.execute(command).returnCode)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // don't mask cancellation as a clean no-track result
            } catch (e: Throwable) {
                false
            }
        }
}
