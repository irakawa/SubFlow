package com.subflow.pipeline

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens to a batch when the provider does not give a token back.
 *
 * The lines that came back clean are finished work. Throwing all twenty-five away
 * because one of them lost a token would make this feature worse than not having it:
 * before, the file had a mangled honorific; that version could ship twenty-five cues
 * in the source language instead.
 */
class UnmaskBatchTest {

    private val source = listOf("Hana-chan is here.", "Come here.", "Akaishi-san waits.")

    private fun masked() = HonorificMask.mask(source)

    /** what the provider returned: line 0 fine, line 2 lost its token */
    private fun providerLines(m: HonorificMask.Masked) =
        listOf("${m.token(0)} burada.", "Buraya gel.", "bekliyor.")

    @Test
    fun `a failed fallback keeps the lines that were never in trouble`() = runBlocking {
        val m = masked()
        val out = PipelineRunner.unmaskBatch(m, providerLines(m), source) { null }
        assertEquals("Hana-chan burada.", out.lines[0])
        assertEquals("Buraya gel.", out.lines[1])
        // only the line that lost its token falls back to the source language
        assertEquals(source[2], out.lines[2])
        assertEquals(listOf(2), out.untranslated)
    }

    @Test
    fun `a failed fallback reports the cues it left behind`() = runBlocking {
        // the count has to reach untranslatedPct, or the result claims to be complete
        val m = masked()
        val out = PipelineRunner.unmaskBatch(m, providerLines(m), source) { null }
        assertEquals(1, out.untranslated.size)
    }

    @Test
    fun `a working fallback translates only the lost lines`() = runBlocking {
        val m = masked()
        var asked: List<String>? = null
        val out = PipelineRunner.unmaskBatch(m, providerLines(m), source) { lines ->
            asked = lines
            listOf("Akaishi-san bekliyor.")
        }
        assertEquals(listOf("Akaishi-san waits."), asked)
        assertEquals("Akaishi-san bekliyor.", out.lines[2])
        assertTrue(out.untranslated.isEmpty())
    }

    @Test
    fun `a fallback that returns the wrong number of lines is not spliced in`() = runBlocking {
        val m = masked()
        val out = PipelineRunner.unmaskBatch(m, providerLines(m), source) { listOf("a", "b") }
        assertEquals(source[2], out.lines[2])
        assertEquals(listOf(2), out.untranslated)
    }

    @Test
    fun `a clean batch never asks for a fallback`() = runBlocking {
        val m = masked()
        var asked = false
        val clean = listOf("${m.token(0)} burada.", "Buraya gel.", "${m.token(1)} bekliyor.")
        val out = PipelineRunner.unmaskBatch(m, clean, source) { asked = true; null }
        assertEquals(false, asked)
        assertTrue(out.untranslated.isEmpty())
        assertEquals("Akaishi-san bekliyor.", out.lines[2])
    }

    @Test
    fun `an unmasked batch passes straight through`() = runBlocking {
        val m = HonorificMask.mask(listOf("Nothing here.", "Or here."))
        val out = PipelineRunner.unmaskBatch(m, listOf("Burada yok.", "Burada da."), source) { null }
        assertEquals(listOf("Burada yok.", "Burada da."), out.lines)
        assertTrue(out.untranslated.isEmpty())
    }
}
