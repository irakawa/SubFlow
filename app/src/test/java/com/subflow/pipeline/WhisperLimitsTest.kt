package com.subflow.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcriber allocates one float per sample. Nothing bounded that, so a long
 * enough film asked for more native heap than the process had and took the whole app
 * down. The budget check has to happen before a single byte is allocated.
 */
class WhisperLimitsTest {

    private fun wavBytesFor(minutes: Int): Long = 44L + 16_000L * 60 * minutes * 2

    @Test
    fun `sample count comes from the pcm payload, not the file size`() {
        assertEquals(16_000, WhisperEngine.samplesInWav(44L + 16_000L * 2))
        assertEquals(0, WhisperEngine.samplesInWav(44L))
    }

    @Test
    fun `a truncated file reports no samples instead of a negative count`() {
        assertEquals(0, WhisperEngine.samplesInWav(20L))
        assertEquals(0, WhisperEngine.samplesInWav(0L))
    }

    @Test
    fun `audio inside the budget is accepted`() {
        val budget = 16_000 * 60 * 60 // one hour of samples
        assertFalse(WhisperEngine.exceedsBudget(wavBytesFor(30), budget))
        assertFalse(WhisperEngine.exceedsBudget(wavBytesFor(60), budget))
    }

    @Test
    fun `audio past the budget is refused`() {
        val budget = 16_000 * 60 * 60
        assertTrue(WhisperEngine.exceedsBudget(wavBytesFor(61), budget))
        // the case that killed the process: a two hour film
        assertTrue(WhisperEngine.exceedsBudget(wavBytesFor(120), budget))
    }

    @Test
    fun `the budget is reported in minutes so the refusal can say why`() {
        assertEquals(60, WhisperEngine.minutesForSamples(16_000 * 60 * 60))
        assertEquals(26, WhisperEngine.minutesForSamples(25_165_824)) // low tier budget
    }
}
