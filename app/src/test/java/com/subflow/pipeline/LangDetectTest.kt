package com.subflow.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Turkish sample is real Google MT output (gtx, en to tr). If the detector
 * misreads our translation output as en, every generated subtitle gets tossed
 * as "not found", the worst failure mode here.
 */
class LangDetectTest {

    private val mtTurkish = """
        Tanya, tabur harekete hazır.
        İstisnasız, şafak vakti saldırıyoruz.
        Anlaşıldı Binbaşı. Büyücüler hazırlandı.
        İmparatorluk tam bir zafer talep ediyor.
        X olmak yine bizimle oynuyor.
        Hepsinin canı cehenneme!
        Topçu, işaretime ateş açın!
        O sadece bir çocuk, değil mi?
        Bütün bir tabura komuta eden bir çocuk.
        Geri çekilme bizim için bir seçenek değil.
        Yaşasın İmparatorluk!
        Bu lanetli savaş hiçbir zaman bitmeyecek.
        Ne yaptığını sanıyorsun asker?
        Sadece emirlere uyuyordum hanımefendi.
        Emirler seni benim gazabımdan kurtaramaz.
        Düşman hatları çöküyor.
        Tüm birimler ileri doğru ilerleyin!
        Allah ruhlarına rahmet eylesin.
        Bu savaş alanında Tanrı yok.
        Sadece ben.
    """.trimIndent()

    private val english = """
        Tanya, the battalion is ready to move.
        We attack at dawn, no exceptions.
        Understood, Major. The mages are prepared.
        The Empire demands total victory.
        Being X is toying with us again.
        Damn it all to hell!
        Artillery, open fire on my mark!
        She's just a child, isn't she?
        A child who commands an entire battalion.
        Retreat is not an option for us.
        Long live the Empire!
        This cursed war will never end.
        What do you think you're doing, soldier?
        I was merely following orders, ma'am.
        Orders won't save you from my wrath.
        The enemy lines are collapsing.
        Push forward, all units!
        May God have mercy on their souls.
        There is no God on this battlefield.
        Only me.
    """.trimIndent()

    @Test
    fun `real machine-translated Turkish is detected as tr, never en`() {
        assertEquals("tr", LangDetect.detect(mtTurkish))
    }

    @Test
    fun `english source is detected as en`() {
        assertEquals("en", LangDetect.detect(english))
    }

    @Test
    fun `a small batch of MT Turkish still detects as tr`() {
        // echo check normally sees ~25 lines, 8 must still work
        val batch = mtTurkish.lines().take(8).joinToString(" ")
        assertEquals("tr", LangDetect.detect(batch))
    }

    @Test
    fun `too-short or ambiguous content returns null, not a wrong language`() {
        assertNull(LangDetect.detect("Tanya!"))
        assertNull(LangDetect.detect("..."))
    }
}
