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

    // --- "everyone" is a crowd whether or not the vocative comma survived ---

    @Test
    fun `everyone counts with or without the vocative comma`() {
        // subtitles drop the vocative comma constantly, and hanging a plural reading on
        // punctuation meant "Everyone calm down" was read as one addressee
        assertTrue(AddresseeAnalyzer.isGroupAddress("Everyone, calm down."))
        assertTrue(AddresseeAnalyzer.isGroupAddress("Everyone calm down."))
        assertTrue(AddresseeAnalyzer.isGroupAddress("Listen up, everyone!"))
        assertTrue(AddresseeAnalyzer.isGroupAddress("Calm down, everybody."))
    }

    @Test
    fun `everyone asked as a question is still a crowd`() {
        // asked of a room, this is a group address; the old test asserted the opposite
        assertTrue(AddresseeAnalyzer.isGroupAddress("Is everybody okay?"))
        assertTrue(AddresseeAnalyzer.isGroupAddress("Everyone knows that."))
    }

    @Test
    fun `a group reading only ever costs a repair, never a line`() {
        // the liberal reading is safe precisely because a group cue can only stop a
        // rewrite. the line comes through untouched, not mangled.
        val t = SceneParticipantTracker()
        t.next("Everyone left the building.")
        assertEquals("Yalnızsınız", GrammarFixer.fix("Yalnızsınız", t.next("You are alone now.")))
    }
}
