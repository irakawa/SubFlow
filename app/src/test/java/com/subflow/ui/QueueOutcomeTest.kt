package com.subflow.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Running the offline queue.
 *
 * SearchQueue.clear() writes through to SharedPreferences, so it is not undoable, and
 * it was being called before the start that can refuse. A refused queue run therefore
 * deleted the queue: the searches the user was waiting to run were gone and nothing
 * had been run in their place.
 */
class QueueOutcomeTest {

    @Test
    fun `a refused run keeps the queue`() {
        var cleared = false
        val outcome = queueOutcome(
            busy = true, online = true,
            start = { true },
            clear = { cleared = true }
        )
        assertEquals(SearchStart.REFUSED_BUSY, outcome)
        assertFalse("the queue was deleted for a run that never started", cleared)
    }

    @Test
    fun `a run the pipeline refuses at the last moment keeps the queue`() {
        var cleared = false
        val outcome = queueOutcome(
            busy = false, online = true,
            start = { false },
            clear = { cleared = true }
        )
        assertEquals(SearchStart.REFUSED_BUSY, outcome)
        assertFalse(cleared)
    }

    @Test
    fun `being offline keeps the queue`() {
        var cleared = false
        val outcome = queueOutcome(
            busy = false, online = false,
            start = { true },
            clear = { cleared = true }
        )
        assertEquals(SearchStart.QUEUED_OFFLINE, outcome)
        assertFalse(cleared)
    }

    @Test
    fun `the queue is dropped only once a run is under way`() {
        var cleared = false
        val outcome = queueOutcome(
            busy = false, online = true,
            start = { true },
            clear = { cleared = true }
        )
        assertEquals(SearchStart.STARTED, outcome)
        assertTrue(cleared)
    }

    @Test
    fun `the pipeline is not asked while busy`() {
        var asked = false
        queueOutcome(busy = true, online = true, start = { asked = true; true }, clear = {})
        assertFalse(asked)
    }
}
