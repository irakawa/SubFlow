package com.subflow.pipeline

import java.util.Locale

/**
 * fixes singular/plural address on a translated Turkish line, given who the line
 * is spoken to. two independent layers (see SUBFLOW_LANGUAGE_RULES 3.2):
 *
 *   LAYER 1 — explicit group words ("hepiniz", "sizler", "tümünüz") are never
 *   right for one person, even a respected one, so they always get pulled back
 *   to a singular pronoun.
 *
 *   LAYER 2 — the sen/siz verb ending only depends on formality. Speaking
 *   formally to one person ("siz gelmişsiniz") is correct Turkish and is left
 *   alone. Only a casual 1:1 line that came back with a stray plural ending gets
 *   singularized ("gelmişsiniz" -> "gelmişsin").
 *
 * name-agnostic: it works off the resolved [Addressee], not any character.
 */
object GrammarFixer {

    private val tr = Locale("tr")

    fun fix(line: String, addressee: Addressee): String {
        // only correct when we're sure it's one person. group / ambiguous is left as is.
        if (addressee.plurality != Plurality.SINGLE) return line
        val formal = addressee.formality == Formality.FORMAL

        var out = fixGroupMarkers(line, formal) // LAYER 1, always
        if (!formal) out = singularizeVerbs(out) // LAYER 2, casual/unknown only
        return out
    }

    /** the singular a group word collapses to, informal and formal variants. */
    private data class Pronoun(val informal: String, val formal: String)

    // "herkes" is intentionally absent: it is usually a real "everyone" subject,
    // not a second-person address, and rewriting it would corrupt correct lines.
    private val groupWords: Map<String, Pronoun> = mapOf(
        "hepiniz" to Pronoun("sen", "siz"),
        "hepinize" to Pronoun("sana", "size"),
        "hepinizi" to Pronoun("seni", "sizi"),
        "hepinizin" to Pronoun("senin", "sizin"),
        "sizler" to Pronoun("sen", "siz"),
        "sizlere" to Pronoun("sana", "size"),
        "sizleri" to Pronoun("seni", "sizi"),
        "sizlerin" to Pronoun("senin", "sizin"),
        "tümünüz" to Pronoun("sen", "siz"),
        "tümünüze" to Pronoun("sana", "size"),
        "tümünüzü" to Pronoun("seni", "sizi")
    )

    private val groupWordRegex = Regex(
        """\b(${groupWords.keys.joinToString("|")})\b""",
        RegexOption.IGNORE_CASE
    )

    private fun fixGroupMarkers(line: String, formal: Boolean): String =
        groupWordRegex.replace(line) { m ->
            val p = groupWords[m.value.lowercase(tr)] ?: return@replace m.value
            preserveCase(m.value, if (formal) p.formal else p.informal)
        }

    // second-person-plural verb endings. each is the singular ending plus the
    // plural marker (vowel + z), so dropping the last two chars restores the
    // singular. anchored on the consonant (s/d/t) so nouns like "deniz" or
    // "yalnız" can't match.
    private val pluralVerbEnding = Regex(
        """\b(\p{L}+?)(sınız|siniz|sunuz|sünüz|dınız|diniz|dunuz|dünüz|tınız|tiniz|tunuz|tünüz)\b""",
        RegexOption.IGNORE_CASE
    )

    private fun singularizeVerbs(line: String): String =
        pluralVerbEnding.replace(line) { m ->
            m.groupValues[1] + m.groupValues[2].dropLast(2)
        }

    // --- SUBFLOW_LANGUAGE_RULES 3.2, second-person-plural IMPERATIVE ---
    //
    // The rule above strips a plural marker off a conjugated predicate: "-sınız" is the
    // singular copula "-sın" plus "-ız", so dropping two characters lands on the singular.
    // An imperative is built the other way round — the suffix hangs straight off the bare
    // stem ("kal" + "ınız") — so that rule neither matched "kalınız" nor could have
    // produced "kal" from it, and the reported "yerinde kalınız" went out untouched.
    //
    // Only the polite "-ınız/-iniz/-unuz/-ünüz" family is handled. The plain "-ın/-in/
    // -un/-ün" imperative is left alone on purpose: it is spelled exactly like the
    // genitive and the possessive, and "gelin" (bride), "kalın" (thick), "burun" (nose)
    // are ordinary words. There is no safe way to tell them apart, so we don't try.

    private val politeImperative = Regex(
        "(?<![\\p{L}\\p{N}])(\\p{L}+?)(y?)(ınız|iniz|unuz|ünüz)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE
    )

    // "-ınız" preceded by s, d or t is the predicate ending the rule above owns
    // ("nasıl|sınız", "gör|dünüz"). Never the imperative's business.
    private val predicateConsonant = setOf('s', 'd', 't', 'S', 'D', 'T')

    // Turkish softens p/ç/t/k to b/c/d/ğ before a vowel: the stem of "gidiniz" is "git",
    // not "gid". Reversing that is a guess, and a wrong stem is worse than no fix.
    private val softenedFinal = setOf('b', 'c', 'd', 'ğ', 'B', 'C', 'D', 'Ğ')

    /** below this a stem is more likely an irregular remnant ("yiyiniz" -> "yi", stem "ye"). */
    private const val MIN_STEM = 3

    /**
     * Reduces a polite plural imperative to the singular, for one informal addressee.
     *
     * Needs [source] because the Turkish suffix is ambiguous on its own: "-ınız" is also
     * the second-person-plural possessive, so "kitabınız" (your book) is spelled like an
     * imperative and is not one. English tells them apart — an imperative has no subject —
     * and getting this wrong turns a noun into a verb stem, so the source has the final say.
     */
    fun fixPluralImperative(source: String, translated: String, addressee: Addressee): String {
        if (addressee.plurality != Plurality.SINGLE) return translated
        if (addressee.formality == Formality.FORMAL) return translated // "siz" is the respect
        if (!looksImperative(source)) return translated

        return politeImperative.replace(translated) { m ->
            val stem = m.groupValues[1]
            val buffer = m.groupValues[2] // the "y" that joins a vowel-final stem
            val last = stem.lastOrNull()
            when {
                stem.length < MIN_STEM -> m.value
                // only a bare suffix can be the predicate's; with a y buffer it cannot be
                buffer.isEmpty() && last in predicateConsonant -> m.value
                last in softenedFinal -> m.value
                else -> stem
            }
        }
    }

    // fillers that can open an order without being the verb
    private val imperativeFillers = setOf(
        "please", "hey", "oh", "ah", "okay", "ok", "well", "now", "just",
        "alright", "and", "but", "so", "then", "yeah", "yes", "no", "hmm", "come", "on"
    )

    // a line opening with one of these has a subject or is a question, so it is not an
    // order. "don't" and "never" are absent on purpose: "Don't move" is an imperative.
    private val nonImperativeOpeners = setOf(
        "i", "you", "he", "she", "it", "we", "they", "there", "that", "this", "these", "those",
        "the", "a", "an", "my", "your", "his", "her", "its", "our", "their",
        "is", "are", "am", "was", "were", "been", "being", "do", "does", "did",
        "can", "could", "will", "would", "shall", "should", "may", "might", "must",
        "have", "has", "had", "let's", "lets",
        "what", "where", "when", "why", "how", "who", "whom", "which", "whose",
        "if", "because", "everyone", "everybody", "someone", "somebody", "nobody",
        "everything", "nothing", "something", "maybe", "perhaps",
        "i'm", "you're", "he's", "she's", "it's", "we're", "they're",
        "there's", "that's", "i'll", "you'll", "i've", "you've", "i'd", "you'd"
    )

    /** true when [source] reads as an order rather than a statement or a question. */
    private fun looksImperative(source: String): Boolean {
        val trimmed = source.trim()
        if (trimmed.isEmpty() || trimmed.endsWith("?")) return false
        // ROOT, not the Turkish locale: lowercasing English "I" in tr gives "ı"
        val tokens = trimmed.split(Regex("[^\\p{L}']+"))
            .filter { it.isNotBlank() }
            .map { it.lowercase(Locale.ROOT) }
        val first = tokens.firstOrNull { it !in imperativeFillers } ?: return false
        return first !in nonImperativeOpeners
    }

    /** copies the first-letter casing of [original] onto [replacement]. */
    private fun preserveCase(original: String, replacement: String): String =
        if (original.firstOrNull()?.isUpperCase() == true) {
            replacement.replaceFirstChar { it.titlecase(tr) }
        } else {
            replacement
        }

    // --- SUBFLOW_LANGUAGE_RULES 3.3: unnecessary "mı/mi" question particle ---

    // an explicit alternative makes it a real question ("X mı yoksa Y mi") -> leave it.
    private val alternative = Regex("""\b(or|yoksa|veya)\b""", RegexOption.IGNORE_CASE)

    // a standalone question particle sitting at the very end, before punctuation only.
    private val trailingParticle = Regex(
        """\s+(mı|mi|mu|mü)(?=[\s?!.…]*$)""",
        RegexOption.IGNORE_CASE
    )

    // grammatical words that, even capitalized at line start, mean the line is a real
    // sentence and not a bare name being echoed in surprise.
    private val sentenceWords = setOf(
        "is", "are", "am", "was", "were", "be", "been", "being",
        "you", "your", "you're", "youre", "this", "that", "these", "those",
        "the", "a", "an", "who", "what", "where", "when", "why", "how", "which",
        "do", "does", "did", "it", "he", "she", "they", "we", "i", "really",
        "so", "and", "but", "or", "not", "no", "yes", "can", "will", "would",
        "could", "should", "to", "of", "in", "on", "at", "my", "me", "him", "her"
    )

    // filler/interjections around a name don't disqualify a surprise beat.
    private val interjections = setOf(
        "oh", "ah", "eh", "uh", "um", "huh", "hey", "hmm", "wha", "er", "wait", "no", "hah"
    )

    /**
     * drops a needless "mı/mi" when the source line is just a name (+honorific) said
     * in surprise, not a genuine yes/no question. Anchored on a honorific in the
     * source so ordinary questions like "Is this Akaishi-san?" keep their particle.
     */
    fun fixSurpriseParticle(source: String, translated: String): String {
        if (AddresseeAnalyzer.formalityOf(source) == null) return translated // no name+honorific
        if (alternative.containsMatchIn(source)) return translated // real "A or B?" question
        if (!isBareNameCall(source)) return translated
        return trailingParticle.replace(translated) { "" }
    }

    /** true when [source] is only a name/honorific plus interjections and punctuation. */
    private fun isBareNameCall(source: String): Boolean {
        val tokens = source.split(Regex("""[\s,.!?…"'()\[\]]+""")).filter { it.isNotBlank() }
        val leftover = tokens.filterNot { token ->
            val lower = token.lowercase(tr)
            when {
                lower in interjections -> true                          // "oh", "wait"
                AddresseeAnalyzer.formalityOf(token) != null -> true    // "Akaishi-san"
                lower in sentenceWords -> false                         // real sentence word
                token.first().isUpperCase() -> true                     // a proper name
                else -> false
            }
        }
        return leftover.isEmpty()
    }
}
