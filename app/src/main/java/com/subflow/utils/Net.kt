package com.subflow.utils

import com.subflow.optimization.DeviceProfiler
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

// shared http client. timeouts adapt to device tier, per-host throttle of 500ms to avoid ip bans
object Net {

    // plain Chrome-on-Android UA, custom tokens trip Cloudflare's bot checks
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(DeviceProfiler.connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(DeviceProfiler.readTimeoutSec, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            // some sites (Addic7ed) set a PHPSESSID mid-redirect and won't serve the page without it
            .cookieJar(InMemoryCookieJar)
            .build()
    }

    // cookies merged by name per host so a session cookie survives responses that don't resend it. not persisted.
    private object InMemoryCookieJar : okhttp3.CookieJar {
        private val store = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, okhttp3.Cookie>>()

        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            val host = store.getOrPut(url.host) { java.util.concurrent.ConcurrentHashMap() }
            cookies.forEach { host[it.name] = it }
        }

        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
            val now = System.currentTimeMillis()
            val host = store[url.host] ?: return emptyList()
            host.entries.removeAll { it.value.expiresAt < now }
            return host.values.filter { it.matches(url) }
        }
    }

    private val throttleMutex = Mutex()
    private val lastRequestAt = HashMap<String, Long>()
    private const val MIN_INTERVAL_MS = 500L

    private suspend fun throttle(url: String) {
        val host = try {
            java.net.URI(url).host ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
        var waitMs = 0L
        throttleMutex.withLock {
            val now = System.currentTimeMillis()
            val last = lastRequestAt[host] ?: 0L
            val next = maxOf(now, last + MIN_INTERVAL_MS)
            waitMs = next - now
            lastRequestAt[host] = next
        }
        if (waitMs > 0) delay(waitMs)
    }

    // rate-limit hook for callers that build their own Request
    suspend fun awaitRateLimit(url: String) = throttle(url)

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): Response {
        throttle(url)
        return withContext(DeviceProfiler.ioDispatcher) {
            val req = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(req).execute()
        }
    }

    suspend fun getString(url: String, headers: Map<String, String> = emptyMap()): String? {
        return try {
            get(url, headers).use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (e: CancellationException) {
            throw e // don't swallow cancellation
        } catch (e: Exception) {
            null
        }
    }

    // like getString but returns why it failed (e.g. "HTTP 429") so the log isn't just a silent null
    suspend fun getStringDetailed(url: String, headers: Map<String, String> = emptyMap()): Pair<String?, String?> {
        return try {
            get(url, headers).use { resp ->
                if (resp.isSuccessful) resp.body?.string() to null
                else null to "HTTP ${resp.code}"
            }
        } catch (e: CancellationException) {
            throw e // don't swallow cancellation
        } catch (e: Exception) {
            null to (e.javaClass.simpleName)
        }
    }

    // POST variant of getStringDetailed
    suspend fun postJsonDetailed(url: String, json: String, headers: Map<String, String> = emptyMap()): Pair<String?, String?> {
        throttle(url)
        return try {
            withContext(DeviceProfiler.ioDispatcher) {
                val body: RequestBody = json.toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .post(body)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() to null
                    else null to "HTTP ${resp.code}"
                }
            }
        } catch (e: CancellationException) {
            throw e // don't swallow cancellation
        } catch (e: Exception) {
            null to (e.javaClass.simpleName)
        }
    }

    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? {
        return try {
            get(url, headers).use { resp ->
                if (!resp.isSuccessful) null else resp.body?.bytes()
            }
        } catch (e: CancellationException) {
            throw e // don't swallow cancellation
        } catch (e: Exception) {
            null
        }
    }

    // xml post for legacy XML-RPC endpoints like OpenSubtitles.org
    suspend fun postXml(url: String, xml: String, headers: Map<String, String> = emptyMap()): String? {
        throttle(url)
        return try {
            withContext(DeviceProfiler.ioDispatcher) {
                val body: RequestBody = xml.toRequestBody("text/xml; charset=utf-8".toMediaType())
                val req = Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .post(body)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                }
            }
        } catch (e: CancellationException) {
            throw e // don't swallow cancellation
        } catch (e: Exception) {
            null
        }
    }

    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String? {
        throttle(url)
        return try {
            withContext(DeviceProfiler.ioDispatcher) {
                val body: RequestBody = json.toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .post(body)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                }
            }
        } catch (e: CancellationException) {
            throw e // don't swallow cancellation
        } catch (e: Exception) {
            null
        }
    }

    // returns raw bytes for binary downloads instead of decoding to a String
    suspend fun postFormBytes(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): ByteArray? {
        throttle(url)
        return try {
            withContext(DeviceProfiler.ioDispatcher) {
                val fb = okhttp3.FormBody.Builder()
                form.forEach { (k, v) -> fb.add(k, v) }
                val req = Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .post(fb.build())
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.bytes()
                }
            }
        } catch (e: CancellationException) {
            throw e // don't swallow cancellation
        } catch (e: Exception) {
            null
        }
    }

    suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): String? {
        throttle(url)
        return try {
            withContext(DeviceProfiler.ioDispatcher) {
                val fb = okhttp3.FormBody.Builder()
                form.forEach { (k, v) -> fb.add(k, v) }
                val req = Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .post(fb.build())
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                }
            }
        } catch (e: CancellationException) {
            throw e // don't swallow cancellation
        } catch (e: Exception) {
            null
        }
    }
}
