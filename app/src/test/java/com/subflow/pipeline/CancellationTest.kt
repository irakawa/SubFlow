package com.subflow.pipeline

import com.subflow.models.ContentType
import com.subflow.models.Release
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cancelling a search must stay a cancellation all the way down. Catching it as an
 * ordinary failure makes the pipeline report invented errors, and worse, lets a
 * torn-down search write its empty result into a cache that outlives it.
 */
class CancellationTest {

    private fun cancels(block: suspend () -> Unit): Boolean = try {
        runBlocking { block() }
        false
    } catch (e: CancellationException) {
        true
    }

    // --- translation providers ---

    @Test
    fun `a cancelled provider call is rethrown`() {
        assertTrue(cancels { TranslationEngine.attempt { throw CancellationException("cancelled") } })
    }

    @Test
    fun `an ordinary provider failure is still reported as null`() {
        val out = runBlocking { TranslationEngine.attempt { throw IOException("host down") } }
        assertNull(out)
    }

    // --- alternate titles ---

    @Test
    fun `a cancelled alt title lookup is rethrown and leaves no cache entry`() {
        val release = Release(title = "Cancel Probe", type = ContentType.SERIES)
        assertTrue(cancels { AltTitles.resolveWith(release) { throw CancellationException("cancelled") } })
        // the cancelled run must not have decided this title has no alternates
        val later = runBlocking { AltTitles.resolveWith(release) { listOf("Alternate Name") } }
        assertEquals(listOf("Alternate Name"), later)
    }

    @Test
    fun `a failed alt title lookup leaves no cache entry`() {
        val release = Release(title = "Failure Probe", type = ContentType.SERIES)
        val first = runBlocking { AltTitles.resolveWith(release) { throw IOException("host down") } }
        assertTrue(first.isEmpty())
        val second = runBlocking { AltTitles.resolveWith(release) { listOf("Alternate Name") } }
        assertEquals(listOf("Alternate Name"), second)
    }

    @Test
    fun `a genuine empty result is cached`() {
        val release = Release(title = "Empty Probe", type = ContentType.SERIES)
        assertTrue(runBlocking { AltTitles.resolveWith(release) { emptyList() } }.isEmpty())
        // a real "this show has no alternates" answer should not be looked up again
        var calledAgain = false
        runBlocking { AltTitles.resolveWith(release) { calledAgain = true; listOf("Alternate Name") } }
        assertTrue(!calledAgain)
    }
}
