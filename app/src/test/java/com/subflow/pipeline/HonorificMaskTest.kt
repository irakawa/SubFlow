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

    // --- no line may leave with a token still in it ---

    @Test
    fun `a token that drifted onto another line is caught on both lines`() {
        // the provider merged two cues: line 0 lost its token and line 1 gained one it
        // never had. Verifying only the tokens a line was *expected* to carry meant the
        // receiving line was never even scanned, and shipped with __SF0__ in it.
        val masked = HonorificMask.mask(listOf("Hana-chan,", "Come here."))
        val restored = HonorificMask.restoreAll(
            masked,
            listOf("", "${masked.token(0)}, buraya gel.")
        )
        assertEquals(listOf(0, 1), restored.lost)
    }

    @Test
    fun `a half-chewed token leaves the line refused`() {
        val masked = HonorificMask.mask(listOf("Wait for Akaishi-san.", "Come here."))
        // the remnant is not a whole token, so no count check would ever see it
        assertNull(HonorificMask.restore(masked, 1, "__SF buraya gel."))
    }

    @Test
    fun `an unmasked batch is not scanned for tokens`() {
        // when masking never ran, "__SF" in the text is the user's own and stays
        val masked = HonorificMask.mask(listOf("Build __SF0__ failed."))
        assertFalse(masked.active)
        assertEquals("Yapı __SF0__ başarısız.", HonorificMask.restore(masked, 0, "Yapı __SF0__ başarısız."))
    }

    // --- the per-line decision the pipeline acts on ---

    @Test
    fun `a batch reports exactly which lines lost their token`() {
        // the pipeline retranslates the reported indices unmasked and keeps the rest.
        // Deciding that here rather than in the caller is the point: it is the only
        // version of the decision, and it is the tested one.
        val masked = HonorificMask.mask(
            listOf("Hana-chan is here.", "Wait for Akaishi-san.", "Nothing to mask.")
        )
        val (restored, lost) = HonorificMask.restoreAll(
            masked,
            listOf(
                "${masked.token(0)} burada.",   // came back clean
                "Bekle.",                        // provider ate the token
                "Maskelenecek bir şey yok."      // never had one
            )
        )
        assertEquals(listOf(1), lost)
        assertEquals("Hana-chan burada.", restored[0])
        assertEquals("Maskelenecek bir şey yok.", restored[2])
    }

    @Test
    fun `a clean batch reports nothing lost`() {
        val masked = HonorificMask.mask(listOf("Hana-chan is here."))
        val (restored, lost) = HonorificMask.restoreAll(masked, listOf("${masked.token(0)} burada."))
        assertTrue(lost.isEmpty())
        assertEquals("Hana-chan burada.", restored[0])
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
