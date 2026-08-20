package com.subflow.pipeline

/**
 * Keeps Japanese honorifics out of the machine translator's reach
 * (SUBFLOW_LANGUAGE_RULES 4: "-san, -kun, -chan ... Türkçeleştirilmez").
 *
 * The rule was written and never implemented, so the provider decided. "-chan" landed
 * on the Turkish diminutive and "Hana-chan" came back "Hanacığı"; "Oshira-sama!" came
 * back "Osiramasama!", the name itself misspelled and the honorific glued on.
 *
 * Rather than try to repair that afterwards — which means telling a name from a suffix
 * in text that has already been mangled — the pair never goes to the provider at all.
 * It is replaced by an opaque token, and put back when the translation returns.
 *
 * ## Choosing the token
 *
 * Measured, not guessed, against the two providers the pipeline actually reaches first
 * (translate.googleapis.com gtx and clients5), EN→TR and JA→TR, with several tokens on
 * one line and one token repeated:
 *
 * ```
 * «0»      -> came back as "0"      quotation marks normalised
 * zz0zz    -> came back as Zz0zz    title-cased at the start of a sentence
 * __SF0__  -> intact, 3/3           and the Turkish case ending attached to it
 * SFNAME0  -> intact, 3/3
 * Xq0qX    -> intact, 3/3
 * ⟦0⟧      -> intact, 3/3
 * ```
 *
 * `__SF0__` was taken from the survivors: double underscores do not occur in subtitle
 * text, so a collision is not a practical concern, and it reads as a code token rather
 * than a word, which is what stopped `zz0zz` from being capitalised.
 *
 * The two providers further down the list are unmeasured, and a provider that mangles
 * the token is expected rather than assumed impossible — [restore] refuses the line and
 * the caller falls back to translating it unmasked, which is what used to happen anyway.
 *
 * ## Known limit
 *
 * Turkish vowel harmony on a suffix the provider attaches is decided by the token's
 * shape, not the real name: `__SF0__` draws back vowels, so a name ending in a front
 * vowel can come back as "Efe-san'dan" where "Efe-san'den" was wanted. The name and the
 * honorific are correct, which is the thing rule 4 asks for; one harmony error on an
 * attached suffix is a much smaller defect than "Osiramasama".
 */
internal object HonorificMask {

    private const val PREFIX = "__SF"
    private const val SUFFIX = "__"

    /**
     * A capitalised name glued to an honorific by a hyphen.
     *
     * Deliberately narrower than [AddresseeAnalyzer]'s pattern, which also accepts a
     * space. That one only reads a signal, so a false positive costs nothing; this one
     * rewrites text, and "in San Francisco" matches `\w+\s(san)`. The hyphen is the form
     * the honorific is actually written in.
     *
     * The honorific alternation carries its own case-insensitive flag rather than the
     * whole pattern, because CASE_INSENSITIVE makes `\p{Lu}` match lowercase too and the
     * capital is doing real work here.
     */
    private val namedHonorific = Regex(
        "(?<![\\p{L}\\p{N}])(\\p{Lu}\\p{L}*)-((?i:san|kun|chan|sama|senpai|sensei|dono))(?![\\p{L}\\p{N}])"
    )

    /**
     * One batch of lines with their name+honorific pairs replaced.
     *
     * [lines] is a new list; the caller's own copy is untouched, because rule 3.2 reads
     * honorifics off the source text to decide formality and a masked line has no
     * honorific left to find.
     */
    /** why a batch went to the provider unprotected. null when it did not. */
    enum class Skipped {
        /** no name+honorific pair in the batch. the ordinary case, not worth a log line. */
        NOTHING_TO_MASK,

        /** the source already contains the token shape, so ours would be indistinguishable. */
        TOKEN_SHAPE_IN_SOURCE
    }

    data class Masked(
        val lines: List<String>,
        private val names: Map<String, String>,
        private val expected: List<Map<String, Int>>,
        /**
         * why masking did not run, or null when it did.
         *
         * Carried rather than inferred from [active] because the two reasons are not
         * alike: having nothing to mask happens on most batches, while refusing over a
         * token collision turns rule 4's protection off for twenty-five cues and needs
         * to be visible to somebody.
         */
        val skipped: Skipped? = null
    ) {
        /** false when there was nothing to mask, or masking was refused. */
        val active: Boolean get() = names.isNotEmpty()

        /** the nth token assigned in this batch, in first-appearance order. */
        fun token(n: Int): String = "$PREFIX$n$SUFFIX"

        internal fun originalOf(token: String): String? = names[token]
        internal fun expectedIn(index: Int): Map<String, Int> = expected.getOrElse(index) { emptyMap() }
    }

    /** Replaces every name+honorific pair in [lines] with a token. */
    fun mask(lines: List<String>): Masked {
        // if the shape already occurs in the text we would not be able to tell our own
        // token from theirs on the way back, so this batch simply goes unmasked
        if (lines.any { it.contains(PREFIX) }) {
            return Masked(lines, emptyMap(), emptyList(), Skipped.TOKEN_SHAPE_IN_SOURCE)
        }

        val names = LinkedHashMap<String, String>()   // token -> "Hana-chan"
        val assigned = LinkedHashMap<String, String>() // "Hana-chan" -> token
        val expected = ArrayList<Map<String, Int>>(lines.size)
        val out = ArrayList<String>(lines.size)

        for (line in lines) {
            val counts = LinkedHashMap<String, Int>()
            val masked = namedHonorific.replace(line) { m ->
                val original = m.value
                val token = assigned.getOrPut(original) {
                    "$PREFIX${assigned.size}$SUFFIX".also { names[it] = original }
                }
                counts[token] = (counts[token] ?: 0) + 1
                token
            }
            expected += counts
            out += masked
        }
        return Masked(out, names, expected, if (names.isEmpty()) Skipped.NOTHING_TO_MASK else null)
    }

    /**
     * Puts the names back into one translated line.
     *
     * @return the restored line, or null when the tokens did not come back exactly as
     *   they went out. Null is a refusal, not an error: the caller translates that line
     *   again without masking, so the worst case is the behaviour that existed before
     *   any of this — never a line with a mangled token in it.
     */
    fun restore(masked: Masked, index: Int, translated: String): String? {
        if (!masked.active) return translated
        val expected = masked.expectedIn(index)
        for ((token, count) in expected) {
            if (translated.occurrencesOf(token) != count) return null
        }
        var out = translated
        for (token in expected.keys) {
            val original = masked.originalOf(token) ?: return null
            out = out.replace(token, original)
        }
        // Anything token-shaped still here is not ours to explain. Checking only the
        // tokens this line was *expected* to carry left the other direction open: when
        // the provider merged two cues, the line that lost its token was refused and the
        // line that gained one was never scanned, so "__SF0__" went into the file. And
        // nothing downstream would have caught it — MegaDictionary has no key with an
        // underscore, fixGrammar's \p{L}+ does not match one, and renderSrt filters
        // nothing. This also catches a half-chewed remnant, which no count check can see.
        if (out.contains(PREFIX)) return null
        return out
    }

    /** [lines] with names put back, and the indices [restore] refused. */
    data class Restored(val lines: List<String>, val lost: List<Int>)

    /**
     * Restores a whole batch and says which lines could not be.
     *
     * The caller retranslates the reported indices unmasked and keeps the rest, so this
     * is where "did this line survive" is decided — once, here, rather than again in the
     * caller where no test would reach it.
     */
    fun restoreAll(masked: Masked, translated: List<String>): Restored {
        val out = translated.toMutableList()
        val lost = mutableListOf<Int>()
        for (i in translated.indices) {
            val restored = restore(masked, i, translated[i])
            if (restored == null) lost += i else out[i] = restored
        }
        return Restored(out, lost)
    }

    private fun String.occurrencesOf(token: String): Int {
        var from = 0
        var found = 0
        while (true) {
            val at = indexOf(token, from)
            if (at < 0) return found
            found++
            from = at + token.length
        }
    }
}
