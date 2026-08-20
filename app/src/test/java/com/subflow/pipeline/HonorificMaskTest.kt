package com.subflow.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SUBFLOW_LANGUAGE_RULES 4: honorifics are not Turkishised.
 *
 * The rule was written and never implemented, so the machine translator decided —
 * "Hana-chan" came back "Hanacığı" with -chan mapped onto the Turkish diminutive -cık,
 * and "Oshira-sama!" came back "Osiramasama!" with the name itself misspelled. The
 * approach is to stop asking the translator to get it right: the pair goes out as an
 * opaque token and comes back untouched.
 */
class HonorificMaskTest {

    // --- the two reported lines ---

    @Test
    fun `a name with an honorific survives translation intact`() {
        val masked = HonorificMask.mask(listOf("Are you talking about Hana-chan?"))
        // what the provider is asked to translate carries no name at all
        assertFalse(masked.lines[0].contains("Hana"))
        // and what it sends back, with the Turkish case ending it chose, restores
        assertEquals(
            "Hana-chan'ı mı diyorsun sen?",
            HonorificMask.restore(masked, 0, "${masked.token(0)}'ı mı diyorsun sen?")
        )
    }

    @Test
    fun `a bare vocative name survives translation intact`() {
        val masked = HonorificMask.mask(listOf("Oshira-sama!"))
        assertEquals(
            "Oshira-sama!",
            HonorificMask.restore(masked, 0, "${masked.token(0)}!")
        )
    }

    // --- verification: a lost token must never reach the file ---

    @Test
    fun `a token the provider dropped fails verification`() {
        val masked = HonorificMask.mask(listOf("Wait for Akaishi-san."))
        assertNull(HonorificMask.restore(masked, 0, "Bekle."))
    }

    @Test
    fun `a token the provider mangled fails verification`() {
        val masked = HonorificMask.mask(listOf("Wait for Akaishi-san."))
        assertNull(HonorificMask.restore(masked, 0, "__ SF0 __ için bekle."))
    }

    @Test
    fun `a token the provider duplicated fails verification`() {
        // the count has to match, not just be non-zero: a duplicated token would put
        // the name in a place the sentence never had it
        val masked = HonorificMask.mask(listOf("Wait for Akaishi-san."))
        val t = masked.token(0)
        assertNull(HonorificMask.restore(masked, 0, "$t ve $t için bekle."))
    }

    // --- batch behaviour ---

    @Test
    fun `the same name gets the same token everywhere in the batch`() {
        val masked = HonorificMask.mask(
            listOf("Hana-chan is here.", "I told Hana-chan already.", "Where is Akaishi-san?")
        )
        val hana = masked.token(0)
        assertTrue(masked.lines[0].contains(hana))
        assertTrue(masked.lines[1].contains(hana))
        assertFalse(masked.lines[2].contains(hana))
    }

    @Test
    fun `two names on one line come back in their own places`() {
        val masked = HonorificMask.mask(listOf("Akaishi-san told Hana-chan to wait."))
        val a = masked.token(0)
        val b = masked.token(1)
        assertEquals(
            "Akaishi-san, Hana-chan'a beklemesini söyledi.",
            HonorificMask.restore(masked, 0, "$a, $b'a beklemesini söyledi.")
        )
    }

    // --- the source text is a separate copy, and rule 3.2 still reads it ---

    @Test
    fun `masking does not touch the caller's lines`() {
        val source = listOf("Naruto-kun, are you okay?")
        HonorificMask.mask(source)
        assertEquals("Naruto-kun, are you okay?", source[0])
        // rule 3.2 resolves formality from this text; masking it would erase the signal
        assertEquals(Formality.INFORMAL, AddresseeAnalyzer.formalityOf(source[0]))
    }

    @Test
    fun `the masked copy no longer carries the formality signal`() {
        // stated as a test because it is the reason the tracker must never be fed these
        val masked = HonorificMask.mask(listOf("Naruto-kun, are you okay?"))
        assertNull(AddresseeAnalyzer.formalityOf(masked.lines[0]))
    }

    // --- do not mask what should not be masked ---

    @Test
    fun `a line that already contains the token shape is left unmasked`() {
        val masked = HonorificMask.mask(listOf("Build __SF0__ failed.", "Hana-chan is here."))
        assertFalse(masked.active)
        assertEquals("Hana-chan is here.", masked.lines[1])
    }

    @Test
    fun `ordinary text is not mistaken for an honorific`() {
        val masked = HonorificMask.mask(
            listOf("We flew to San Francisco.", "It was a well-done job.", "no-sensei here")
        )
        assertFalse(masked.active)
    }

    @Test
    fun `a line with nothing to mask restores unchanged`() {
        val masked = HonorificMask.mask(listOf("Nothing to see here."))
        assertEquals("Görülecek bir şey yok.", HonorificMask.restore(masked, 0, "Görülecek bir şey yok."))
    }
}
