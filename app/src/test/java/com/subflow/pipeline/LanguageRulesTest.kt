package com.subflow.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * covers the uncensored-tone (1), stutter (2.1) and stray "mı/mi" (3.3) rules
 * from SUBFLOW_LANGUAGE_RULES.
 */
class LanguageRulesTest {

    // --- 1: the tone claim must be measured, never assumed ---

    @Test
    fun `a clean line claims no tone hardening`() {
        val post = PostProcessor("tr")
        val out = post.processBatch(listOf("Hello there, friend."), listOf("Merhaba dostum."))
        assertFalse(out.toneHardened)
    }

    @Test
    fun `a softened line reports the hardening it actually applied`() {
        val post = PostProcessor("tr")
        // "kötü adam" is registered as the soft form of "şerefsiz", so only a source that
        // really used that term may claim the slot
        val out = post.processBatch(listOf("That motherfucker!"), listOf("O kötü adam!"))
        assertEquals("O şerefsiz!", out.lines[0])
        assertTrue(out.toneHardened)
    }

    @Test
    fun `a marker bound to another term is left alone`() {
        val post = PostProcessor("tr")
        // "kötü adam" softens "motherfucker", not "son of a bitch". overwriting it with
        // "orospu çocuğu" would be the same misattribution in a nicer disguise
        val out = post.processBatch(listOf("You son of a bitch!"), listOf("Seni kötü adam!"))
        assertEquals("Seni kötü adam!", out.lines[0])
        assertFalse(out.toneHardened)
    }

    @Test
    fun `a weak slot is never filled with an unrelated source term`() {
        val post = PostProcessor("tr")
        // "Tanrım" is the correct rendering of "Oh my God", not a softened "fucking".
        // Filling it with the swearword's equivalent both invents profanity where there
        // was none and still loses the one that was actually dropped.
        val out = post.processBatch(
            listOf("Oh my God, that fucking hurts!"),
            listOf("Aman Tanrım, bu çok acıyor!")
        )
        assertEquals("Aman Tanrım, bu çok acıyor!", out.lines[0])
        assertFalse(out.toneHardened)
    }

    // --- 1: sanitize detection must not depend on a known soft marker ---

    @Test
    fun `sanitizing is detected even when no soft marker was left behind`() {
        // MT dropped the profanity outright instead of softening it into a known
        // marker, so the marker-based reading used to miss this entirely
        assertTrue(SlangDictionary.looksSanitized("That fucking bastard!", "Kahrolası herif!"))
        assertTrue(SlangDictionary.looksSanitized("Fuck you!", "Git buradan!"))
    }

    @Test
    fun `a line that kept its register is not called sanitized`() {
        assertTrue(!SlangDictionary.looksSanitized("You son of a bitch!", "Seni orospu çocuğu!"))
    }

    @Test
    fun `a clean source is never called sanitized`() {
        assertFalse(SlangDictionary.looksSanitized("Good morning.", "Günaydın."))
    }

    @Test
    fun `an innocent word is not mistaken for harsh language`() {
        // every one of these merely contains "lan" or "göt". reading them as harsh
        // silently closes the gate on exactly the lines the repair exists for.
        assertTrue(SlangDictionary.looksSanitized("Fuck this!", "Bu koca bir yalan!"))
        assertTrue(SlangDictionary.looksSanitized("Fuck this!", "Varsayılan plan buydu."))
        assertTrue(SlangDictionary.looksSanitized("Fuck this!", "Onu içeri götürdü."))
        assertTrue(SlangDictionary.looksSanitized("Fuck this!", "Atlanır böyle şeyler."))
    }

    @Test
    fun `a genuinely harsh line still counts as register preserved`() {
        assertFalse(SlangDictionary.looksSanitized("Fuck this!", "Siktir git!"))
        assertFalse(SlangDictionary.looksSanitized("Fuck this!", "Gel buraya lan!"))
        assertFalse(SlangDictionary.looksSanitized("Fuck this!", "Boktan bir durum."))
        // "lanet" is the dictionary's own rendering for "goddamn", so it is harsh here,
        // never a soft marker
        assertFalse(SlangDictionary.looksSanitized("Goddamn it!", "Lanet olsun!"))
    }

    @Test
    fun `the dictionary now reaches a line that carries only a soft marker`() {
        val post = PostProcessor("tr")
        // "kahrolası" is the soft form of "lanet olası", which is what "goddamn" renders as.
        // a repaired cue must still start with a capital: the repair runs before the
        // grammar pass, not after it.
        val out = post.processBatch(listOf("That goddamn bastard!"), listOf("Kahrolası herif!"))
        assertEquals("Lanet olası herif!", out.lines[0])
        assertTrue(out.toneHardened)
    }

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

    // --- 6: a name stays the same name for the whole episode ---

    @Test
    fun `a name learned in an earlier cue keeps its capital later`() {
        val post = PostProcessor("tr")
        post.processBatch(listOf("Akaishi is here."), listOf("Akaishi burada."))
        // this cue never names her, so only the remembered name can restore the capital
        val out = post.processBatch(listOf("She left with him."), listOf("Sonra akaishi gitti."))
        assertEquals("Sonra Akaishi gitti.", out.lines[0])
    }

    @Test
    fun `a remembered name does not bleed into a longer word`() {
        val post = PostProcessor("tr")
        post.processBatch(listOf("Kan is waiting."), listOf("Kan bekliyor."))
        // "kanepe" and "kanıyor" merely start with the name. the turkish suffix matters:
        // an ascii word boundary treats "ı" as a non-word char and would match here.
        val out = post.processBatch(listOf("It hurts."), listOf("Sonra kanepe devrildi ve kanıyor."))
        assertEquals("Sonra kanepe devrildi ve kanıyor.", out.lines[0])
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
