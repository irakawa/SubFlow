package com.subflow.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The imperative rule cuts a stem off a Turkish word. Every case where it fires on a
 * noun instead of a verb is a corrupted line, so each guard is tested on a case that
 * only that guard catches — a case two guards cover proves nothing about either.
 */
class ImperativeGuardTest {

    private fun fix(
        source: String,
        translated: String,
        lang: String = "en",
        t: SceneParticipantTracker = SceneParticipantTracker()
    ): String {
        val a = t.next(source)
        return GrammarFixer.fixPluralImperative(source, lang, GrammarFixer.fix(translated, a), a)
    }

    // --- guard 1: the source says "your", so the -ınız is a possessive ---

    @Test
    fun `a source that says your never triggers the rule`() {
        // "anahtarınız" is your key, not an order to key something
        assertEquals("İşte anahtarınız.", fix("Here is your key.", "İşte anahtarınız."))
        assertEquals("Telefonunuz çalıyor.", fix("Your phone is ringing.", "Telefonunuz çalıyor."))
        assertEquals("Bu sizin evinizdir.", fix("This is yours.", "Bu sizin evinizdir."))
    }

    // --- guard 2: only a real imperative verb opens an order ---

    @Test
    fun `a line opening with a name is not an order`() {
        // the old blacklist let every unlisted opener through, so "Ahmet," was an order
        assertEquals(
            "Ahmet, telefonunuz çalıyor.",
            fix("Ahmet, the phone is ringing.", "Ahmet, telefonunuz çalıyor.")
        )
    }

    @Test
    fun `unlisted openers no longer count as orders`() {
        assertFalse(GrammarFixer.looksImperative("Here is the key."))
        assertFalse(GrammarFixer.looksImperative("Ahmet, the phone is ringing."))
        assertFalse(GrammarFixer.looksImperative("Nobody moves."))
        assertFalse(GrammarFixer.looksImperative("Suddenly everything stopped."))
    }

    @Test
    fun `real imperative verbs still count`() {
        assertTrue(GrammarFixer.looksImperative("Stay where you are."))
        assertTrue(GrammarFixer.looksImperative("Please wait."))
        assertTrue(GrammarFixer.looksImperative("Don't move."))
        assertTrue(GrammarFixer.looksImperative("Listen to me."))
        assertTrue(GrammarFixer.looksImperative("Sit down."))
    }

    // --- guard 3: the whitelist is English, so anything else is off ---

    @Test
    fun `a japanese source never reads as an english order`() {
        // no token matches an english list, so the blacklist called every line an order.
        // JA to TR is a first-class path for this app.
        assertFalse(GrammarFixer.looksImperative("ここにいてください。"))
        assertFalse(GrammarFixer.looksImperative("動くな。"))
        assertFalse(GrammarFixer.looksImperative("お前の名前は。"))
    }

    @Test
    fun `a non-english source turns the rule off outright`() {
        // even if the turkish line looks like an imperative, nothing verified the source
        assertEquals("Yerinde kalınız.", fix("ここにいてください。", "Yerinde kalınız.", lang = "ja"))
        assertEquals("Yerinde kalınız.", fix("Bleib wo du bist.", "Yerinde kalınız.", lang = "de"))
    }

    // --- guard 4: only the predicate position, not every word on the line ---

    @Test
    fun `only the last word can be the verb`() {
        // "anahtarınız" is a subject here and must survive; "bekleyiniz" is the predicate
        assertEquals(
            "Anahtarınız burada, bekle.",
            fix("Wait here, please.", "Anahtarınız burada, bekleyiniz.")
        )
    }

    @Test
    fun `nouns that are not in predicate position are untouched`() {
        assertEquals(
            "Oğlunuz burada bekliyor.",
            fix("Wait for me here.", "Oğlunuz burada bekliyor.")
        )
    }

    // --- guard 5: the stem list has to answer "is this a verb", not "is this a string" ---

    @Test
    fun `an ambiguous word with no matching source verb is left alone`() {
        // both parses are real Turkish: "sor|unuz" is an order, "soru|nuz" is your
        // question. The source below orders something, but not this — nothing in it
        // says which parse the Turkish is, so the rule declines.
        val t = SceneParticipantTracker()
        assertEquals("Bu sizin sorunuz.", fix("Look at this.", "Bu sizin sorunuz.", t = t))
        assertEquals("Bulanık görünüz.", fix("Wait here.", "Bulanık görünüz.", t = t))
        assertEquals("Büyümüş sürünüz.", fix("Stop.", "Büyümüş sürünüz.", t = t))
        assertEquals("İlginç gösteriniz.", fix("Come here.", "İlginç gösteriniz.", t = t))
        assertEquals("Eksik veriniz.", fix("Listen to me.", "Eksik veriniz.", t = t))
        assertEquals("Güzel düşünüz.", fix("Wait.", "Güzel düşünüz.", t = t))
    }

    @Test
    fun `an ambiguous word the source names as a verb is repaired`() {
        // the evidence was there all along: the source is English and opens with the
        // very verb whose Turkish stem is in question, so the verb parse is the one
        // that produced this line. Refusing these outright cost seven common orders.
        val t = SceneParticipantTracker()
        assertEquals("Sor.", fix("Ask.", "Sorunuz.", t = t))
        assertEquals("Çok güzel yaz.", fix("Write it well.", "Çok güzel yazınız.", t = t))
        assertEquals("Bana göster.", fix("Show me.", "Bana gösteriniz.", t = t))
        assertEquals("Bana ver.", fix("Give it to me.", "Bana veriniz.", t = t))
        assertEquals("Şuna bir gör.", fix("See this.", "Şuna bir görünüz.", t = t))
    }

    @Test
    fun `helper-verb nouns never form an imperative on their own`() {
        // "yardım" is a noun; the order is "yardım et". None of these can be the verb,
        // so listing them as stems was risk with nothing on the other side of it.
        //
        // All three sources open with a verb that IS on the imperative list, and all
        // three Turkish stems WERE on the removed list, so putting any of them back
        // fails this. Two earlier cases proved nothing: "izniniz" yields the stem
        // "izn" which was never listed, and "Light it." does not open with a listed
        // verb, so neither could ever have reached the stem check.
        val t = SceneParticipantTracker()
        assertEquals("Acil yardımınız.", fix("Help me.", "Acil yardımınız.", t = t))
        assertEquals("Bu sizin ateşiniz.", fix("Fire.", "Bu sizin ateşiniz.", t = t))
        assertEquals("Bu sizin nişanınız.", fix("Aim.", "Bu sizin nişanınız.", t = t))
    }

    // --- the fix this rule exists for still works ---

    @Test
    fun `the reported line is still repaired`() {
        assertEquals("Yerinde kal.", fix("Stay where you are.", "Yerinde kalınız."))
        assertEquals("Lütfen bekle.", fix("Please wait.", "Lütfen bekleyiniz."))
        assertEquals("Beni dinle.", fix("Listen to me.", "Beni dinleyiniz."))
    }
}
