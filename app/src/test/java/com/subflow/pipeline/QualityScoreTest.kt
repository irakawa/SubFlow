package com.subflow.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The score is the only thing most users read before opening a file, so a wrong
 * number is the same defect class as a wrong badge: an unearned claim.
 */
class QualityScoreTest {

    @Test
    fun `sync confidence cannot lift a partial file into the delivered band`() {
        // a file that scored low for a reason of its own must not be rescued by good
        // timing — aligning cues does not translate the ones left in English
        assertEquals(55, Quality.withSync(45, 100))
        assertTrue(Quality.withSync(45, 100) < Quality.FLOOR)
    }

    @Test
    fun `a fully translated file keeps its base score`() {
        assertEquals(Quality.TRANSLATED, Quality.withUntranslated(Quality.TRANSLATED, 0))
    }

    @Test
    fun `a heavily untranslated file cannot score as first class`() {
        val score = Quality.withUntranslated(Quality.TRANSLATED, 40)
        assertEquals(45, score)
        // the whole point: 40% english must not read as a delivered result
        assertTrue(score < Quality.FLOOR)
    }

    @Test
    fun `the penalty scales with how much was left behind`() {
        assertEquals(80, Quality.withUntranslated(Quality.TRANSLATED, 5))
        assertEquals(65, Quality.withUntranslated(Quality.TRANSLATED, 20))
        assertEquals(15, Quality.withUntranslated(Quality.TRANSLATED, 70))
    }

    @Test
    fun `the untranslated share never rounds down to nothing`() {
        // one cue in two hundred is still a cue the user will hit
        assertEquals(1, Quality.untranslatedPercent(1, 200))
        assertEquals(0, Quality.untranslatedPercent(0, 200))
        assertEquals(100, Quality.untranslatedPercent(200, 200))
        assertEquals(40, Quality.untranslatedPercent(80, 200))
    }

    @Test
    fun `an empty cue list is not a division by zero`() {
        assertEquals(0, Quality.untranslatedPercent(0, 0))
    }

    @Test
    fun `sync still floors a fully delivered result as before`() {
        assertEquals(75, Quality.withSync(Quality.TRANSLATED, 0))
        assertEquals(95, Quality.withSync(Quality.TRANSLATED, 100))
        assertEquals(Quality.FLOOR, Quality.withSync(Quality.FLOOR, 0))
    }
}
