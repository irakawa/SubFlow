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

    /** copies the first-letter casing of [original] onto [replacement]. */
    private fun preserveCase(original: String, replacement: String): String =
        if (original.firstOrNull()?.isUpperCase() == true) {
            replacement.replaceFirstChar { it.titlecase(tr) }
        } else {
            replacement
        }
}
