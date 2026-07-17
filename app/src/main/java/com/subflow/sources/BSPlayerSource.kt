package com.subflow.sources

import com.subflow.R
import com.subflow.models.LangCatalog
import com.subflow.models.Release
import com.subflow.models.SubtitleCandidate
import com.subflow.utils.L10n
import com.subflow.utils.Net
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Old but broad archive. SOAP based, only works when we have an OpenSubtitles hash.
 */
class BSPlayerSource : SubtitleSource {
    override val name = "BSPlayer"
    override val tier = 3

    private val endpoints = listOf(
        "http://s1.api.bsplayer-subtitles.com/v1.php",
        "http://s2.api.bsplayer-subtitles.com/v1.php"
    )

    override suspend fun search(release: Release, log: suspend (String) -> Unit): List<SubtitleCandidate> {
        val hash = release.hash ?: run {
            log(L10n.t(R.string.log_hash_required, "BSPlayer"))
            return emptyList()
        }
        val size = release.fileSize ?: 0L
        val target3 = LangCatalog.threeLetter(release.targetLang)
        for (ep in endpoints) {
            val handle = soapCall(ep, "logIn", "<username></username><password></password><AppID>BSPlayer v2.72</AppID>")
                ?.let { extract(it, "result") } ?: continue
            val body =
                "<handle>$handle</handle><movieHash>$hash</movieHash><movieSize>$size</movieSize><languageId>$target3,eng</languageId><imdbId>*</imdbId>"
            val resp = soapCall(ep, "searchSubtitles", body) ?: continue
            val doc = Jsoup.parse(resp, "", Parser.xmlParser())
            val out = mutableListOf<SubtitleCandidate>()
            for (item in doc.select("item")) {
                val url = item.selectFirst("subDownloadLink")?.text() ?: continue
                val lang = item.selectFirst("subLang")?.text()?.lowercase() ?: ""
                val nameEl = item.selectFirst("subName")?.text() ?: "BSPlayer sub"
                val isTarget = lang.startsWith(target3) || lang.startsWith(release.targetLang)
                out += SubtitleCandidate(
                    sourceName = name, title = nameEl,
                    language = if (isTarget) release.targetLang else "en",
                    downloadUrl = url,
                    score = matchScore(release, nameEl) + if (isTarget) 8 else 0
                )
            }
            if (out.isNotEmpty()) return out.sortedByDescending { it.score }
        }
        return emptyList()
    }

    private suspend fun soapCall(endpoint: String, action: String, innerXml: String): String? {
        val envelope = """<?xml version="1.0" encoding="UTF-8"?>
<SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/"
 xmlns:ns1="$endpoint">
<SOAP-ENV:Body>
<ns1:$action>$innerXml</ns1:$action>
</SOAP-ENV:Body>
</SOAP-ENV:Envelope>"""
        return try {
            val mediaType = "text/xml; charset=utf-8".toMediaType()
            val body = envelope.toRequestBody(mediaType)
            val req = okhttp3.Request.Builder().url(endpoint)
                .header("User-Agent", "BSPlayer/2.x (1022.12360)")
                .header("SOAPAction", "\"$endpoint#$action\"")
                .post(body).build()
            Net.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // bypasses Net.kt so it needs its own cancellation guard
        } catch (e: Exception) {
            null
        }
    }

    private fun extract(xml: String, tag: String): String? =
        Regex("<$tag>([^<]+)</$tag>").find(xml)?.groupValues?.get(1)
}
