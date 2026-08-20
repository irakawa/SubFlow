package com.subflow.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a start attempt reports back.
 *
 * The defect was never in PipelineRunner's guard — it always refused correctly. It was
 * that the refusal was thrown away on the way out: startPipeline() returned a constant
 * true, so the UI opened the progress screen on a run that had not started. A test on
 * the guard alone would have stayed green through all of it, so this one exercises the
 * path the answer travels.
 */
class StartOutcomeTest {

    @Test
    fun `a refusal from the runner is reported as a refusal`() {
        // the whole defect in one assertion: the runner said no and that has to survive
        assertEquals(SearchStart.REFUSED_BUSY, startOutcome(online = true) { false })
    }

    @Test
    fun `an accepted run is reported as started`() {
        assertEquals(SearchStart.STARTED, startOutcome(online = true) { true })
    }

    @Test
    fun `being offline is queued, not started and not refused`() {
        assertEquals(SearchStart.QUEUED_OFFLINE, startOutcome(online = false) { true })
    }

    @Test
    fun `the runner is not even asked while offline`() {
        var asked = false
        startOutcome(online = false) { asked = true; true }
        assertEquals(false, asked)
    }

    @Test
    fun `only a started run opens the progress screen`() {
        assertEquals(true, SearchStart.STARTED.opensProgress)
        assertEquals(false, SearchStart.REFUSED_BUSY.opensProgress)
        assertEquals(false, SearchStart.QUEUED_OFFLINE.opensProgress)
    }
}
