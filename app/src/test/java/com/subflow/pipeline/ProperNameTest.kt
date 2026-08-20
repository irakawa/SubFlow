package com.subflow.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * restoreProperNames capitalises a word in the Turkish line because the English source
 * used it as a name. Plenty of English names are also ordinary Turkish words — Ben, Can,
 * Ada, Kan, Deniz — and once such a character appears the rule fires on every later cue
 * that happens to use the Turkish word. "Sonra ben gittim" becoming "Sonra Ben gittim"
 * is a defect the viewer sees on every line, from one correct capitalisation.
 */
class ProperNameTest {

    @Test
    fun `a name that is also a turkish pronoun does not capitalise the pronoun`() {
        val post = PostProcessor("tr")
        // the character really is called Ben, and the first cue is right to keep it
        assertEquals("Ben burada.", post.processBatch(listOf("Ben is here."), listOf("Ben burada."))[0])
        // this cue uses the ordinary Turkish word for "I"
        val out = post.processBatch(listOf("Then I left."), listOf("Sonra ben gittim."))
        assertEquals("Sonra ben gittim.", out[0])
    }

    @Test
    fun `common turkish nouns are not turned into names`() {
        val post = PostProcessor("tr")
        post.processBatch(
            listOf("Ada and Can went to the sea.", "Kaya is late."),
            listOf("Ada ve Can denize gitti.", "Kaya geç kaldı.")
        )
        val out = post.processBatch(
            listOf("There is an island over there.", "It hurts."),
            listOf("Orada bir ada var.", "Kan akıyor ve kaya düştü.")
        )
        assertEquals("Orada bir ada var.", out[0])
        assertEquals("Kan akıyor ve kaya düştü.", out[1])
    }

    @Test
    fun `a name with no turkish meaning is still restored`() {
        // the rule has to keep working: this is the whole point of remembering names
        val post = PostProcessor("tr")
        post.processBatch(listOf("Akaishi is here."), listOf("Akaishi burada."))
        val out = post.processBatch(listOf("She left with him."), listOf("Sonra akaishi gitti."))
        assertEquals("Sonra Akaishi gitti.", out[0])
    }

    @Test
    fun `a colliding name gives up its restoration rather than corrupting every line`() {
        // the deliberate trade: when a name is also an ordinary Turkish word we stop
        // restoring it at all. Missing one capital on a name MT lowercased costs one
        // line; capitalising the ordinary word costs every line that uses it, and the
        // ordinary word is far more common than the character.
        val post = PostProcessor("tr")
        post.processBatch(listOf("Kaya is late."), listOf("Kaya geç kaldı."))
        val out = post.processBatch(listOf("Kaya left."), listOf("kaya gitti."))
        assertEquals("Kaya gitti.", out[0]) // sentence-initial capital, not name restoration
    }

    @Test
    fun `the rule is turkish-only, other targets keep the plain behaviour`() {
        // the collision list is a fact about Turkish; a German result has no reason to
        // lose a name restoration over it (SUBFLOW_LANGUAGE_RULES 8.1)
        val post = PostProcessor("de")
        post.processBatch(listOf("Ben is here."), listOf("Ben ist hier."))
        val out = post.processBatch(listOf("He left."), listOf("Dann ging ben."))
        assertEquals("Dann ging Ben.", out[0])
    }
}

/** processBatch returns a BatchResult; the tests only care about the lines */
private operator fun PostProcessor.BatchResult.get(i: Int): String = lines[i]
