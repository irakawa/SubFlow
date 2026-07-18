package com.subflow.pipeline

/** how formal the speaker is toward the person they address. */
enum class Formality { FORMAL, INFORMAL, UNKNOWN }

/** how many people a line is spoken to. */
enum class Plurality { SINGLE, GROUP, AMBIGUOUS }

/** the resolved addressee for one line: how many, and how formally. */
data class Addressee(val plurality: Plurality, val formality: Formality)

/**
 * reads a source line for two independent signals used by the singular/plural
 * address fix: the honorific attached to a name (tells us how formal the speaker
 * is) and any explicit "you all" style phrase (tells us it's a group).
 *
 * name-agnostic on purpose: the honorific pattern matches any word + honorific,
 * never a specific character.
 */
object AddresseeAnalyzer {

    // any word glued to a japanese honorific, e.g. "Akaishi-san", "Naruto-kun".
    private val honorific = Regex(
        """\w+[-\s](san|kun|chan|sama|senpai|sensei|dono)\b""",
        RegexOption.IGNORE_CASE
    )

    // honorifics also used as a bare title ("Sensei, help!"). only the multi-letter,
    // unambiguous ones: "san"/"kun"/"chan" as lone words would be too noisy.
    private val bareHonorific = Regex(
        """\b(sensei|senpai|sama|dono)\b""",
        RegexOption.IGNORE_CASE
    )

    // english phrases that only make sense addressing more than one person.
    // liberal by design: a false positive here just skips the fix, never corrupts a line.
    private val groupAddress = Regex(
        """\b(you all|all of you|you guys|you two|you three|you both|both of you|the two of you|you people|you lot|y'all|everyone|everybody)\b""",
        RegexOption.IGNORE_CASE
    )

    /** the formality of the honorific in [text], or null when there's no honorific. */
    fun formalityOf(text: String): Formality? {
        var result: Formality? = null
        for (m in honorific.findAll(text)) {
            result = strongest(result, classify(m.groupValues[1]))
        }
        for (m in bareHonorific.findAll(text)) {
            result = strongest(result, classify(m.groupValues[1]))
        }
        return result
    }

    private fun classify(honorific: String): Formality = when (honorific.lowercase()) {
        "sama", "sensei", "dono" -> Formality.FORMAL
        "kun", "chan" -> Formality.INFORMAL
        else -> Formality.UNKNOWN // san, senpai depend on context
    }

    fun isGroupAddress(text: String): Boolean = groupAddress.containsMatchIn(text)

    private fun strongest(a: Formality?, b: Formality): Formality = when {
        a == null -> b
        a == Formality.FORMAL || b == Formality.FORMAL -> Formality.FORMAL
        a == Formality.INFORMAL || b == Formality.INFORMAL -> Formality.INFORMAL
        else -> Formality.UNKNOWN
    }
}
