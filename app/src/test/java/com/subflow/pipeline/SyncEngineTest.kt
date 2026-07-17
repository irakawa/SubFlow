package com.subflow.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {

    private val srt = """
        1
        00:00:01,000 --> 00:00:03,000
        First line

        2
        00:00:04,500 --> 00:00:06,000
        Second line
    """.trimIndent()

    @Test
    fun `parseSrt reads cues and timings`() {
        val cues = SyncEngine.parseSrt(srt)
        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(3000L, cues[0].endMs)
        assertEquals("First line", cues[0].text)
        assertEquals(4500L, cues[1].startMs)
    }

    @Test
    fun `render then parse round-trips`() {
        val cues = SyncEngine.parseSrt(srt)
        val reparsed = SyncEngine.parseSrt(SyncEngine.renderSrt(cues))
        assertEquals(cues.map { it.startMs to it.text }, reparsed.map { it.startMs to it.text })
    }

    @Test
    fun `positive shift moves timings later`() {
        val shifted = SyncEngine.parseSrt(SyncEngine.shift(srt, 500))
        assertEquals(1500L, shifted[0].startMs)
        assertEquals(3500L, shifted[0].endMs)
    }

    @Test
    fun `shift never produces a negative start`() {
        val shifted = SyncEngine.parseSrt(SyncEngine.shift(srt, -5000))
        assertTrue(shifted.all { it.startMs >= 0 })
    }
}
