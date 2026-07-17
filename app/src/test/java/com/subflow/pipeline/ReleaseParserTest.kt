package com.subflow.pipeline

import com.subflow.models.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseParserTest {

    @Test
    fun `scene series name is parsed`() {
        val r = ReleaseParser.parse("Breaking.Bad.S05E14.1080p.WEB-DL.DDP5.1.H.264-NTb")
        assertEquals("Breaking Bad", r.title)
        assertEquals(5, r.season)
        assertEquals(14, r.episode)
        assertEquals("x264", r.codec)
        assertEquals("WEB-DL", r.format)
        assertEquals("NTb", r.releaseGroup)
    }

    @Test
    fun `fansub anime name is parsed as anime`() {
        val r = ReleaseParser.parse("[SubsPlease] Youjo Senki - 01 [1080p]")
        assertEquals("Youjo Senki", r.title)
        assertEquals(1, r.episode)
        assertEquals(ContentType.ANIME, r.type)
    }

    @Test
    fun `film with year is parsed as film`() {
        val r = ReleaseParser.parse("Dune.2021.2160p.BluRay.x265-GROUP")
        assertEquals("Dune", r.title)
        assertEquals(2021, r.year)
        assertEquals(ContentType.FILM, r.type)
        assertEquals("x265", r.codec)
    }

    @Test
    fun `HDTV keeps its own format, is not relabelled WEB-DL`() {
        val r = ReleaseParser.parse("Some.Show.S01E02.720p.HDTV.x264-GRP")
        assertEquals("HDTV", r.format)
    }

    @Test
    fun `alternate NxNN episode notation is parsed`() {
        val r = ReleaseParser.parse("The Office 2x05 HDTV")
        assertEquals(2, r.season)
        assertEquals(5, r.episode)
    }
}
