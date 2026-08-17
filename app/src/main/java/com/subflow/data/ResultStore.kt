package com.subflow.data

import android.content.Context
import com.subflow.models.SubtitleResult
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

// result sets persisted to disk by history id, pruned to keep storage bounded
object ResultStore {

    private const val KEEP = 12

    private fun dir(context: Context): File =
        File(context.filesDir, "results").apply { mkdirs() }

    fun save(context: Context, id: Long, results: List<SubtitleResult>) {
        // drop any prior file first so a failed write can't leave stale content
        runCatching { File(dir(context), "$id.json").delete() }
        try {
            val arr = JSONArray()
            for (r in results) {
                arr.put(
                    JSONObject()
                        .put("fileName", r.fileName)
                        .put("content", r.content)
                        .put("sourceName", r.sourceName)
                        .put("method", r.method)
                        .put("sizeBytes", r.sizeBytes)
                        .put("episodeLabel", r.episodeLabel)
                        .put("syncWarning", r.syncWarning ?: JSONObject.NULL)
                        .put("qualityScore", r.qualityScore)
                        .put("tonePreserved", r.tonePreserved)
                        .put("untranslatedPct", r.untranslatedPct)
                        .put("rawMachineTranslation", r.rawMachineTranslation)
                )
            }
            File(dir(context), "$id.json").writeText(arr.toString(), Charsets.UTF_8)
            prune(context)
        } catch (e: Exception) {
            // persistence is best-effort
        }
    }

    fun load(context: Context, id: Long): List<SubtitleResult>? {
        return try {
            val file = File(dir(context), "$id.json")
            if (!file.exists()) return null
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SubtitleResult(
                    fileName = o.getString("fileName"),
                    content = o.getString("content"),
                    sourceName = o.getString("sourceName"),
                    method = o.getString("method"),
                    sizeBytes = o.getInt("sizeBytes"),
                    episodeLabel = o.getString("episodeLabel"),
                    syncWarning = if (o.isNull("syncWarning")) null else o.getString("syncWarning"),
                    qualityScore = o.optInt("qualityScore", 0),
                    tonePreserved = o.optBoolean("tonePreserved", false),
                    untranslatedPct = o.optInt("untranslatedPct", 0),
                    rawMachineTranslation = o.optBoolean("rawMachineTranslation", false)
                )
            }.ifEmpty { null }
        } catch (e: Throwable) {
            // includes OOM on a very large set, just re-run
            null
        }
    }

    fun delete(context: Context, id: Long) {
        runCatching { File(dir(context), "$id.json").delete() }
    }

    private fun prune(context: Context) {
        val files = dir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP).forEach { runCatching { it.delete() } }
    }
}
