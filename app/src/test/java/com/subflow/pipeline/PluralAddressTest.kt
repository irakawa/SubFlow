package com.subflow.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Genuine plural address must survive rule 3.2.
 *
 * Defaulting to SINGLE when no group phrase matched turned the group dictionary's false
 * negatives into corrupted lines: eleven phrases decided whether a real plural got
 * singularised. Absence of evidence is not evidence, so the layers now separate what was
 * shown from what was assumed, and the strongest evidence — the Turkish line's own plural
 * marking — is read too.
 */
class PluralAddressTest {

    private fun fix(source: String, translated: String, t: SceneParticipantTracker) =
        GrammarFixer.fix(translated, t.next(source))

    // --- the measured corruptions ---

    @Test
    fun `an explicit plural pronoun in the line is left alone`() {
        val t = SceneParticipantTracker()
        // no group phrase in the source at all: the Turkish line is the only evidence
        assertEquals("Hepiniz tutuklusunuz.", fix("Under arrest.", "Hepiniz tutuklusunuz.", t))
    }

    @Test
    fun `a plural vocative in the line is left alone`() {
        val t = SceneParticipantTracker()
        assertEquals("Beyler, geç kaldınız.", fix("You are late.", "Beyler, geç kaldınız.", t))
    }

    @Test
    fun `a plural quantifier in the line is left alone`() {
        val t = SceneParticipantTracker()
        assertEquals("Hiçbiriniz anlamıyorsunuz.", fix("Nobody understands.", "Hiçbiriniz anlamıyorsunuz.", t))
    }

    @Test
    fun `a counted group in the source is a group`() {
        assertTrue(AddresseeAnalyzer.isGroupAddress("Are the four of you ready?"))
        assertTrue(AddresseeAnalyzer.isGroupAddress("None of you are leaving."))
        assertTrue(AddresseeAnalyzer.isGroupAddress("You're all under arrest."))
        val t = SceneParticipantTracker()
        assertEquals(
            "Dördünüz hazır mısınız?",
            fix("Are the four of you ready?", "Dördünüz hazır mısınız?", t)
        )
    }

    @Test
    fun `a plural vocative in the source is a group`() {
        assertTrue(AddresseeAnalyzer.isGroupAddress("Gentlemen, we have a problem."))
        assertTrue(AddresseeAnalyzer.isGroupAddress("Ladies, this way."))
        assertTrue(AddresseeAnalyzer.isGroupAddress("Guys, look at this."))
    }

    // --- what was shown vs what was assumed ---

    @Test
    fun `an unmarked line is single only by assumption`() {
        val t = SceneParticipantTracker()
        assertEquals(Plurality.SINGLE_ASSUMED, t.next("Stay where you are.").plurality)
    }

    @Test
    fun `a honorific still gives evidenced single`() {
        val t = SceneParticipantTracker()
        assertEquals(Plurality.SINGLE, t.next("Naruto-kun, stay here.").plurality)
    }

    @Test
    fun `only evidenced single rewrites a group word into a pronoun`() {
        // layer 1 replaces "hepiniz" with "sen", which claims to know there is one
        // person. Evidence, not assumption, is what licenses that.
        val evidenced = SceneParticipantTracker()
        assertEquals("Sen gel.", fix("Naruto-kun, come here.", "Hepiniz gel.", evidenced))

        val assumed = SceneParticipantTracker()
        assertEquals("Hepiniz gel.", fix("Come here.", "Hepiniz gel.", assumed))
    }

    // --- the repairs this layer exists for still happen ---

    @Test
    fun `an ordinary line with no plural marking is still repaired`() {
        val t = SceneParticipantTracker()
        assertEquals("Nasılsın?", fix("How are you?", "Nasılsınız?", t))
        assertEquals("Onu gördün", fix("You saw him.", "Onu gördünüz", t))
    }

    @Test
    fun `the reported imperative is still repaired`() {
        val t = SceneParticipantTracker()
        val a = t.next("Stay where you are.")
        assertEquals(
            "Yerinde kal.",
            GrammarFixer.fixPluralImperative(
                "Stay where you are.", "en", GrammarFixer.fix("Yerinde kalınız.", a), a
            )
        )
    }

    @Test
    fun `an imperative aimed at a marked plural is left alone`() {
        val t = SceneParticipantTracker()
        val src = "Wait here."
        val a = t.next(src)
        assertEquals(
            "Beyler, bekleyiniz.",
            GrammarFixer.fixPluralImperative(src, "en", GrammarFixer.fix("Beyler, bekleyiniz.", a), a)
        )
    }
}
