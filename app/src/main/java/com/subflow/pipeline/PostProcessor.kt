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

            // last, so the grammar pass also tidies what the repair just wrote. running it
            // first left every repaired cue starting in lowercase, since the replacement
            // text lands after the capital has already been applied.
            line = fixGrammar(line)

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
     *
     * A name is only remembered when its lowercase form isn't an ordinary word in the
     * target language. Plenty of names are also everyday Turkish words — Ben, Can, Ada,
     * Kaya, Deniz — and remembering one turns every later "ben" (I) into "Ben". The
     * trade is deliberate: skipping the restoration costs one capital on one line,
     * while the collision costs every line that uses the ordinary word, and the
     * ordinary word occurs far more often than the character does.
     */
    private fun restoreProperNames(source: String, line: String): String {
        Regex("\\b[A-Z][a-z]{2,}\\b").findAll(source)
            .map { it.value }
            .filterNot { it in commonEnglishWords }
            .filterNot { targetLang == "tr" && it.lowercase() in commonTurkishWords }
            .forEach { properNames += it }

        var out = line
        for (name in properNames) {
            // MT lowercased the name, restore it
            val lowered = name.lowercase()
            if (out.contains(lowered) && !out.contains(name)) {
                out = out.replace(TextMatch.wholeWord(lowered), name)
            }
        }
        return out
    }

    private val commonEnglishWords = setOf(
        "The", "This", "That", "What", "When", "Where", "Why", "How",
        "And", "But", "You", "Are", "Was", "Were", "Have", "Has", "Not",
        "All", "Can", "Will", "Just", "Now", "Then", "There", "Here"
    )

    /**
     * Everyday Turkish words a source-language capital must never impose itself on.
     *
     * Only ASCII spellings are listed, and only three letters or more, because that is
     * exactly what the name regex above can produce: `[A-Z][a-z]{2,}` cannot match ı, ş,
     * ğ, ö, ü or ç, so a Turkish word containing one can never collide. Entries are
     * high-frequency words plus the given names that double as them, which is where the
     * damage actually comes from.
     */
    private val commonTurkishWords = setOf(
        // pronouns and their cases — the highest-frequency collisions
        "ben", "beni", "bana", "benim", "bende", "benden",
        "sen", "seni", "sana", "senin", "sende", "senden",
        "biz", "bizi", "bize", "bizim", "siz", "sizi", "size", "sizin",
        "onu", "ona", "onun", "onda", "ondan", "onlar", "bunu", "buna", "bunun", "bunlar",
        "kim", "kime", "kimi", "kimin", "kendi",
        // function words
        "ama", "ile", "ise", "gibi", "kadar", "daha", "hem", "her", "hep", "tam",
        "sonra", "bile", "yine", "hala", "asla", "belki", "zaten", "ancak", "veya",
        "evet", "tabii", "yani", "peki", "haydi", "hadi",
        // very common nouns and verb stems that are also given names
        "ada", "adam", "aile", "akil", "alan", "ana", "anne", "araba", "arka", "asker",
        "baba", "bahar", "bal", "bir", "biri", "bora", "bura", "burada",
        "can", "cam", "deniz", "derin", "ders", "dil", "dost", "dur", "duman",
        "ege", "efe", "ela", "elma", "emir", "esen", "eve",
        "gece", "gel", "git", "guzel", "hava", "hayat", "kal", "kan", "kara", "kaya",
        "kol", "kul", "kum", "lale", "mal", "mavi", "mert", "nar", "nur",
        "oda", "okul", "olan", "onur", "orada", "ozan", "para", "ruh",
        "sabah", "savas", "sel", "ses", "son", "soru", "tan", "tek", "ton", "top",
        "tur", "umut", "uzak", "var", "yan", "yaz", "yer", "yol", "zaman"
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
