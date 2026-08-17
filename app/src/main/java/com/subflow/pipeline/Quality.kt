package com.subflow.pipeline

/**
 * Delivery-confidence score (0-100), the "uyum" badge the user sees.
 *
 * Everything that gets a score already passed the identity and language gates,
 * so the number is about provenance (how the sub was produced) plus a measured
 * VAD nudge when a video stream was available. Two rails: a gated result never
 * drops into the failure band (floor 70), and we never claim certainty we
 * couldn't verify (ceiling 98). How it was produced is shown by the method
 * label, not by quietly docking a correct translation.
 */
internal object Quality {
    const val HUMAN_TAGS_MATCH = 92 // human target sub, release tags line up
    const val HUMAN = 85            // human target sub, tags differ
    const val EMBEDDED = 95         // from the video's own container, timing is native
    const val TORRENT = 90          // streamed from the release's own MKV, timing inherited
    const val TRANSLATED = 85       // our own EN/JA to TR production, first class
    const val WHISPER = 75          // transcription adds a real, honest extra uncertainty
    const val FLOOR = 70
    const val CEILING = 98

    /**
     * blends a base score with measured VAD confidence. only called when timing was measured.
     *
     * The lower rail is the base itself once the base sits under [FLOOR]. The floor exists
     * to keep a fully delivered, gated result out of the failure band; it must not rescue a
     * result that earned a low score for a reason of its own. Aligning cues does not
     * translate the ones that were left in English.
     */
    fun withSync(base: Int, confPct: Int): Int =
        (base + ((confPct - 50) / 5).coerceIn(-10, 10)).coerceIn(minOf(base, FLOOR), CEILING)

    /**
     * Docks the score for cues that never got translated.
     *
     * One point per percent left in the source language. The pipeline delivers a partly
     * translated file rather than nothing, which is the right call — but a file that is
     * 40% English is not the same product as one that is 0% English, and the score is
     * where that difference has to show. Linear because the cost to the viewer is
     * linear: every untranslated cue is one cue they cannot read.
     *
     * Deliberately allowed below [FLOOR]. The floor protects a result that was fully
     * delivered; a half-translated file is not that, and clamping it back into the
     * delivered band would restate exactly the claim this penalty exists to withdraw.
     */
    fun withUntranslated(base: Int, untranslatedPct: Int): Int =
        (base - untranslatedPct.coerceIn(0, 100)).coerceIn(0, CEILING)

    /** share of cues left in the source language, rounded up so a real gap never shows as 0%. */
    fun untranslatedPercent(untranslatedCues: Int, totalCues: Int): Int {
        if (totalCues <= 0 || untranslatedCues <= 0) return 0
        return (((untranslatedCues * 100) + totalCues - 1) / totalCues).coerceAtMost(100)
    }
}
