package com.subflow.data

import android.content.Context
import com.subflow.models.ContentType
import com.subflow.ui.InputForm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

// holds searches made while offline, replayed on reconnect. torrent file lists
// aren't serialized, they can't survive the round-trip.
object SearchQueue {

    private const val PREFS = "subflow_queue"
    private const val KEY = "pending"

    private lateinit var prefs: android.content.SharedPreferences
    private val _queue = MutableStateFlow<List<InputForm>>(emptyList())
    val queue: StateFlow<List<InputForm>> = _queue.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _queue.value = load()
    }

    fun enqueue(form: InputForm) {
        _queue.value = _queue.value + form
        persist()
    }

    fun all(): List<InputForm> = _queue.value

    fun clear() {
        _queue.value = emptyList()
        persist()
    }

    val size: Int get() = _queue.value.size

    private fun persist() {
        val arr = JSONArray()
        _queue.value.forEach { f ->
            arr.put(
                JSONObject()
                    .put("title", f.title).put("season", f.season).put("episode", f.episode)
                    .put("type", f.type.name).put("format", f.format).put("codec", f.codec)
                    .put("audio", f.audio).put("extraTags", f.extraTags).put("httpUrl", f.httpUrl)
                    .put("seasonMode", f.seasonMode).put("episodeEnd", f.episodeEnd)
                    .put("fileName", f.fileName ?: JSONObject.NULL)
                    .put("fileSize", f.fileSize ?: JSONObject.NULL)
                    .put("targetLang", f.targetLang)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun load(): List<InputForm> = try {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            InputForm(
                title = o.optString("title"), season = o.optString("season"),
                episode = o.optString("episode"),
                type = runCatching { ContentType.valueOf(o.optString("type")) }.getOrDefault(ContentType.SERIES),
                format = o.optString("format"), codec = o.optString("codec"), audio = o.optString("audio"),
                extraTags = o.optString("extraTags"), httpUrl = o.optString("httpUrl"),
                seasonMode = o.optBoolean("seasonMode"), episodeEnd = o.optString("episodeEnd"),
                fileName = if (o.isNull("fileName")) null else o.optString("fileName"),
                fileSize = if (o.isNull("fileSize")) null else o.optLong("fileSize"),
                targetLang = o.optString("targetLang", "tr")
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
