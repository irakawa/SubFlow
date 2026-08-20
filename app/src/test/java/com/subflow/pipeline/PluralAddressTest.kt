package com.subflow.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // --- a vocative is a vocative, not any word that starts like one ---

    @Test
    fun `a plural vocative counts only when it is the address itself`() {
        assertTrue(AddresseeAnalyzer.hasPluralAddress("Beyler, geç kaldınız."))
        assertTrue(AddresseeAnalyzer.hasPluralAddress("Çocuklar, buraya gelin."))
        // these are objects of the sentence, not who it is spoken to
        assertFalse(AddresseeAnalyzer.hasPluralAddress("Kardeşlerimi gördünüz mü?"))
        assertFalse(AddresseeAnalyzer.hasPluralAddress("Çocukları okula götürdünüz."))
        assertFalse(AddresseeAnalyzer.hasPluralAddress("Arkadaşlarım geldi."))
        assertFalse(AddresseeAnalyzer.hasPluralAddress("Askerleri gördünüz."))
    }

    @Test
    fun `a third-person plural noun does not block the repair`() {
        // measured loss: this used to be repaired and stopped being, in the corpus the
        // rule was written for
        val t = SceneParticipantTracker()
        t.next("Naruto-kun, stop!")
        assertEquals(
            "Kardeşlerimi gördün mü?",
            fix("Did you see my brothers?", "Kardeşlerimi gördünüz mü?", t)
        )
    }

    @Test
    fun `a pronoun still matches through its case endings`() {
        // "hepiniz" is never anything but second-person plural, so its suffixed forms
        // stay on prefix matching
        assertTrue(AddresseeAnalyzer.hasPluralAddress("Hepinize söyledim."))
        assertTrue(AddresseeAnalyzer.hasPluralAddress("Sizlere güveniyorum."))
        assertTrue(AddresseeAnalyzer.hasPluralAddress("İkinizi de görüyorum."))
    }

    // --- what was shown vs what was assumed ---

    @Test
    fun `an unmarked line is single only by assumption`() {
        val t = SceneParticipantTracker()
        assertEquals(Plurality.SINGLE, t.next("Stay where you are.").plurality)
    }

    @Test
    fun `a honorific still gives evidenced single`() {
        val t = SceneParticipantTracker()
        assertEquals(Plurality.SINGLE, t.next("Naruto-kun, stay here.").plurality)
    }

    @Test
    fun `a honorific does not license overriding the line's own plural marking`() {
        // this used to expect "Sen gel." on the evidenced branch, while the same code
        // answered hasPluralAddress("Hepiniz gel.") == true. A honorific says the speaker
        // is on familiar terms with someone; it does not say the Turkish line in front of
        // us is addressed to one person, and the line says otherwise.
        val evidenced = SceneParticipantTracker()
        assertEquals("Hepiniz gel.", fix("Naruto-kun, come here.", "Hepiniz gel.", evidenced))

        val assumed = SceneParticipantTracker()
        assertEquals("Hepiniz gel.", fix("Come here.", "Hepiniz gel.", assumed))
    }

    // --- the veto does not care which branch asked ---

    @Test
    fun `a plural line survives a nearby honorific`() {
        // measured: after "Naruto-kun, stop!" the tracker reports evidenced SINGLE for
        // the next four lines, and the veto was not being asked on that branch at all
        val t = SceneParticipantTracker()
        t.next("Naruto-kun, stop!")
        assertEquals("Hepiniz tutuklusunuz.", fix("Under arrest.", "Hepiniz tutuklusunuz.", t))
        assertEquals("Beyler, geç kaldınız.", fix("You are late.", "Beyler, geç kaldınız.", t))
        assertEquals("İkiniz de tutuklusunuz.", fix("Under arrest.", "İkiniz de tutuklusunuz.", t))
    }

    @Test
    fun `a plural line survives a nearby honorific through the imperative rule too`() {
        val t = SceneParticipantTracker()
        t.next("Naruto-kun, stop!")
        val src = "Wait here."
        val a = t.next(src)
        assertEquals(
            "Beyler, bekleyiniz.",
            GrammarFixer.fixPluralImperative(src, "en", GrammarFixer.fix("Beyler, bekleyiniz.", a), a)
        )
    }

    @Test
    fun `the honorific scene is still repaired when the line marks nothing`() {
        // the veto only stops a repair; a line with no plural marking is untouched by it
        val t = SceneParticipantTracker()
        t.next("Naruto-kun, are you okay?")
        assertEquals("İyi misin?", fix("Are you okay?", "İyi misiniz?", t))
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
