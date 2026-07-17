package com.subflow.models

// languages the free translation providers can handle
data class TargetLang(
    val code: String,        // ISO 639-1, for translation APIs
    val threeLetter: String, // for ffmpeg / BSPlayer
    val englishName: String, // for site scraping filters
    val nativeName: String   // shown in the UI
)

object LangCatalog {

    val supported: List<TargetLang> = listOf(
        TargetLang("tr", "tur", "Turkish", "Türkçe"),
        TargetLang("en", "eng", "English", "English"),
        TargetLang("de", "ger", "German", "Deutsch"),
        TargetLang("es", "spa", "Spanish", "Español"),
        TargetLang("fr", "fre", "French", "Français"),
        TargetLang("it", "ita", "Italian", "Italiano"),
        TargetLang("pt", "por", "Portuguese", "Português"),
        TargetLang("ru", "rus", "Russian", "Русский"),
        TargetLang("ar", "ara", "Arabic", "العربية"),
        TargetLang("id", "ind", "Indonesian", "Bahasa Indonesia"),
        TargetLang("pl", "pol", "Polish", "Polski"),
        TargetLang("nl", "dut", "Dutch", "Nederlands")
    )

    fun byCode(code: String): TargetLang =
        supported.firstOrNull { it.code == code } ?: supported.first()

    fun englishName(code: String): String = byCode(code).englishName

    fun threeLetter(code: String): String = byCode(code).threeLetter

    fun nativeName(code: String): String = byCode(code).nativeName
}
