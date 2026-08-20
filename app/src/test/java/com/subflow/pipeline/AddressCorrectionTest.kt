package com.subflow.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * covers the singular/plural address fix (SUBFLOW_LANGUAGE_RULES 3.2):
 * honorific -> formality detection, scene tracking, and the two correction layers.
 */
class AddressCorrectionTest {

    // --- AddresseeAnalyzer ---

    @Test
    fun `honorific maps to the right formality`() {
        assertEquals(Formality.FORMAL, AddresseeAnalyzer.formalityOf("Yoroshiku, Tanaka-sama."))
        assertEquals(Formality.FORMAL, AddresseeAnalyzer.formalityOf("Ohayo, sensei!"))
        assertEquals(Formality.INFORMAL, AddresseeAnalyzer.formalityOf("Naruto-kun, wait!"))
        assertEquals(Formality.INFORMAL, AddresseeAnalyzer.formalityOf("Sakura-chan is here."))
        assertEquals(Formality.UNKNOWN, AddresseeAnalyzer.formalityOf("Akaishi-san, over here."))
        assertEquals(Formality.UNKNOWN, AddresseeAnalyzer.formalityOf("Nice work, senpai."))
    }

    @Test
    fun `no honorific means no formality`() {
        assertEquals(null, AddresseeAnalyzer.formalityOf("Where are you going?"))
    }

    @Test
    fun `formal wins when a line mixes honorifics`() {
        assertEquals(Formality.FORMAL, AddresseeAnalyzer.formalityOf("Naruto-kun, greet Tanaka-sama."))
    }

    @Test
    fun `group address phrases are detected`() {
        assertTrue(AddresseeAnalyzer.isGroupAddress("You all need to leave now."))
        assertTrue(AddresseeAnalyzer.isGroupAddress("Listen up, everyone!"))
        assertTrue(AddresseeAnalyzer.isGroupAddress("Both of you, come here."))
        assertFalse(AddresseeAnalyzer.isGroupAddress("You need to leave now."))
    }

    // --- SceneParticipantTracker ---

    @Test
    fun `honorific line is a single formal addressee`() {
        val t = SceneParticipantTracker()
        val a = t.next("Please forgive me, Tanaka-sama.")
        assertEquals(Plurality.SINGLE, a.plurality)
        assertEquals(Formality.FORMAL, a.formality)
    }

    @Test
    fun `honorific keeps addressing single for the next few lines`() {
        val t = SceneParticipantTracker()
        t.next("Naruto-kun, listen.")           // anchor
        val a = t.next("You are stronger now.")  // no honorific, still in window
        assertEquals(Plurality.SINGLE, a.plurality)
        assertEquals(Formality.INFORMAL, a.formality)
    }

    @Test
    fun `group phrase overrides a lingering honorific`() {
        val t = SceneParticipantTracker()
        t.next("Tanaka-kun is our leader.")
        val a = t.next("All of you, follow him!")
        assertEquals(Plurality.GROUP, a.plurality)
    }

    @Test
    fun `unmarked line with no anchor addresses one person`() {
        // this used to assert AMBIGUOUS, which is what kept rule 3.2 from ever running on
        // a source without a honorific. Only an explicit group cue makes it plural now;
        // an unmarked line reads as one addressee, and the register stays unclaimed.
        val t = SceneParticipantTracker()
        val a = t.next("The weather is nice today.")
        assertEquals(Plurality.SINGLE, a.plurality)
        assertEquals(Formality.UNKNOWN, a.formality)
    }

    // --- GrammarFixer: LAYER 1 (group markers) ---

    @Test
    fun `layer 1 collapses group words for an informal single addressee`() {
        val a = Addressee(Plurality.SINGLE, Formality.INFORMAL)
        assertEquals("Sen gel.", GrammarFixer.fix("Hepiniz gel.", a))
        assertEquals("sana söyledim", GrammarFixer.fix("sizlere söyledim", a))
    }

    @Test
    fun `layer 1 fires even for a formal single addressee but keeps siz`() {
        // group word is wrong for one person even with respect, but the pronoun stays formal
        val a = Addressee(Plurality.SINGLE, Formality.FORMAL)
        assertEquals("Siz gelin.", GrammarFixer.fix("Hepiniz gelin.", a))
        assertEquals("size güveniyorum", GrammarFixer.fix("sizlere güveniyorum", a))
    }

    // --- GrammarFixer: LAYER 2 (sen/siz conjugation) ---

    @Test
    fun `layer 2 singularizes a stray plural ending on a casual line`() {
        val a = Addressee(Plurality.SINGLE, Formality.INFORMAL)
        assertEquals("Nasılsın?", GrammarFixer.fix("Nasılsınız?", a))
        assertEquals("Geldin mi?", GrammarFixer.fix("Geldiniz mi?", a))
        assertEquals("Onu gördün", GrammarFixer.fix("Onu gördünüz", a))
    }

    @Test
    fun `layer 2 leaves the formal siz conjugation alone`() {
        // respectful "siz" to one person is correct Turkish and must survive
        val a = Addressee(Plurality.SINGLE, Formality.FORMAL)
        assertEquals("Nasılsınız?", GrammarFixer.fix("Nasılsınız?", a))
        assertEquals("Geldiniz mi?", GrammarFixer.fix("Geldiniz mi?", a))
    }

    @Test
    fun `layer 2 never touches a group addressee`() {
        val a = Addressee(Plurality.GROUP, Formality.INFORMAL)
        assertEquals("Nasılsınız?", GrammarFixer.fix("Nasılsınız?", a))
        assertEquals("Hepiniz gelin.", GrammarFixer.fix("Hepiniz gelin.", a))
    }

    @Test
    fun `layer 2 does not corrupt lookalike nouns`() {
        val a = Addressee(Plurality.SINGLE, Formality.INFORMAL)
        // deniz, yalnız, yıldız end similarly but aren't plural verb endings
        assertEquals("Deniz yalnız kaldı", GrammarFixer.fix("Deniz yalnız kaldı", a))
        assertEquals("Yıldızları gördüm", GrammarFixer.fix("Yıldızları gördüm", a))
    }

    @Test
    fun `ambiguous addressee is passed through untouched`() {
        val a = Addressee(Plurality.AMBIGUOUS, Formality.UNKNOWN)
        assertEquals("Nasılsınız?", GrammarFixer.fix("Nasılsınız?", a))
    }

    // --- end to end through the tracker ---

    @Test
    fun `casual honorific scene gets singularized end to end`() {
        val t = SceneParticipantTracker()
        // source (English/romaji) drives detection; TR line is what gets fixed
        val a1 = t.next("Naruto-kun, are you okay?")
        assertEquals("İyi misin?", GrammarFixer.fix("İyi misiniz?", a1))
        val a2 = t.next("You worried me.")           // still in the honorific window
        assertEquals("Beni endişelendirdin", GrammarFixer.fix("Beni endişelendirdiniz", a2))
    }

    @Test
    fun `formal honorific scene keeps respect end to end`() {
        val t = SceneParticipantTracker()
        val a = t.next("Thank you, sensei.")
        assertEquals("Çok naziksiniz", GrammarFixer.fix("Çok naziksiniz", a))
    }
}
