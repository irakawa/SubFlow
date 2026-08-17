package com.subflow.pipeline

import com.subflow.R
import com.subflow.utils.L10n
import com.subflow.utils.Net
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * free translation cascade, reliability order: google gtx, google clients5, libretranslate, mymemory.
 * returns null if every provider is down. batches lines so cues keep context.
 */
object TranslationEngine {

    /** cues per request. bigger batch, fewer requests. */
    const val BATCH_SIZE = 25

    @Volatile private var lastFail: String? = null

    /** rare separator that survives translation. */
    private const val SEP = "\n␟\n"

    /** splits into request-sized chunks without ever cutting a line or the SEP marker. */
    private fun chunkBySep(joined: String, maxChars: Int): List<String> {
        val lines = joined.split(SEP)
        val chunks = mutableListOf<String>()
        val sb = StringBuilder()
        for (line in lines) {
            val addLen = line.length + if (sb.isEmpty()) 0 else SEP.length
            if (sb.isNotEmpty() && sb.length + addLen > maxChars) {
                chunks += sb.toString(); sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append(SEP)
            sb.append(line)
        }
        if (sb.isNotEmpty()) chunks += sb.toString()
        return chunks.ifEmpty { listOf(joined) }
    }

    /** a translation plus the provider that made it, so a retry can pick another. */
    data class MtResult(val lines: List<String>, val provider: String)

    /**
     * Runs one provider call, turning a failure into null.
     *
     * Cancellation is not a failure. Swallowing it marked healthy providers unhealthy
     * and wrote "provider failed" lines into the user-facing log while the search was
     * already being torn down.
     */
    internal suspend fun attempt(call: suspend () -> String?): String? =
        try {
            call()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /**
     * Translates [lines] into [targetLang], preserving line count.
     *
     * @param unhealthy providers that already failed for this file; tried last so a
     *   rate-limited service isn't hammered, and removed again on success.
     * @param avoid a provider to skip entirely, used when retrying a nonsense batch.
     * @return the translation, or null if every provider failed.
     */
    suspend fun translateLines(
        lines: List<String>,
        sourceLang: String,
        targetLang: String = "tr",
        unhealthy: MutableSet<String>? = null,
        avoid: String? = null,
        onLog: suspend (String) -> Unit
    ): MtResult? {
        if (lines.isEmpty()) return MtResult(emptyList(), "none")
        val src = normalizeLang(sourceLang)
        if (src == targetLang) return MtResult(lines, "passthrough")
        val joined = lines.joinToString(SEP)

        // alive providers, distinct infra. dead ones removed.
        val providers = listOf<Pair<String, suspend () -> String?>>(
            "Google" to { googleGtx(joined, src, targetLang) },
            "GoogleDict" to { googleDict(joined, src, targetLang) },
            "LibreTranslate" to { libreTranslate("https://translate.fedilab.app/translate", joined, src, targetLang) },
            "MyMemory" to { myMemoryBatch(lines, src, targetLang)?.joinToString(SEP) }
        ).filterNot { it.first == avoid } // skip the provider that produced nonsense
            .sortedBy { if (unhealthy?.contains(it.first) == true) 1 else 0 }

        for ((name, provider) in providers) {
            lastFail = null
            var result = attempt { provider() }
            // 429s recover fast, one paced retry beats burning the whole pool
            if (result == null && lastFail?.contains("429") == true) {
                delay(2000)
                lastFail = null
                result = attempt { provider() }
            }
            var failDetail = lastFail
            if (result != null) {
                val parts = result.split("␟").map { it.trim() }
                val cleaned = if (parts.size == lines.size) parts else {
                    // separator got mangled, fall back to per-line
                    val byLine = result.split('\n').map { it.trim() }.filter { it != "␟" && it.isNotBlank() }
                    if (byLine.size == lines.size) byLine else null
                }
                if (cleaned != null && !isEcho(lines, cleaned, src, targetLang)) {
                    onLog(L10n.t(R.string.log_translate_provider, name))
                    unhealthy?.remove(name)
                    return MtResult(cleaned, name)
                }
                failDetail = if (cleaned == null) "lines ${parts.size}/${lines.size}" else "echo"
            }
            unhealthy?.add(name)
            // include the reason so a screenshot is diagnosable
            onLog(L10n.t(R.string.log_provider_fail, name + (failDetail?.let { " · $it" } ?: "")))
        }
        return null
    }

    /**
     * detects a provider echoing the input back instead of translating.
     * true when most content lines come back identical, or the batch still reads
     * as the source language. names and numbers are ignored.
     */
    private fun isEcho(input: List<String>, output: List<String>, src: String, targetLang: String): Boolean {
        if (src == targetLang || input.size != output.size) return false
        var comparable = 0
        var echoed = 0
        for (i in input.indices) {
            val a = input[i].trim()
            val b = output[i].trim()
            if (a.count { it.isLetter() } < 4) continue // skip names/numbers
            comparable++
            if (a.equals(b, ignoreCase = true)) echoed++
        }
        if (comparable >= 2 && echoed >= (comparable + 1) / 2) return true // majority echoed
        // batch still reads as source language
        val joined = output.joinToString(" ")
        if (joined.length >= 40) {
            val detected = LangDetect.detect(joined)
            if (detected != null && detected == src && detected != targetLang) return true
        }
        return false
    }

    private fun normalizeLang(lang: String): String = when (val l = lang.lowercase()) {
        "en", "eng", "english" -> "en"
        "ja", "jp", "jpn", "japanese" -> "ja"
        "zh", "cn", "chi", "chinese" -> "zh"
        "ko", "kr", "kor", "korean" -> "ko"
        "de", "ger", "german" -> "de"
        "es", "spa", "spanish" -> "es"
        "fr", "fre", "french" -> "fr"
        "it", "ita", "italian" -> "it"
        "pt", "por", "portuguese" -> "pt"
        "ru", "rus", "russian" -> "ru"
        "ar", "ara", "arabic" -> "ar"
        "nl", "dut", "dutch" -> "nl"
        "pl", "pol", "polish" -> "pl"
        "id", "ind", "indonesian" -> "id"
        "tr", "tur", "turkish" -> "tr"
        // unknown 2-letter code passes through, defaulting to en would use the wrong source
        else -> if (l.length == 2) l else "en"
    }

    /**
     * google gtx endpoint, no key needed.
     * response: [[[translation, original, ...], ...], null, "src", ...]
     */
    private suspend fun googleGtx(text: String, src: String, target: String): String? {
        val out = StringBuilder()
        for (chunk in chunkBySep(text, 3000)) {
            val enc = java.net.URLEncoder.encode(chunk, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=$src&tl=$target&dt=t&q=$enc"
            val (body, err) = Net.getStringDetailed(url)
            if (body == null) { lastFail = err; return null }
            val segments = runCatching { JSONArray(body).getJSONArray(0) }.getOrNull()
                ?: run { lastFail = "parse"; return null }
            if (out.isNotEmpty()) out.append(SEP) // separator between chunks
            for (i in 0 until segments.length()) {
                val seg = segments.optJSONArray(i) ?: continue
                out.append(seg.optString(0))
            }
        }
        return out.toString().ifBlank { null }
    }

    /**
     * google clients5 endpoint. same engine as gtx but a separate quota pool,
     * so it survives when gtx starts 429ing mid-file.
     * response: ["translated text"]
     */
    private suspend fun googleDict(text: String, src: String, target: String): String? {
        val out = StringBuilder()
        for (chunk in chunkBySep(text, 2500)) {
            val enc = java.net.URLEncoder.encode(chunk, "UTF-8")
            val url = "https://clients5.google.com/translate_a/t" +
                "?client=dict-chrome-ex&sl=$src&tl=$target&q=$enc"
            val (body, err) = Net.getStringDetailed(url)
            if (body == null) { lastFail = err; return null }
            // usually ["text"], but some inputs yield [["text","src"]]
            val t = runCatching {
                when (val first = JSONArray(body).opt(0)) {
                    is String -> first
                    is JSONArray -> first.optString(0)
                    else -> null
                }
            }.getOrNull()
            if (t.isNullOrBlank()) { lastFail = "parse"; return null }
            if (out.isNotEmpty()) out.append(SEP) // separator between chunks
            out.append(t)
        }
        return out.toString().ifBlank { null }
    }

    private suspend fun libreTranslate(endpoint: String, text: String, src: String, target: String): String? {
        val body = JSONObject()
            .put("q", text)
            .put("source", src)
            .put("target", target)
            .put("format", "text")
            .toString()
        val (resp, err) = Net.postJsonDetailed(endpoint, body)
        if (resp == null) { lastFail = err; return null }
        return runCatching {
            JSONObject(resp).optString("translatedText").ifBlank { null }
        }.getOrNull() ?: run { lastFail = "parse"; null }
    }

    private suspend fun myMemoryBatch(lines: List<String>, src: String, target: String): List<String>? {
        // mymemory caps at ~500 bytes, go line by line
        val out = mutableListOf<String>()
        for (line in lines) {
            if (line.isBlank()) { out += line; continue }
            // long line goes in pieces so nothing gets truncated
            val sb = StringBuilder()
            for (piece in line.chunked(490)) {
                val enc = java.net.URLEncoder.encode(piece, "UTF-8")
                val (resp, err) = Net.getStringDetailed(
                    "https://api.mymemory.translated.net/get?q=$enc&langpair=$src|$target"
                )
                if (resp == null) { lastFail = err; return null }
                val t = runCatching {
                    JSONObject(resp).getJSONObject("responseData").optString("translatedText")
                }.getOrNull() ?: run { lastFail = "parse"; return null }
                if (t.contains("MYMEMORY WARNING", true)) { lastFail = "quota"; return null }
                sb.append(t)
            }
            out += sb.toString()
        }
        return out
    }
}
