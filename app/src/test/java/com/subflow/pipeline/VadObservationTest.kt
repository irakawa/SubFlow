package com.subflow.pipeline

import com.subflow.models.SubtitleResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VAD is an observer until its confidence statistic is fixed.
 *
 * overlapScore divides by the subtitle's own speech frames and never counts the audio
 * side, so its floor is the audio's speech density rather than zero, and align() takes
 * the maximum over ~18000 offsets against a fixed 0.55 gate. Measured on a 24-minute
 * timeline, a completely unrelated subtitle scores 0.595 at density 0.54 and 0.892 at
 * density 0.88 — the gate is a gate on density, not on agreement.
 *
 * So a reading must not rewrite timings and must not retract the one honest warning
 * the pipeline produces. It may still nudge the score, which is bounded to +1..+10.
 */
class VadObservationTest {

    private fun result(
        warning: String? = "tags mismatch, verify",
        score: Int = Quality.HUMAN,
        episodeVerified: Boolean = true
    ) = SubtitleResult(
        fileName = "ep.srt",
        content = "1\n00:00:01,000 --> 00:00:02,000\nmerhaba\n\n",
        sourceName = "test",
        method = "human",
        sizeBytes = 42,
        episodeLabel = "S01E01",
        syncWarning = warning,
        qualityScore = score,
        episodeVerified = episodeVerified
    )

    @Test
    fun `an observation never rewrites the timings`() {
        val before = result()
        val after = PipelineRunner.withVadObservation(before, confPct = 92)
        assertEquals(before.content, after.content)
    }

    @Test
    fun `an observation never retracts the sync warning`() {
        val before = result(warning = "Release tags mismatch — verify")
        val after = PipelineRunner.withVadObservation(before, confPct = 92)
        assertNotNull(after.syncWarning)
        assertEquals(before.syncWarning, after.syncWarning)
    }

    @Test
    fun `a result that had no warning still has none`() {
        val after = PipelineRunner.withVadObservation(result(warning = null), confPct = 60)
        assertEquals(null, after.syncWarning)
    }

    @Test
    fun `the bounded score nudge survives`() {
        val after = PipelineRunner.withVadObservation(result(score = Quality.HUMAN), confPct = 100)
        assertEquals(Quality.withSync(Quality.HUMAN, 100), after.qualityScore)
        assertTrue(after.qualityScore > Quality.HUMAN)
    }

    @Test
    fun `an unconfirmed episode is still not lifted`() {
        val before = result(score = Quality.UNVERIFIED_EPISODE, episodeVerified = false)
        val after = PipelineRunner.withVadObservation(before, confPct = 100)
        assertEquals(Quality.UNVERIFIED_EPISODE, after.qualityScore)
    }
}
