package com.subflow.utils

import org.json.JSONObject

// checks the github releases api for a newer build. keyless, silent-fail when offline.
object UpdateChecker {

    private const val REPO = "irakawa/SubFlow"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    data class Update(val version: String, val apkUrl: String, val notes: String)

    // returns an Update when the latest release is newer than [current] (e.g. "2.35.0"), else null
    suspend fun check(current: String): Update? {
        val body = Net.getString(LATEST, mapOf("Accept" to "application/vnd.github+json")) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null

        val tag = json.optString("tag_name").removePrefix("v").trim()
        if (tag.isBlank() || !isNewer(tag, current)) return null

        // pick the .apk attached to the release
        val assets = json.optJSONArray("assets") ?: return null
        var apk: String? = null
        for (i in 0 until assets.length()) {
            val name = assets.optJSONObject(i)?.optString("name").orEmpty()
            if (name.endsWith(".apk", ignoreCase = true)) {
                apk = assets.optJSONObject(i)?.optString("browser_download_url")
                break
            }
        }
        val url = apk?.takeIf { it.isNotBlank() } ?: return null
        return Update(tag, url, json.optString("body").trim().take(400))
    }

    // numeric dotted-version compare, e.g. 2.36.0 > 2.35.0
    private fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, c.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
