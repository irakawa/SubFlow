package com.subflow.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * covers the stutter (2.1) and stray "mı/mi" (3.3) rules from
 * SUBFLOW_LANGUAGE_RULES.
 */
class LanguageRulesTest {

    // --- 3.3: unnecessary "mı/mi" question particle ---

    private fun mi(source: String, translated: String) =
        GrammarFixer.fixSurpriseParticle(source, translated)

    @Test
    fun `surprised name recognition drops the particle`() {
        assertEquals("Akaishi-san?!", mi("Akaishi-san?!", "Akaishi-san mı?!"))
        assertEquals("Ah, Akaishi-san?", mi("Oh, Akaishi-san?", "Ah, Akaishi-san mı?"))
    }

    @Test
    fun `bare honorific recognition drops the particle`() {
        assertEquals("Sensei?", mi("Sensei?", "Sensei mi?"))
    }

    @Test
    fun `a real question keeps its particle`() {
        // "Is this Akaishi-san?" is a genuine yes/no question
        assertEquals("Bu Akaishi-san mı?", mi("Is this Akaishi-san?", "Bu Akaishi-san mı?"))
    }

    @Test
    fun `an explicit alternative keeps the particle`() {
        assertEquals(
            "Akaishi-san mı yoksa Akaishi-kun mu?",
            mi("Akaishi-san or Akaishi-kun?", "Akaishi-san mı yoksa Akaishi-kun mu?")
        )
    }

    @Test
    fun `a line without a honorific is never touched`() {
        // ordinary questions must survive untouched
        assertEquals("Gerçekten mi?", mi("Really?", "Gerçekten mi?"))
        assertEquals("Gidiyor musun?", mi("Are you leaving?", "Gidiyor musun?"))
    }

    // --- 2.1: stutter preservation with the Turkish first letter ---

    private fun st(source: String, translated: String) =
        StutterPreserver.apply(source, translated)

    @Test
    fun `dropped stutter is restored from the translated word`() {
        assertEquals("A-aptal!", st("B-baka!", "Aptal!"))
        assertEquals("N-ne yapıyorsun?", st("W-what are you doing?", "Ne yapıyorsun?"))
    }

    @Test
    fun `carried-over source letter is corrected to the turkish word`() {
        // MT kept the source "K-" but the Turkish word starts with S
        assertEquals("S-seni şerefsiz!", st("K-kisama!", "K-seni şerefsiz!"))
    }

    @Test
    fun `a stuttered proper name keeps its capital`() {
        assertEquals("A-Akaishi-san?", st("A-Akaishi-san?", "Akaishi-san?"))
    }

    @Test
    fun `an already correct stutter is left stable`() {
        assertEquals("N-ne?", st("N-ne?", "N-ne?"))
    }

    @Test
    fun `turkish dotted i casing is handled`() {
        assertEquals("İ-iyi misin?", st("I-I'm fine?", "İyi misin?"))
    }

    @Test
    fun `no stutter in the source means no change`() {
        assertEquals("Merhaba", st("Hello", "Merhaba"))
    }

    @Test
    fun `stutter source with no translatable word is passed through`() {
        assertEquals("...", st("W-what?", "..."))
    }
}
