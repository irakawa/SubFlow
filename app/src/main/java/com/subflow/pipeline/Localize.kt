package com.subflow.pipeline

import java.util.Locale

/**
 * Turkish term policy. Free machine translators render "God" as "Allah". We normalize
 * to the neutral "Tanrı" as a final pass over every translated line.
 *
 * Plain string scanning, not regex, on purpose. An earlier regex used the (?U) flag,
 * which compiles on the desktop JVM but throws at class-init on Android's regex engine
 * (ExceptionInInitializerError) and crashed the whole translation step. Manual
 * tokenising has no static regex to compile, so it can't fail that way.
 *
 * Idioms that merely contain the string ("inşallah", "maşallah", "vallahi") are left
 * alone: a word is only converted when "Allah" starts it and its suffix is a known
 * case suffix.
 */
object Localize {

    private val tr = Locale("tr")

    // suffix after consonant-stem "Allah" mapped to the "Tanrı" form
    private val suffixMap: Map<String, String> = mapOf(
        "" to "",              // Allah        -> Tanrı
        "a" to "ya",           // Allah'a      -> Tanrıya       (dative)
        "ı" to "yı",           // Allah'ı      -> Tanrıyı       (accusative)
        "ın" to "nın",         // Allah'ın     -> Tanrının      (genitive)
        "tan" to "dan",        // Allah'tan    -> Tanrıdan      (ablative)
        "dan" to "dan",
        "ta" to "da",          // Allah'ta     -> Tanrıda       (locative)
        "da" to "da",
        "la" to "yla",         // Allah'la     -> Tanrıyla      (instrumental)
        "ım" to "m",           // Allah'ım     -> Tanrım        (my God)
        "ımız" to "mız",       // Allah'ımız   -> Tanrımız      (our God)
        "sız" to "sız",        // Allahsız     -> Tanrısız
        "tı" to "ydı",         // Allah'tı     -> Tanrıydı      (was)
        "dı" to "ydı",
        "mış" to "ymış",       // Allah'mış    -> Tanrıymış
        "tır" to "dır",        // Allahtır     -> Tanrıdır
        "dır" to "dır",
        "taydı" to "daydı",
        "daydı" to "daydı",
        "taymış" to "daymış",
        "daymış" to "daymış"
    )

    fun godToTanri(line: String): String {
        if (line.length < 5 || !containsAllah(line)) return line
        val sb = StringBuilder(line.length + 8)
        var i = 0
        while (i < line.length) {
            if (startsAllahWord(line, i)) {
                val end = wordEnd(line, i)
                sb.append(convertToken(line.substring(i, end)))
                i = end
            } else {
                sb.append(line[i]); i++
            }
        }
        return sb.toString()
    }

    private fun containsAllah(s: String): Boolean {
        var i = 0
        while (i + 5 <= s.length) {
            if (s.regionMatches(i, "allah", 0, 5, ignoreCase = true)) return true
            i++
        }
        return false
    }

    /** true when "Allah" starts a word at [i]. */
    private fun startsAllahWord(s: String, i: Int): Boolean {
        if (i + 5 > s.length) return false
        if (!s.regionMatches(i, "allah", 0, 5, ignoreCase = true)) return false
        return i == 0 || !s[i - 1].isLetterOrDigit()
    }

    /** index past the full "Allah" token (word, optional apostrophe, suffix). */
    private fun wordEnd(s: String, start: Int): Int {
        var j = start + 5
        if (j < s.length && (s[j] == '\'' || s[j] == '’')) j++
        while (j < s.length && s[j].isLetter()) j++
        return j
    }

    private fun convertToken(token: String): String {
        var k = 5
        if (k < token.length && (token[k] == '\'' || token[k] == '’')) k++
        val suffix = token.substring(k).lowercase(tr)
        val mapped = suffixMap[suffix] ?: return token // unknown suffix, leave it (idiom or derivation)
        return applyCase(token, "tanrı$mapped")
    }

    /** re-applies the original's casing to [lower]. */
    private fun applyCase(original: String, lower: String): String {
        val letters = original.filter { it.isLetter() }
        return when {
            letters.isNotEmpty() && letters.all { it.isUpperCase() } -> lower.uppercase(tr)
            original.firstOrNull { it.isLetter() }?.isUpperCase() == true ->
                lower.replaceFirstChar { it.titlecase(tr) }
            else -> lower
        }
    }
}
