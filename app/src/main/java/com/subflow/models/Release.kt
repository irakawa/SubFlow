package com.subflow.models

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.subflow.R

enum class ContentType(@StringRes val labelRes: Int) {
    ANIME(R.string.type_anime),
    SERIES(R.string.type_series),
    FILM(R.string.type_film),
    DONGHUA(R.string.type_donghua),
    ANIMATION(R.string.type_animation)
}

@Immutable
data class TorrentFile(
    val path: String,
    val size: Long
)

@Immutable
data class Release(
    val title: String = "",
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val type: ContentType = ContentType.SERIES,
    val format: String = "",          // BD Remux, WEB-DL, BluRay Encode
    val codec: String = "",           // x264, x265
    val audio: String = "",           // FLAC, DTS-HD MA, DDP5.1, TrueHD Atmos
    val releaseGroup: String? = null,
    val tags: List<String> = emptyList(),   // Dual Audio, 8-bit, HDR10, etc.
    val fileSize: Long? = null,       // taken from the torrent
    val httpUrl: String? = null,      // set when an HTTP source is available
    val hash: String? = null,         // derived via range request
    val fileName: String? = null,     // original MKV name, for output matching
    val torrentFiles: List<TorrentFile> = emptyList(),
    val originalLanguage: String? = null, // search by original language name
    val targetLang: String = "tr",        // target subtitle language (ISO 639-1)
    /** alternate titles (romaji/english/synonyms), sites name files by any of them. */
    val altTitles: List<String> = emptyList()
) {
    val targetLangName: String get() = LangCatalog.englishName(targetLang)

    /** builds query variants so search covers several angles.
     *  Locale.ROOT keeps digits ASCII, an Arabic locale would mangle %d. */
    fun queryVariants(): List<String> {
        val root = java.util.Locale.ROOT
        val se = buildString {
            if (season != null) append(" S%02d".format(root, season))
            if (episode != null) append(if (season != null) "E%02d".format(root, episode) else " %02d".format(root, episode))
        }
        val variants = mutableListOf<String>()
        variants += (title + se).trim()
        variants += title.trim()
        if (season != null && episode != null) {
            variants += "$title ${season}x%02d".format(root, episode)
            variants += "$title Season $season Episode $episode"
        }
        if (episode != null) variants += "$title - %02d".format(root, episode)
        if (year != null) variants += "$title $year"
        releaseGroup?.let { variants += "$it $title" }
        originalLanguage?.let { variants += it }
        // alternate spellings: dots, stripped apostrophes, lowercase
        variants += title.replace(' ', '.')
        if (title.contains('\'')) variants += title.replace("'", "")
        variants += title.lowercase(root)
        return variants.distinct().filter { it.isNotBlank() }
    }

    fun displayName(): String = buildString {
        val root = java.util.Locale.ROOT
        append(title)
        if (season != null) append(" S%02d".format(root, season))
        if (episode != null) append("E%02d".format(root, episode))
        if (year != null && season == null) append(" ($year)")
    }
}
