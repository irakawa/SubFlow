package com.subflow.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a subtitle track out of an MKV without parsing its index means ffmpeg seeks
 * around the container, and every byte it touches is a piece of the video pulled onto
 * the device. Until the index is parsed properly, a hard ceiling is what keeps that
 * bounded.
 */
class TorrentBudgetTest {

    @Test
    fun `the ceiling compares in bytes`() {
        assertFalse(TorrentSubtitle.overBudget(0))
        assertFalse(TorrentSubtitle.overBudget(TorrentSubtitle.MAX_DOWNLOAD_BYTES - 1))
        assertTrue(TorrentSubtitle.overBudget(TorrentSubtitle.MAX_DOWNLOAD_BYTES))
        assertTrue(TorrentSubtitle.overBudget(TorrentSubtitle.MAX_DOWNLOAD_BYTES * 2))
    }

    @Test
    fun `the ceiling is hundreds of megabytes, not hundreds of bytes`() {
        // a units slip here either disables the cap or aborts every single attempt,
        // and both fail quietly
        assertTrue(TorrentSubtitle.MAX_DOWNLOAD_BYTES > 64L * 1024 * 1024)
        assertTrue(TorrentSubtitle.MAX_DOWNLOAD_BYTES <= 512L * 1024 * 1024)
    }

    @Test
    fun `negative or missing counters never read as over budget`() {
        // libtorrent reports -1 before the handle produces real status
        assertFalse(TorrentSubtitle.overBudget(-1))
    }

    @Test
    fun `bytes are reported to the user in whole megabytes`() {
        assertEquals(256, TorrentSubtitle.megabytes(256L * 1024 * 1024))
        assertEquals(1, TorrentSubtitle.megabytes(1024L * 1024))
        assertEquals(0, TorrentSubtitle.megabytes(1023))
    }
}
