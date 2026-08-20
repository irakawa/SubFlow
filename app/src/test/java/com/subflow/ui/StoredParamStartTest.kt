package com.subflow.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A refused search must cost the user nothing.
 *
 * The stored-parameter entry points — continue watching, next episode of a followed
 * show, re-run from history — overwrote the input form and dropped the picked video
 * before asking whether a run could start at all. When the pipeline then refused, the
 * user's half-typed search was already gone and nothing had happened in its place.
 */
class StoredParamStartTest {

    @Test
    fun `a refused start never touches the form`() {
        var formWritten = false
        val outcome = startFromStoredParams(
            busy = true,
            applyForm = { formWritten = true },
            start = { SearchStart.STARTED }
        )
        assertEquals(SearchStart.REFUSED_BUSY, outcome)
        assertFalse("the form was cleared for a search that never ran", formWritten)
    }

    @Test
    fun `a free pipeline fills the form and starts`() {
        var formWritten = false
        val outcome = startFromStoredParams(
            busy = false,
            applyForm = { formWritten = true },
            start = { SearchStart.STARTED }
        )
        assertEquals(SearchStart.STARTED, outcome)
        assertTrue(formWritten)
    }

    @Test
    fun `the form is filled before the search is attempted`() {
        // order matters: the search reads what applyForm wrote
        val calls = mutableListOf<String>()
        startFromStoredParams(
            busy = false,
            applyForm = { calls += "form" },
            start = { calls += "start"; SearchStart.STARTED }
        )
        assertEquals(listOf("form", "start"), calls)
    }

    @Test
    fun `an outcome other than started is passed straight through`() {
        assertEquals(
            SearchStart.QUEUED_OFFLINE,
            startFromStoredParams(busy = false, applyForm = {}, start = { SearchStart.QUEUED_OFFLINE })
        )
    }
}
