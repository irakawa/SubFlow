package com.subflow.pipeline

/**
 * follows a conversation line by line and decides who each line is spoken to.
 *
 * a honorific ("-san", "-sensei") anchors a one-to-one exchange and stays in
 * effect for a few lines, since not every line repeats the name. an explicit
 * "you all" phrase marks a group and likewise lingers, so a single stray
 * honorific in the middle of a crowd scene doesn't flip us back to singular.
 *
 * stateful across the whole episode. feed it source (pre-translation) lines in
 * order; the honorifics and "you all" cues live in the source, not the noisy MT
 * output.
 */
class SceneParticipantTracker {

    /** honorific / group cues stay in effect this many following lines. */
    private val window = 4

    private var ambientFormality: Formality? = null
    private var sinceHonorific = Int.MAX_VALUE
    private var sinceGroup = Int.MAX_VALUE

    /** updates scene state with [sourceLine] and returns who it addresses. */
    fun next(sourceLine: String): Addressee {
        val group = AddresseeAnalyzer.isGroupAddress(sourceLine)
        val honorific = AddresseeAnalyzer.formalityOf(sourceLine)

        if (honorific != null) {
            ambientFormality = honorific
            sinceHonorific = 0
        } else if (sinceHonorific != Int.MAX_VALUE) {
            sinceHonorific++
        }
        if (group) sinceGroup = 0 else if (sinceGroup != Int.MAX_VALUE) sinceGroup++

        // a group phrase in this line settles it: plural is genuinely correct.
        if (group) {
            return Addressee(Plurality.GROUP, honorific ?: ambientFormality ?: Formality.UNKNOWN)
        }

        // formality anchor: this line's honorific, or a recent one still in the window.
        // absent one we do not guess — UNKNOWN, which keeps the register decision out of
        // it and only allows the plural-ending repair.
        val formality = when {
            honorific != null -> honorific
            sinceHonorific <= window -> ambientFormality ?: Formality.UNKNOWN
            else -> Formality.UNKNOWN
        }

        // how many people are being addressed and how formally are separate questions,
        // and only the second one needs a honorific. Tying plurality to the honorific
        // meant every source without one — English film, series, animation, most of what
        // this app is pointed at — resolved AMBIGUOUS forever and rule 3.2 never ran.
        //
        // Absent a group cue, one addressee is the reading dialogue usually supports.
        // A honorific makes that firmer, but nothing downstream treats the two cases
        // differently any more, so they are not reported apart.
        return when {
            sinceGroup <= window -> Addressee(Plurality.AMBIGUOUS, formality)
            else -> Addressee(Plurality.SINGLE, formality)
        }
    }
}
