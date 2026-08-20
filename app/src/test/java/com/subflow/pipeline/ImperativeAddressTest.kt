package com.subflow.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SUBFLOW_LANGUAGE_RULES 3.2 for the Turkish second-person plural *imperative*.
 *
 * The predicate rule ("Nasılsınız" -> "Nasılsın") strips a plural marker off a
 * conjugated form and is correct there. Imperatives are built differently — the suffix
 * hangs off the bare stem — so the same rule neither matched "kalınız" nor could have
 * produced "kal" from it. Sources here carry no honorific, and the addressee always
 * comes out of the tracker.
 */
class ImperativeAddressTest {

    private fun fix(source: String, translated: String, t: SceneParticipantTracker): String {
        val a = t.next(source)
        return GrammarFixer.fixPluralImperative(source, "en", GrammarFixer.fix(translated, a), a)
    }

    // --- the reported defect ---

    @Test
    fun `a polite plural imperative comes back singular`() {
        val t = SceneParticipantTracker()
        assertEquals("Yerinde kal.", fix("Stay where you are.", "Yerinde kalınız.", t))
        assertEquals("Lütfen bekle.", fix("Please wait.", "Lütfen bekleyiniz.", t))
        assertEquals("Buraya gel.", fix("Come here.", "Buraya geliniz.", t))
        assertEquals("Otur.", fix("Sit down.", "Oturunuz.", t))
        assertEquals("Buna bak.", fix("Look at this.", "Buna bakınız.", t))
        assertEquals("Beni dinle.", fix("Listen to me.", "Beni dinleyiniz.", t))
    }

    // --- what it must refuse to touch ---

    @Test
    fun `a possessive noun is never mistaken for an imperative`() {
        // "-ınız" is also the second-person-plural possessive: kitabınız = your book.
        // the source is what tells them apart, and neither of these orders anyone about.
        val t = SceneParticipantTracker()
        assertEquals("Kitabınız burada.", fix("Your book is here.", "Kitabınız burada.", t))
        assertEquals("Eviniz nerede?", fix("Where is your house?", "Eviniz nerede?", t))
        assertEquals("Gözünüz kanıyor.", fix("Your eye is bleeding.", "Gözünüz kanıyor.", t))
    }

    @Test
    fun `a stem we cannot recover is left alone instead of guessed`() {
        // "gidiniz" would strip to "gid", which is not a word: the real stem is "git",
        // and reversing Turkish consonant softening is not something to guess at.
        val t = SceneParticipantTracker()
        val out = fix("Please leave.", "Lütfen gidiniz.", t)
        assertEquals("Lütfen gidin.", out) // predicate rule's doing; never "gid"
    }

    @Test
    fun `a two letter stem is left alone`() {
        // "yiyiniz" strips to "yi", but the stem is "ye" — irregular, not recoverable
        val t = SceneParticipantTracker()
        assertEquals("Bunu yiyiniz.", fix("Eat this.", "Bunu yiyiniz.", t))
    }

    // --- register and plurality still decide ---

    @Test
    fun `a formal addressee keeps the polite imperative`() {
        val t = SceneParticipantTracker()
        assertEquals("Lütfen bekleyiniz.", fix("Please wait, sensei.", "Lütfen bekleyiniz.", t))
    }

    @Test
    fun `a group addressee keeps the plural imperative`() {
        val t = SceneParticipantTracker()
        assertEquals("Lütfen bekleyiniz.", fix("All of you, please wait.", "Lütfen bekleyiniz.", t))
    }

    // --- the predicate rule must survive untouched ---

    @Test
    fun `predicate forms still singularize`() {
        val t = SceneParticipantTracker()
        assertEquals("Nasılsın?", fix("How are you?", "Nasılsınız?", t))
        assertEquals("Onu gördün", fix("You saw him.", "Onu gördünüz", t))
        assertEquals("Çok naziksin", fix("You are very kind.", "Çok naziksiniz", t))
    }

    @Test
    fun `lookalike nouns are still safe`() {
        val t = SceneParticipantTracker()
        assertEquals("Deniz yalnız kaldı", fix("Deniz was left alone.", "Deniz yalnız kaldı", t))
        assertEquals("Yıldızları gördüm", fix("I saw the stars.", "Yıldızları gördüm", t))
    }
}
