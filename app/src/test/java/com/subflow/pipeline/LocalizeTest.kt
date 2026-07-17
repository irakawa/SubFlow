package com.subflow.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalizeTest {

    private fun t(s: String) = Localize.godToTanri(s)

    @Test
    fun `bare God becomes Tanri`() {
        assertEquals("Tanrı seni korusun", t("Allah seni korusun"))
        assertEquals("Tanrı aşkına", t("Allah aşkına"))
    }

    @Test
    fun `the God exclamation becomes Tanrim`() {
        assertEquals("Aman Tanrım!", t("Aman Allah'ım!"))
        assertEquals("Tanrım, ne oldu?", t("Allahım, ne oldu?"))
    }

    @Test
    fun `inflected forms are converted with correct suffixes`() {
        assertEquals("Tanrıya şükür", t("Allah'a şükür"))
        assertEquals("Tanrının izniyle", t("Allah'ın izniyle"))
        assertEquals("Tanrıdan korkarım", t("Allah'tan korkarım"))
        assertEquals("Tanrıyı gördüm", t("Allah'ı gördüm"))
    }

    @Test
    fun `idioms that merely contain the string are left untouched`() {
        // not translations of "God", mangling them makes nonsense
        assertEquals("inşallah gelir", t("inşallah gelir"))
        assertEquals("maşallah çok büyümüş", t("maşallah çok büyümüş"))
        assertEquals("vallahi bilmiyorum", t("vallahi bilmiyorum"))
        assertEquals("Allahaısmarladık", t("Allahaısmarladık"))
    }

    @Test
    fun `all-caps shouting is handled`() {
        assertEquals("TANRIM!", t("ALLAH'IM!"))
        assertEquals("TANRI", t("ALLAH"))
    }

    @Test
    fun `edge cases`() {
        assertEquals("Tanrı.", t("Allah."))                       // trailing punctuation
        assertEquals("Vallahi Tanrı var", t("Vallahi Allah var")) // idiom + real word together
        assertEquals("TANRIM", t("ALLAHIM"))                       // all-caps, no apostrophe
        assertEquals("Tanrısız", t("Allahsız"))                    // derivation
        assertEquals("maşallah", t("maşallah"))                    // embedded, untouched
        assertEquals("selam", t("selam"))                          // no allah at all
        assertEquals("", t(""))                                    // empty
    }

    @Test
    fun `inflected suffixes work in every case, not just capitalized`() {
        assertEquals("TANRIYA ŞÜKÜR", t("ALLAH'A ŞÜKÜR"))   // all-caps dative
        assertEquals("tanrıya şükür", t("allah'a şükür"))   // lowercase dative
        assertEquals("Tanrıydı", t("Allah'tı"))
        assertEquals("Tanrıdaymış bir gün", t("Allahtaymış bir gün")) // extended suffix, no dangling
    }

    @Test
    fun `no Allah in any form survives a mixed line`() {
        val out = t("Allah'ım! Allah'a yemin ederim, Allah büyüktür.")
        assert(!out.contains("Allah")) { "Allah survived in: $out" }
        assertEquals("Tanrım! Tanrıya yemin ederim, Tanrı büyüktür.", out)
    }
}
