package com.subflow.pipeline

/** how formal the speaker is toward the person they address. */
enum class Formality { FORMAL, INFORMAL, UNKNOWN }

/**
 * how many people a line is spoken to.
 *
 * [SINGLE] covers both a honorific that showed a one-to-one exchange and an unmarked
 * line that defaults to one. They were separate values for a while, so that the
 * evidenced case could license a rewrite the assumed case could not — but the rewrite
 * in question is gone and no caller distinguishes them any more. A distinction nothing
 * reads is a distinction that will drift out of true, so it is one value again.
 */
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

    private val tr = java.util.Locale("tr")

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

    // english phrases that only make sense addressing more than one person. liberal by
    // design, and that design only holds while a miss here cannot corrupt a line: the
    // caller must treat "no group phrase matched" as unproven, never as singular.
    private val groupAddress = Regex(
        """\b(you all|all of you|you guys|you two|you three|you both|both of you|""" +
            """the two of you|you people|you lot|y'all|""" +
            """you(?:'re| are) all|you all are|""" +
            """(?:two|three|four|five|six|seven|eight|nine|ten|all|any|none|some|each|""" +
            """both|several|many|most) of you|""" +
            """gentlemen|ladies|guys|folks|boys|girls|kids|children|comrades|soldiers|""" +
            """everyone|everybody)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Explicit second-person-plural marking in the *Turkish* line.
     *
     * The strongest evidence about how many people are addressed is often in the line
     * being rewritten, not in the source: "Hepiniz tutuklusunuz." and "Beyler, geç
     * kaldınız." say plainly that this is a crowd, and no English phrase had to survive
     * translation for them to say it. Read as a veto only — it stops a repair, never
     * starts one — so a false positive costs an unrepaired line and nothing worse.
     */
    // Second-person-plural pronouns and quantifiers. Prefix matching, because these
    // words are nothing but an address in any of their case forms: "hepinize",
    // "sizlere", "ikinizi" are all still speaking to a group.
    private val pluralPronouns = listOf(
        "hepiniz", "sizler", "tümünüz", "ikiniz", "üçünüz", "dördünüz", "beşiniz",
        "hiçbiriniz", "biriniz", "herbiriniz", "çoğunuz", "bazılarınız", "kiminiz",
        "hanginiz", "kaçınız"
    ).map { TextMatch.wordStart(it) }

    // Plural vocatives. Whole-word matching, because unlike the pronouns these are
    // ordinary nouns and only the bare form is an address. "Beyler, geç kaldınız."
    // speaks to a crowd; "Kardeşlerimi gördünüz mü?" speaks to one person about some
    // brothers, and prefix matching read the second as the first — a repair the rule
    // used to make, lost in exactly the corpus it was written for.
    private val pluralVocatives = listOf(
        "beyler", "baylar", "hanımlar", "bayanlar", "beyefendiler", "hanımefendiler",
        "çocuklar", "arkadaşlar", "dostlar", "gençler", "askerler", "kardeşler"
    ).map { TextMatch.wholeWord(it) }

    /**
     * true when the Turkish line marks its addressee as plural in so many words.
     *
     * Matched against the Turkish-locale lowercasing rather than with IGNORE_CASE:
     * the JVM's case-insensitive flag is ASCII-only unless UNICODE_CASE goes with it,
     * so "Çocuklar" would not have matched "çocuklar", and ROOT lowercasing turns "İ"
     * into i plus a combining dot, which matches nothing.
     */
    fun hasPluralAddress(turkishLine: String): Boolean {
        val lower = turkishLine.lowercase(tr)
        return pluralPronouns.any { it.containsMatchIn(lower) } ||
            pluralVocatives.any { it.containsMatchIn(lower) }
    }


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

    /**
     * true when [text] addresses more than one person.
     *
     * "everyone"/"everybody" count unconditionally. They were briefly gated on a
     * neighbouring comma, to tell "Everyone, calm down" from "Everyone left" — but
     * subtitles drop the vocative comma constantly, so "Everyone calm down" read as one
     * addressee. The gate is not worth it either way: a group reading can only stop a
     * rewrite, so calling a narrated "everyone" a crowd costs one unrepaired line, while
     * missing a real one used to corrupt the line instead.
     */
    fun isGroupAddress(text: String): Boolean = groupAddress.containsMatchIn(text)

    private fun strongest(a: Formality?, b: Formality): Formality = when {
        a == null -> b
        a == Formality.FORMAL || b == Formality.FORMAL -> Formality.FORMAL
        a == Formality.INFORMAL || b == Formality.INFORMAL -> Formality.INFORMAL
        else -> Formality.UNKNOWN
    }
}
