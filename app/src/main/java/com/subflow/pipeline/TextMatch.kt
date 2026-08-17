package com.subflow.pipeline

/**
 * Word matching for Turkish text.
 *
 * `\b` is ASCII-only on the JVM unless UNICODE_CHARACTER_CLASS is set, so it reads "ı",
 * "ş", "ğ" as non-word characters and fires in the middle of a Turkish word: `\bkan\b`
 * matches inside "kanıyor". Plain `contains` is worse still — "lan" is inside yalan,
 * plan, kullanılan, varsayılan, and "göt" opens the entire "götür-" verb. These helpers
 * use unicode letter/digit lookarounds instead.
 */
internal object TextMatch {

    /** [word] standing alone: no letter or digit on either side. */
    fun wholeWord(word: String): Regex =
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(word)}(?![\\p{L}\\p{N}])")

    /** [stem] opening a word, with any Turkish suffix free to follow ("bok" -> "boktan"). */
    fun wordStart(stem: String): Regex =
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(stem)}")
}
