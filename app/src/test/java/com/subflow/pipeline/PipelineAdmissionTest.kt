package com.subflow.pipeline

import com.subflow.models.PipelineStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a new search is actually taken on.
 *
 * The pipeline refuses a second run while one is in flight, which is correct — but the
 * refusal used to be silent: startPipeline() returned true regardless, the UI navigated
 * to the progress screen, and the user watched the previous search's log and was handed
 * the previous search's subtitle. A refusal has to be reportable, so it lives in one
 * function that the guard and the callers both read.
 */
class PipelineAdmissionTest {

    @Test
    fun `a run in flight refuses a new one`() {
        assertFalse(PipelineRunner.accepts(PipelineStatus.RUNNING, 1))
        assertFalse(PipelineRunner.accepts(PipelineStatus.RUNNING, 12))
    }

    @Test
    fun `any settled state accepts a new run`() {
        for (s in listOf(
            PipelineStatus.IDLE, PipelineStatus.DONE,
            PipelineStatus.FAILED, PipelineStatus.CANCELLED
        )) {
            assertTrue(s.name, PipelineRunner.accepts(s, 1))
        }
    }

    @Test
    fun `nothing to search is also a refusal`() {
        // the other silent-return path: an empty batch never started anything either
        assertFalse(PipelineRunner.accepts(PipelineStatus.IDLE, 0))
        assertFalse(PipelineRunner.accepts(PipelineStatus.DONE, 0))
    }
}
