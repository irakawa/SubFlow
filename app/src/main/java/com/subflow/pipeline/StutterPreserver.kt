package com.subflow.pipeline

import java.util.Locale

/**
 * keeps a character's stutter alive through translation (SUBFLOW_LANGUAGE_RULES 2.1).
 *
 * a stutter in the source ("W-what?!", "B-baka!") must survive, but the repeated
 * letter has to match the *Turkish* word that comes out, not the source letter.
 * "W-what" translated to "ne" becomes "N-ne", never "W-ne". So we detect the
 * stutter on the source, then regenerate the prefix from the translated line's
 * first word.
 *
 * format is [Capital]-[word]: the sentence-initial capital moves onto the prefix
 * and the word itself stays in its natural case ("B-bana", "Ç-çünkü"), except a
 * proper name keeps its capital ("A-Akaishi-san").
 */
object StutterPreserver {

    private val tr = Locale("tr")

    // a single letter, a dash, then a letter: the classic stutter opener.
    private val sourceStutter = Regex("""^\s*\p{L}-\p{L}""")

    // the source word that is being stuttered, e.g. "Akaishi-san" in "A-Akaishi-san".
    private val sourceWord = Regex("""^\s*\p{L}-([\p{L}][\p{L}'’-]*)""")

    // a stutter prefix already on the translated line (possibly the wrong letter).
    private val leadingPrefix = Regex("""^\s*\p{L}-""")

    // the first real word of a line.
    private val firstWord = Regex("""^([\p{L}][\p{L}'’-]*)""")

    fun apply(source: String, translated: String): String {
        if (!sourceStutter.containsMatchIn(source)) return translated

        // strip any stutter prefix the MT carried over, then read the first word.
        val leading = translated.takeWhile { it == ' ' }
        val body = translated.drop(leading.length)
        val stripped = body.replaceFirst(leadingPrefix, "")
        val match = firstWord.find(stripped) ?: return translated
        val word = match.groupValues[1]

        val prefix = word.first().toString().uppercase(tr)
        val rest = stripped.substring(word.length)
        val shownWord = if (isProperName(source, word)) word else lowerFirst(word)
        return "$leading$prefix-$shownWord$rest"
    }

    /** a translated word keeps its capital when it's a name, not a common word. */
    private fun isProperName(source: String, word: String): Boolean {
        if (AddresseeAnalyzer.formalityOf(word) != null) return true // carries a honorific
        // the name passed through untranslated: source's stuttered word equals this one
        val original = sourceWord.find(source)?.groupValues?.get(1) ?: return false
        return original.equals(word, ignoreCase = true) && word.first().isUpperCase()
    }

    private fun lowerFirst(word: String): String =
        word.replaceFirstChar { it.lowercase(tr) }
}
