package com.subflow.pipeline

import com.subflow.models.ContentType
import com.subflow.models.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentIdentityTest {

    private val transformersPrime = Release(
        title = "Transformers Prime", season = 1, episode = 6, type = ContentType.SERIES
    )

    // the four proven bad matches

    @Test
    fun `different production with extra tokens is rejected`() {
        assertFalse(
            ContentIdentity.verify(
                "Transformers.Prime.Beast.Hunters.Predacons.Rising.2013.1080p", transformersPrime
            )
        )
    }

    @Test
    fun `unrelated 80s series episode is rejected`() {
        assertFalse(
            ContentIdentity.verify("The Transformers - A Prime Problem", transformersPrime)
        )
    }

    @Test
    fun `wrong episode of right show is rejected`() {
        assertFalse(
            ContentIdentity.verify(
                "Transformers Prime - 101 - Darkness Rising Part 1", transformersPrime
            )
        )
    }

    @Test
    fun `japanese different production is rejected`() {
        assertFalse(
            ContentIdentity.verify(
                "Super Robot Lifeform Transformers Prime 006", transformersPrime
            )
        )
    }

    @Test
    fun `exact scene name passes`() {
        assertTrue(
            ContentIdentity.verify(
                "Transformers.Prime.S01E06.1080p.WEB-DL.x264-GROUP", transformersPrime
            )
        )
    }

    @Test
    fun `loose site name without episode info passes`() {
        assertTrue(ContentIdentity.verify("Transformers Prime", transformersPrime))
    }

    @Test
    fun `alternate episode notation passes`() {
        assertTrue(ContentIdentity.verify("Transformers Prime 1x06 HDTV", transformersPrime))
    }

    @Test
    fun `film year within tolerance passes, beyond is rejected`() {
        val film = Release(title = "Dune", year = 2021, type = ContentType.FILM)
        assertTrue(ContentIdentity.verify("Dune.2021.2160p.HDR.x265", film))
        assertFalse(ContentIdentity.verify("Dune.1984.1080p.BluRay", film))
    }

    @Test
    fun `exact match scores above gate, vague match below`() {
        val exact = ContentIdentity.matchScore(
            transformersPrime, "Transformers.Prime.S01E06.720p.WEB-DL"
        )
        assertTrue("expected >=60, got $exact", exact >= ContentIdentity.SCORE_GATE)

        val vague = ContentIdentity.matchScore(transformersPrime, "Transformers Collection Pack")
        assertTrue("expected <60, got $vague", vague < ContentIdentity.SCORE_GATE)
    }

    @Test
    fun `episode-title words in filename do not drop a correct sub below the gate`() {
        // "Darkness.Rising" is the episode title, not a spin-off. don't penalize it.
        // used to score 59 vs a 60 gate and demote the correct TR sub.
        val s01e01 = Release(title = "Transformers Prime", season = 1, episode = 1, type = ContentType.SERIES)
        val score = ContentIdentity.matchScore(s01e01, "Transformers.Prime.S01E01.Darkness.Rising.720p")
        assertTrue("expected >=60, got $score", score >= ContentIdentity.SCORE_GATE)
    }

    @Test
    fun `version tag v2 is not misread as roman season 5`() {
        // "05v2" is a version tag. a lone "v" must not fabricate season 5.
        assertEquals(null, ContentIdentity.extractSeason("[Group] Overlord - 05v2 [1080p]"))
        // multi-letter roman seasons still resolve
        assertEquals(2, ContentIdentity.extractSeason("[Group] Overlord II - 05 [1080p]"))
    }

    @Test
    fun `fuzzy match normalizes punctuation`() {
        assertTrue(ContentIdentity.fuzzyMatch("Jujutsu Kaisen", "jujutsu.kaisen") > 0.95f)
    }

    // real-world naming that must not be falsely rejected

    @Test
    fun `bracketed fansub with dash episode passes`() {
        val aot = Release(title = "Attack on Titan", season = 4, episode = 28, type = ContentType.ANIME)
        assertTrue(ContentIdentity.verify("[SubsPlease] Attack on Titan - 28 (1080p)", aot))
    }

    @Test
    fun `fansub with wrong dash episode is rejected`() {
        val aot = Release(title = "Attack on Titan", season = 4, episode = 28, type = ContentType.ANIME)
        assertFalse(ContentIdentity.verify("[SubsPlease] Attack on Titan - 03 (1080p)", aot))
    }

    @Test
    fun `film scene name with group tail passes`() {
        val film = Release(title = "Dune", year = 2021, type = ContentType.FILM)
        assertTrue(ContentIdentity.verify("Dune.2021.1080p.WEB-DL.DDP5.1.Atmos.H.264-CMRG", film))
    }

    @Test
    fun `turkish listing format passes`() {
        val bb = Release(title = "Breaking Bad", season = 5, episode = 14, type = ContentType.SERIES)
        assertTrue(ContentIdentity.verify("Breaking Bad 5. Sezon 14. Bölüm Türkçe Altyazı", bb))
    }

    @Test
    fun `h265 codec is not mistaken for an episode number`() {
        val show = Release(title = "Show", season = 1, episode = 6, type = ContentType.SERIES)
        assertTrue(ContentIdentity.verify("Show - 06 (H.265)", show))
    }

    @Test
    fun `resolution is not mistaken for season x episode`() {
        val show = Release(title = "Show", season = 2, episode = 8, type = ContentType.SERIES)
        assertTrue(ContentIdentity.verify("Show S02E08 1920x1080 x265", show))
        // NxNN form still works
        assertTrue(ContentIdentity.verify("Show 2x08 HDTV", show))
    }

    @Test
    fun `year extraction takes the bounded release year`() {
        val film = Release(title = "1917", year = 2019, type = ContentType.FILM)
        assertTrue(ContentIdentity.verify("1917.2019.1080p.BluRay.x264", film))
    }

    // title-year numbers must not be read as the release year

    @Test
    fun `film title ending in a year is not rejected`() {
        val film = Release(title = "Blade Runner 2049", year = 2017, type = ContentType.FILM)
        assertTrue(ContentIdentity.verify("Blade Runner 2049.1080p.BluRay.x264-GROUP", film))
        // dotted scene form must survive separator differences
        assertTrue(ContentIdentity.verify("Blade.Runner.2049.1080p.BluRay.x264-GROUP", film))
    }

    @Test
    fun `dotted anime title with a number is not misread as an episode`() {
        val mob = Release(title = "Mob Psycho 100", season = 2, episode = 5, type = ContentType.ANIME)
        assertTrue(ContentIdentity.verify("Mob.Psycho.100.II.-.05.1080p", mob))
        assertTrue(ContentIdentity.verify("[Group] Mob Psycho 100 II - 05", mob))
    }

    @Test
    fun `diacritics and turkish casing do not break matching`() {
        val poke = Release(title = "Pokemon", season = 1, episode = 3, type = ContentType.ANIME)
        assertTrue(ContentIdentity.titleResembles("Pokémon S01E03", poke))
        val tr = Release(title = "İntikam", season = 1, episode = 2, type = ContentType.SERIES)
        assertTrue(ContentIdentity.titleResembles("intikam 1. Sezon 2. Bölüm", tr))
    }

    @Test
    fun `candidate named by an alternate title passes when alt titles are known`() {
        val youjo = Release(
            title = "Saga of Tanya the Evil", season = 2, episode = 1, type = ContentType.ANIME,
            altTitles = listOf("Youjo Senki")
        )
        assertTrue(ContentIdentity.verify("[SubsPlease] Youjo Senki - 01 (1080p)", youjo))
        assertTrue(ContentIdentity.matchScore(youjo, "Youjo.Senki.S02E01.1080p") >= ContentIdentity.SCORE_GATE)
        // junk still fails even with alt titles present
        assertFalse(ContentIdentity.verify("Game.of.Thrones.S02E01", youjo))
    }

    @Test
    fun `film titled with a year passes without a separate release year`() {
        val film = Release(title = "1917", year = 2019, type = ContentType.FILM)
        // no separate year token, so title's 1917 must not become candYear
        assertTrue(ContentIdentity.verify("1917.1080p.BluRay.x264", film))
    }

    // title numbers must not be read as an episode

    @Test
    fun `series titled with a number is not rejected on a bare 3-digit`() {
        val show = Release(title = "The 100", season = 2, episode = 5, type = ContentType.SERIES)
        // bare-3-digit is anime-only. for a live-action series the title's 100 is ignored
        assertTrue(ContentIdentity.verify("The 100 S02E05 1080p WEB-DL", show))
        assertTrue(ContentIdentity.verify("The 100 1080p HDTV", show)) // no S/E, neutral not rejected
    }

    @Test
    fun `anime bare 3-digit still works and rejects wrong episode`() {
        val anime = Release(title = "One Piece", episode = 1015, type = ContentType.ANIME)
        assertTrue(ContentIdentity.verify("[Group] One Piece - 1015 [1080p]", anime))
        assertFalse(ContentIdentity.verify("[Group] One Piece - 1000 [1080p]", anime))
    }

    // S/E agreement must never substitute for title identity (every show has an S02E01)

    @Test
    fun `a different show with the same season and episode is rejected`() {
        val youjo = Release(title = "Youjo Senki", season = 2, episode = 1, type = ContentType.ANIME)
        assertFalse(ContentIdentity.verify("Game.of.Thrones.S02E01.1080p.BluRay.x264", youjo))
        assertFalse(ContentIdentity.verify("Breaking Bad S02E01 720p HDTV", youjo))
        assertFalse(ContentIdentity.verify("The.Witcher.S02E01.WEB-DL", youjo))
    }

    @Test
    fun `the actually requested show still passes in all its spellings`() {
        val youjo = Release(title = "Youjo Senki", season = 2, episode = 1, type = ContentType.ANIME)
        assertTrue(ContentIdentity.verify("Youjo.Senki.S02E01.1080p.WEB-DL", youjo))
        assertTrue(ContentIdentity.verify("[SubsPlease] Youjo Senki S2 - 01 (1080p)", youjo))
        assertTrue(ContentIdentity.verify("Youjo Senki 2. Sezon 1. Bölüm Türkçe Altyazı", youjo))
    }

    // alt-title naming must clear both the identity gate and the score gate

    @Test
    fun `alt-title suffixed release passes once metadata supplies the alias`() {
        // AltTitles.resolve() fills altTitles at runtime. with the alias known the
        // suffix words are explained and both gates open
        val youjo = Release(
            title = "Youjo Senki", season = 2, episode = 1, type = ContentType.ANIME,
            altTitles = listOf("Saga of Tanya the Evil")
        )
        val names = listOf(
            "Youjo.Senki.Saga.of.Tanya.the.Evil.S02E01.1080p.WEB-DL",
            "[SubsPlease] Youjo Senki Saga of Tanya the Evil - 01 (1080p)",
            "Youjo Senki - Saga of Tanya the Evil Episode 1 English subtitles"
        )
        for (name in names) {
            assertTrue("verify failed: $name", ContentIdentity.verify(name, youjo))
            val score = ContentIdentity.matchScore(youjo, name)
            assertTrue("score $score < gate for: $name", score >= ContentIdentity.SCORE_GATE)
        }
        // without the alias the suffix reads as unexplained extra words and stays
        // under the serve gate (spin-off protection)
        val noAlias = youjo.copy(altTitles = emptyList())
        assertTrue(
            ContentIdentity.matchScore(noAlias, "Youjo.Senki.Saga.of.Tanya.the.Evil.S02E01") <
                ContentIdentity.SCORE_GATE
        )
    }

    @Test
    fun `a one-letter site misspelling still matches the title`() {
        // from a failing device log: the site spells it "Yojo"
        val youjo = Release(
            title = "Youjo Senki", season = 2, episode = 1, type = ContentType.ANIME,
            altTitles = listOf("Saga of Tanya the Evil")
        )
        val row = "Yojo Senki: Saga of Tanya the Evil - 698681"
        assertTrue(ContentIdentity.verify(row, youjo))
        assertEquals(1f, ContentIdentity.containment(row, youjo))
        // short tokens stay exact-match only, no US/UK bleed
        val office = Release(title = "The Office US", season = 2, episode = 1, type = ContentType.SERIES)
        assertTrue(ContentIdentity.containment("The Office UK S02E01", office) < 1f)
    }

    @Test
    fun `roman numeral, S2 and 2nd Season forms reject the wrong season`() {
        val s1 = Release(title = "Overlord", season = 1, episode = 5, type = ContentType.ANIME)
        assertFalse(ContentIdentity.verify("[Judas] Overlord II - 05", s1))
        assertFalse(ContentIdentity.verify("Overlord S2 - 05 (1080p)", s1))
        assertFalse(ContentIdentity.verify("Overlord 2nd Season - 05", s1))
        val s2 = s1.copy(season = 2)
        assertTrue(ContentIdentity.verify("[Judas] Overlord II - 05", s2))
        assertTrue(ContentIdentity.verify("Overlord S2 - 05 (1080p)", s2))
        assertTrue(ContentIdentity.verify("Overlord 2nd Season - 05", s2))
    }

    @Test
    fun `four digit episode numbers are not truncated`() {
        val op = Release(title = "One Piece", episode = 1071, type = ContentType.ANIME)
        assertTrue(ContentIdentity.verify("One Piece Episode 1071 English Sub", op))
        assertTrue(ContentIdentity.verify("One Piece 1071. Bölüm", op))
        assertFalse(ContentIdentity.verify("One Piece Episode 1017 English Sub", op))
    }

    @Test
    fun `recaps trailers and half episodes never satisfy an episode request`() {
        val aot = Release(title = "Attack on Titan", season = 4, episode = 28, type = ContentType.ANIME)
        assertFalse(ContentIdentity.verify("Attack on Titan - 28.5 (Recap)", aot))
        val dune = Release(title = "Dune", year = 2021, type = ContentType.FILM)
        assertFalse(ContentIdentity.verify("Dune Official Trailer (2021) 4K", dune))
    }

    @Test
    fun `spin-offs sharing the title and episode number stay under the serve gate`() {
        val prime = Release(title = "Transformers Prime", season = 1, episode = 6, type = ContentType.SERIES)
        assertTrue(ContentIdentity.matchScore(prime, "Transformers Prime Beast Hunters S01E06") < ContentIdentity.SCORE_GATE)
        val aot = Release(title = "Attack on Titan", season = 4, episode = 28, type = ContentType.ANIME)
        assertTrue(ContentIdentity.matchScore(aot, "Attack on Titan Junior High - 28") < ContentIdentity.SCORE_GATE)
    }

    @Test
    fun `alt-title season pack without an episode is still rejected`() {
        val youjo = Release(title = "Youjo Senki", season = 2, episode = 1, type = ContentType.ANIME)
        assertFalse(ContentIdentity.verify("Youjo Senki Saga of Tanya the Evil (Season 1) [BD 1080p]", youjo))
    }

    @Test
    fun `containment measures presence of the requested title`() {
        val youjo = Release(title = "Youjo Senki", season = 2, episode = 1, type = ContentType.ANIME)
        assertEquals(1f, ContentIdentity.containment("Youjo.Senki.Saga.of.Tanya.the.Evil.S02E01", youjo))
        assertEquals(0f, ContentIdentity.containment("Game.of.Thrones.S02E01", youjo))
    }

    @Test
    fun `unrelated popular content without episode markers is rejected`() {
        // popular links scraped from a no-result page must never pass
        val youjo = Release(title = "Youjo Senki", season = 2, episode = 1, type = ContentType.ANIME)
        assertFalse(ContentIdentity.titleResembles("Game of Thrones", youjo))
        assertFalse(ContentIdentity.titleResembles("Squid Game 2. Sezon", youjo))
        assertTrue(ContentIdentity.titleResembles("Youjo Senki", youjo))
        assertTrue(ContentIdentity.titleResembles("Youjo.Senki.Saga.of.Tanya.the.Evil", youjo))
    }

    // gate hardening regression cases

    @Test
    fun `anime searched by its japanese name matches the english-titled release`() {
        // Otome Kaijuu Caraméliser == "Kaiju Girl Caramelise" (CR/EN title). searching by
        // the JP name must still accept the EN-titled Nyaa release. real user case.
        val jp = "[Tsundere-Raws] Kaiju Girl Caramelise S01E01 VOSTFR 1080p WEB x264 AAC (CR) (Otome Kaijuu Caraméliser)"
        // no episode set, just the show name
        val byName = Release(title = "Otome Kaijuu Caraméliser", type = ContentType.ANIME)
        assertTrue(ContentIdentity.verify(jp, byName))
        // right episode
        val e1 = Release(title = "Otome Kaijuu Caraméliser", season = 1, episode = 1, type = ContentType.ANIME)
        assertTrue(ContentIdentity.verify(jp, e1))
        // stale episode from a prior search, rejected as wrong episode.
        // the form-state fix in SearchViewModel.onTitleChanged keeps this from reaching verify.
        val stale = Release(title = "Otome Kaijuu Caraméliser", season = 3, episode = 7, type = ContentType.ANIME)
        assertFalse(ContentIdentity.verify(jp, stale))
        // by the EN name directly, JP name in the parenthetical
        val byEn = Release(title = "Kaiju Girl Caramelise", season = 1, episode = 1, type = ContentType.ANIME)
        assertTrue(ContentIdentity.verify(jp, byEn))
        // end-to-end: searched by JP name, AltTitles resolves EN, and the
        // OpenSubtitles.org English file named by the EN title must be accepted.
        val resolved = Release(
            title = "Otome Kaijuu Caraméliser",
            altTitles = listOf("Otome Kaijuu Caramelise", "KAIJU GIRL CARAMELISE"),
            season = 1, episode = 1, type = ContentType.ANIME
        )
        assertTrue(ContentIdentity.verify("KAIJU GIRL CARAMELISE - S01E01.en.srt", resolved))
    }

    @Test
    fun `spaced and dotted season-episode markers are parsed`() {
        assertEquals(1, ContentIdentity.extractSeason("Show Name S1 E5 1080p WEB-DL"))
        assertEquals(5, ContentIdentity.extractEpisode("Show Name S1 E5 1080p WEB-DL"))
        assertEquals(2, ContentIdentity.extractSeason("Show.Name.S02.E08.720p"))
        assertEquals(8, ContentIdentity.extractEpisode("Show.Name.S02.E08.720p"))
    }

    @Test
    fun `wrong episode in a spaced marker is rejected, right one accepted`() {
        val show = Release(title = "Show Name", season = 1, episode = 5, type = ContentType.SERIES)
        assertTrue(ContentIdentity.verify("Show Name S1 E5 1080p WEB-DL", show))
        assertFalse(ContentIdentity.verify("Show Name S1 E9 1080p WEB-DL", show))
    }

    @Test
    fun `spin-off with a matching season-episode number is still rejected`() {
        // same S/E number must not confirm a different production
        val prime = Release(title = "Transformers Prime", season = 1, episode = 6, type = ContentType.SERIES)
        assertFalse(ContentIdentity.verify("Transformers Prime Beast Hunters S01E06", prime))
        assertTrue(ContentIdentity.verify("Transformers.Prime.S01E06.1080p.WEB-DL.x264-GROUP", prime))
    }

    @Test
    fun `episode title in the filename is not mistaken for a different production`() {
        // "Darkness Rising" etc. are episode titles after the S/E marker,
        // not spin-off names. the subtitle must still be accepted
        val s1e1 = Release(title = "Transformers Prime", season = 1, episode = 1, type = ContentType.SERIES)
        assertTrue(ContentIdentity.verify("Transformers.Prime.S01E01.Darkness.Rising.Part.1.720p", s1e1))
        val s1e6 = Release(title = "Transformers Prime", season = 1, episode = 6, type = ContentType.SERIES)
        assertTrue(ContentIdentity.verify("Transformers.Prime.S01E06.Masters.and.Students.720p", s1e6))
        val s2e1 = Release(title = "Transformers Prime", season = 2, episode = 1, type = ContentType.SERIES)
        assertTrue(ContentIdentity.verify("Transformers Prime - 02x01 - Orion Pax, Part 1", s2e1))
        // wrong episode's title still rejected
        assertFalse(ContentIdentity.verify("Transformers.Prime.S01E05.Darkness.Rising.Part.5", s1e1))
    }

    @Test
    fun `numbered film sequel without a year is not confused with the original`() {
        val badBoys = Release(title = "Bad Boys", type = ContentType.FILM)
        assertFalse(ContentIdentity.verify("Bad.Boys.2.1080p.BluRay.x264-RARBG", badBoys))
        assertTrue(ContentIdentity.verify("Bad.Boys.1080p.BluRay.x264-RARBG", badBoys))
        // a numbered request still accepts its own sequel
        val badBoys2 = Release(title = "Bad Boys 2", type = ContentType.FILM)
        assertTrue(ContentIdentity.verify("Bad.Boys.2.1080p.BluRay.x264-RARBG", badBoys2))
    }

    @Test
    fun `edition and cut variants without a year token are still accepted`() {
        val batman = Release(title = "The Batman", year = 2022, type = ContentType.FILM)
        assertTrue(ContentIdentity.verify("The.Batman.EXTENDED.CUT.1080p.BluRay.x264-GROUP", batman))
        val dune = Release(title = "Dune", year = 2021, type = ContentType.FILM)
        assertTrue(ContentIdentity.verify("Dune.2021.REMASTERED.DIRECTORS.CUT.1080p.BluRay", dune))
    }
}
