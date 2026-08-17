package com.subflow.pipeline

/**
 * cleans up raw machine translation with build-time knowledge: slang dictionary,
 * running glossary, grammar fixes for common TR MT errors, and a nonsense detector.
 */
class PostProcessor(private val targetLang: String = "tr") {

    /** running glossary, keeps a term translated the same way across the episode */
    private val glossary = LinkedHashMap<String, String>()

    /** proper names, never translated */
    private val properNames = mutableSetOf<String>()

    /**
     * [toneHardened] is the honest answer to "did we restore register on this batch":
     * true only when the sanitize repair actually ran AND changed a line. It backs the
     * uncensored badge, so it must never be assumed — an unmeasured claim is worse than
     * no claim.
     */
    data class BatchResult(
        val lines: List<String>,
        val retrySuggested: Boolean,
        val toneHardened: Boolean = false
    )

    fun processBatch(sourceLines: List<String>, translatedLines: List<String>): BatchResult {
        var retry = false
        var hardened = false
        val out = ArrayList<String>(translatedLines.size)

        for (i in translatedLines.indices) {
            val source = sourceLines.getOrElse(i) { "" }
            var line = translatedLines[i]

            line = fixGrammar(line)
            if (targetLang == "tr") line = applyGlossary(source, line)
            line = restoreProperNames(source, line)

            // TR only: pull sanitized MT back to the source's register. only a line the
            // repair actually changed counts as hardened; detecting sanitization we
            // couldn't repair is not tone preservation.
            if (targetLang == "tr" && SlangDictionary.looksSanitized(source, line)) {
                val restored = SlangDictionary.hardenTranslation(source, line)
                if (restored != line) hardened = true
                line = restored
            }

            // flag nonsense
            if (isNonsense(source, line)) {
                retry = true
            }

            out += line
        }
        return BatchResult(out, retry, hardened)
    }

    private val grammarFixes: List<Pair<Regex, String>> = listOf(
        // spacing cleanup
        Regex("\\s{2,}") to " ",
        Regex("\\s+([,.!?;:])") to "$1",
        Regex("([,.!?;:])(\\p{L})") to "$1 $2",
        // word duplications
        Regex("\\b(\\p{L}+) \\1\\b", RegexOption.IGNORE_CASE) to "$1",
        // leftover English quotes
        Regex("''") to "\"",
        // "bir bir" repetition
        Regex("\\bbir bir\\b", RegexOption.IGNORE_CASE) to "bir"
        // dropped the "split attached question particle" rule. it corrupted real words
        // ending the same way ("resmi", "yardımı"), and MT already spaces these right.
    )

    /** TR verb-conjugation fixes */
    private val verbFixes: Map<String, String> = mapOf(
        "yapmak zorundayım gitmek" to "gitmek zorundayım",
        "istiyorum gitmek" to "gitmek istiyorum",
        "sahip değilim" to "bende yok",
        "sahip misin" to "sende var mı",
        "sahip olmak istiyorum" to "istiyorum",
        "alabilir miyim bir" to "bir şey alabilir miyim:",
        "yapabilirim yardım" to "yardım edebilirim",
        "değil mi öyle" to "öyle değil mi"
    )

    fun fixGrammar(line: String): String {
        var out = line
        for ((regex, repl) in grammarFixes) out = out.replace(regex, repl)
        if (targetLang == "tr") {
            for ((bad, good) in verbFixes) out = out.replace(bad, good, ignoreCase = true)
        }
        // capitalize first letter. TR needs the locale so 'i' becomes 'İ' not 'I'
        out = out.trim()
        if (out.isNotEmpty() && out[0].isLowerCase() && !out.startsWith("<")) {
            out = if (targetLang == "tr") {
                out.replaceFirstChar { it.titlecase(java.util.Locale("tr")) }
            } else {
                out.replaceFirstChar { it.titlecase() }
            }
        }
        return out
    }

    /** map source jargon to a consistent TR term */
    private fun applyGlossary(source: String, line: String): String {
        var out = line
        val lowerSource = source.lowercase()
        val allDicts = SlangDictionary.jpToTr + SlangDictionary.cnToTr
        for ((term, tr) in allDicts) {
            if (lowerSource.contains(term)) {
                val chosen = glossary.getOrPut(term) { tr }
                if (!out.lowercase().contains(chosen.lowercase())) {
                    // MT used a different word, force the glossary term
                    out = out.replace(Regex("\\b${Regex.escape(term)}\\b", RegexOption.IGNORE_CASE), chosen)
                }
            }
        }
        return out
    }

    /**
     * keep source proper names in the output, including names first seen in an earlier
     * cue: a character doesn't stop being a name halfway through the episode just
     * because this line doesn't repeat it (rule 6, terminology consistency).
     */
    private fun restoreProperNames(source: String, line: String): String {
        Regex("\\b[A-Z][a-z]{2,}\\b").findAll(source)
            .map { it.value }
            .filterNot { it in commonEnglishWords }
            .forEach { properNames += it }

        var out = line
        for (name in properNames) {
            // MT lowercased the name, restore it
            val lowered = name.lowercase()
            if (out.contains(lowered) && !out.contains(name)) {
                out = out.replace(wholeWord(lowered), name)
            }
        }
        return out
    }

    /**
     * whole-word matcher for [word]. `\b` is ASCII-only here, so it would happily fire
     * inside "kanıyor" (it reads "ı" as a non-word char) and rewrite an ordinary Turkish
     * word into a name. Unicode letter lookarounds don't.
     */
    private fun wholeWord(word: String): Regex =
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(word)}(?![\\p{L}\\p{N}])")

    private val commonEnglishWords = setOf(
        "The", "This", "That", "What", "When", "Where", "Why", "How",
        "And", "But", "You", "Are", "Was", "Were", "Have", "Has", "Not",
        "All", "Can", "Will", "Just", "Now", "Then", "There", "Here"
    )

    /** true if the translation looks like nonsense, caller can retry another source */
    fun isNonsense(source: String, target: String): Boolean {
        if (source.isBlank()) return false
        if (target.isBlank()) return true
        // came back identical to source, nothing was translated
        if (source.length > 12 && target.equals(source, ignoreCase = true)) return true
        // long TR line with zero Turkish chars is suspicious
        if (targetLang == "tr") {
            val turkishChars = target.count { it in "çğıöşüÇĞİÖŞÜ" }
            val letters = target.count { it.isLetter() }
            if (letters > 30 && turkishChars == 0) {
                // lots of English words means it failed
                val enWords = target.split(' ').count { w ->
                    w.length > 3 && w.all { it.code < 128 } && w.lowercase() in enCommon
                }
                if (enWords >= 3) return true
            }
        }
        // 5x expansion or contraction, likely corruption
        if (source.length > 20 && (target.length > source.length * 5 || target.length * 5 < source.length)) return true
        return false
    }

    private val enCommon = setOf(
        "the", "this", "that", "with", "have", "will", "your", "what",
        "about", "would", "there", "their", "want", "know", "think", "going"
    )
}
