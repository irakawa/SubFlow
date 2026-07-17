package com.subflow.data

import android.content.Context
import android.net.Uri
import com.subflow.ui.theme.Palettes
import com.subflow.ui.theme.SubFlowColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// backs up settings and followed shows to a local json file
object BackupManager {

    suspend fun export(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val favorites = JSONArray()
            SubFlowDb.get(context).favoriteDao().snapshot().forEach { f ->
                favorites.put(
                    JSONObject()
                        .put("title", f.title).put("type", f.type).put("season", f.season)
                        .put("lastEpisode", f.lastEpisode).put("targetLang", f.targetLang)
                        .put("format", f.format).put("codec", f.codec).put("audio", f.audio)
                )
            }
            val root = JSONObject()
                .put("theme", SubFlowColors.palette.id)
                .put("targetLang", AppSettings.defaultTargetLang)
                .put("autoSave", AppSettings.autoSave)
                .put("haptics", AppSettings.haptics)
                .put("apiSubdl", ApiKeys.subdl)
                .put("apiOpenSubtitles", ApiKeys.openSubtitles)
                .put("apiSubsource", ApiKeys.subsource)
                .put("favorites", favorites)
            context.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(root.toString(2).toByteArray(Charsets.UTF_8))
            } ?: return@withContext false
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun import(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return@withContext false
            val root = JSONObject(text)

            // parse and validate everything before touching state, so a bad file
            // doesn't leave settings half-applied
            val favorites = root.optJSONArray("favorites")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    FavoriteEntry(
                        title = o.getString("title"), type = o.getString("type"),
                        season = o.getInt("season"), lastEpisode = o.getInt("lastEpisode"),
                        targetLang = o.getString("targetLang"), format = o.optString("format"),
                        codec = o.optString("codec"), audio = o.optString("audio"),
                        timestamp = System.currentTimeMillis()
                    )
                }
            } ?: emptyList()

            val theme = root.optString("theme").takeIf { it.isNotBlank() }
            val targetLang = if (root.has("targetLang")) root.optString("targetLang") else null
            val autoSave = if (root.has("autoSave")) root.optBoolean("autoSave") else null
            val haptics = if (root.has("haptics")) root.optBoolean("haptics") else null
            val apiSubdl = if (root.has("apiSubdl")) root.optString("apiSubdl") else null
            val apiOpenSubtitles = if (root.has("apiOpenSubtitles")) root.optString("apiOpenSubtitles") else null
            val apiSubsource = if (root.has("apiSubsource")) root.optString("apiSubsource") else null

            // now apply
            theme?.let { SubFlowColors.apply(context, Palettes.byId(it)) }
            targetLang?.let { AppSettings.defaultTargetLang = it }
            autoSave?.let { AppSettings.autoSave = it }
            haptics?.let { AppSettings.haptics = it }
            apiSubdl?.let { ApiKeys.subdl = it }
            apiOpenSubtitles?.let { ApiKeys.openSubtitles = it }
            apiSubsource?.let { ApiKeys.subsource = it }

            val dao = SubFlowDb.get(context).favoriteDao()
            favorites.forEach { dao.upsert(it) }
            true
        } catch (e: Exception) {
            false
        }
    }
}
