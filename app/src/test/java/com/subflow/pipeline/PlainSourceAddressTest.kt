package com.subflow.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SUBFLOW_LANGUAGE_RULES 3.2 on sources that carry no Japanese honorific — English
 * film, series and animation, which is most of what the app is pointed at.
 *
 * Every existing address test anchors on "-kun" or "sensei", and that is exactly where
 * the defect hid: the tracker only ever reported SINGLE when a honorific had been seen,
 * so GrammarFixer.fix returned untouched on every line of every non-anime file while
 * the suite stayed green. These tests go through the tracker end to end and never
 * construct an Addressee by hand.
 */
class PlainSourceAddressTest {

    // --- the tracker must resolve an addressee without a honorific ---

    @Test
    fun `a plain english line resolves to a single addressee`() {
        val t = SceneParticipantTracker()
        val a = t.next("Stay where you are.")
        // assumed, not shown: nothing in the line said how many people are listening
        assertEquals(Plurality.SINGLE_ASSUMED, a.plurality)
        // nothing said how formal the speaker is, and nothing should be invented
        assertEquals(Formality.UNKNOWN, a.formality)
    }

    @Test
    fun `a whole plain scene keeps resolving single`() {
        val t = SceneParticipantTracker()
        for (line in listOf("Don't move.", "Are you listening to me?", "Put it down.", "Now.")) {
            assertEquals(line, Plurality.SINGLE_ASSUMED, t.next(line).plurality)
        }
    }

    @Test
    fun `a stray plural ending is fixed end to end with no honorific in sight`() {
        val t = SceneParticipantTracker()
        assertEquals("Nasılsın?", GrammarFixer.fix("Nasılsınız?", t.next("How are you?")))
        assertEquals("Onu gördün", GrammarFixer.fix("Onu gördünüz", t.next("You saw him.")))
        assertEquals("Beni duydun mu?", GrammarFixer.fix("Beni duydunuz mu?", t.next("Did you hear me?")))
    }

    // --- an explicit group phrase must still win, honorific or not ---

    @Test
    fun `an explicit group phrase still suppresses the fix`() {
        val t = SceneParticipantTracker()
        val a = t.next("All of you, get out.")
        assertEquals(Plurality.GROUP, a.plurality)
        assertEquals("Hepiniz çıkın.", GrammarFixer.fix("Hepiniz çıkın.", a))
    }

    @Test
    fun `the group cue lingers for the window then releases`() {
        val t = SceneParticipantTracker()
        t.next("All of you, get out.")
        repeat(4) { assertEquals(Plurality.AMBIGUOUS, t.next("Move.").plurality) }
        assertEquals(Plurality.SINGLE_ASSUMED, t.next("Move.").plurality)
    }

    // --- "everyone" means two different things and only one of them is a group address ---

    @Test
    fun `everyone as a sentence subject is not a group address`() {
        // GrammarFixer leaves "herkes" alone for exactly this reason; the source side
        // has to read the word the same way or the two disagree about the same sentence
        assertFalse(AddresseeAnalyzer.isGroupAddress("Everyone left the building."))
        assertFalse(AddresseeAnalyzer.isGroupAddress("Is everybody okay?"))
        assertFalse(AddresseeAnalyzer.isGroupAddress("Everyone knows that."))
    }

    @Test
    fun `everyone spoken to a crowd is a group address`() {
        assertTrue(AddresseeAnalyzer.isGroupAddress("Everyone, calm down."))
        assertTrue(AddresseeAnalyzer.isGroupAddress("Listen up, everyone!"))
        assertTrue(AddresseeAnalyzer.isGroupAddress("Calm down, everybody."))
    }

    @Test
    fun `a narrated everyone does not block the next line's fix`() {
        val t = SceneParticipantTracker()
        t.next("Everyone left the building.")
        assertEquals("Yalnızsın", GrammarFixer.fix("Yalnızsınız", t.next("You are alone now.")))
    }
}
