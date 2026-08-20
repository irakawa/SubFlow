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
        // AMBIGUOUS here is what kept rule 3.2 from ever running on a source without a
        // honorific, so an unmarked line resolves to one addressee. A honorific makes
        // that firmer, but nothing downstream reads the difference, so it is not
        // reported as a separate value.
        val t = SceneParticipantTracker()
        val a = t.next("The weather is nice today.")
        assertEquals(Plurality.SINGLE, a.plurality)
        assertEquals(Formality.UNKNOWN, a.formality)
    }

    // --- the line's own plural marking vetoes every repair ---

    @Test
    fun `the line's plural marking outranks any addressee we resolved`() {
        // these two used to expect "Sen gel." and "Siz gelin." from a rewrite that
        // turned an explicit group word into a singular pronoun. That rewrite is gone:
        // the word it acted on is itself the clearest statement that this is a crowd.
        val informal = Addressee(Plurality.SINGLE, Formality.INFORMAL)
        assertEquals("Hepiniz gel.", GrammarFixer.fix("Hepiniz gel.", informal))
        assertEquals("sizlere söyledim", GrammarFixer.fix("sizlere söyledim", informal))

        val formal = Addressee(Plurality.SINGLE, Formality.FORMAL)
        assertEquals("Hepiniz gelin.", GrammarFixer.fix("Hepiniz gelin.", formal))
        assertEquals("sizlere güveniyorum", GrammarFixer.fix("sizlere güveniyorum", formal))
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
