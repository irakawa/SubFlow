package com.subflow.data

import android.content.Context
import android.net.Uri
import com.subflow.ui.theme.Palettes
import com.subflow.ui.theme.SubFlowColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backs up settings and followed shows to a local json file.
 *
 * The API keys are deliberately not in the export. Three options were on the table:
 * encrypt them, turn auto-backup off entirely, or leave them out. Encryption is
 * theatre here — there is no user passphrase to derive a key from, and the app ships
 * as an unobfuscated APK, so any embedded key travels in the same artifact it is
 * supposed to protect. Turning auto-backup off would throw away favourites and settings
 * to solve a problem that only concerns three strings, and would not touch this file at
 * all, which is the copy a user can accidentally share. So the secret is the only thing
 * removed, on both paths: here, and in res/xml/backup_rules.xml +
 * res/xml/data_extraction_rules.xml for Android's own auto-backup.
 *
 * [import] still reads the key fields so a backup written before this change restores
 * as it always did. Nothing writes them any more.
 */
object BackupManager {

    /**
     * Settings written into a backup file, in write order.
     *
     * Pure and Context-free so the "no secrets in a backup" rule is checkable without a
     * device (BackupSecretsTest). If a secret is ever added to a backup again, it has to
     * be added here first, and the test fails.
     */
    internal fun settingsFields(
        theme: String,
        targetLang: String,
        autoSave: Boolean,
        haptics: Boolean
    ): Map<String, Any> = linkedMapOf(
        "theme" to theme,
        "targetLang" to targetLang,
        "autoSave" to autoSave,
        "haptics" to haptics
    )

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
            for ((name, value) in settingsFields(
                theme = SubFlowColors.palette.id,
                targetLang = AppSettings.defaultTargetLang,
                autoSave = AppSettings.autoSave,
                haptics = AppSettings.haptics
            )) {
                root.put(name, value)
            }
            root.put("favorites", favorites)
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
            // read-only backward compatibility: backups written before the keys were
            // dropped from the export still restore. Nothing writes these fields now.
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
