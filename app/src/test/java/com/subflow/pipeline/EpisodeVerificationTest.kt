package com.subflow.pipeline

import com.subflow.models.ContentType
import com.subflow.models.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app exists to hand back the right episode. The identity gate cannot always prove
 * one: a candidate that never states an episode number scores 45 + 15 + 10 = 70 on an
 * exact title alone, which clears SCORE_GATE. Rejecting it would turn real single-episode
 * uploads into "not found", so it is delivered — but it must not be delivered wearing the
 * same score and label as a candidate whose episode was actually read and matched.
 */
class EpisodeVerificationTest {

    private val prime = Release(
        title = "Transformers Prime", season = 1, episode = 6, type = ContentType.SERIES
    )

    // --- what the gate can and cannot settle ---

    @Test
    fun `a bare title clears the gate even though nothing confirms the episode`() {
        // the arithmetic that makes the marker necessary: containment and similarity alone
        // reach the gate, so score tuning cannot express "we could not check the episode"
        assertTrue(ContentIdentity.verify("Transformers Prime", prime))
        assertTrue(ContentIdentity.matchScore(prime, "Transformers Prime") >= ContentIdentity.SCORE_GATE)
        assertFalse(ContentIdentity.episodeConfirmed("Transformers Prime", prime))
    }

    @Test
    fun `a candidate that names the requested episode is confirmed`() {
        assertTrue(ContentIdentity.episodeConfirmed("Transformers.Prime.S01E06.1080p", prime))
        assertTrue(ContentIdentity.episodeConfirmed("Transformers Prime 1x06 HDTV", prime))
    }

    @Test
    fun `a candidate that names a different episode is not confirmed`() {
        assertFalse(ContentIdentity.episodeConfirmed("Transformers.Prime.S01E07.1080p", prime))
    }

    @Test
    fun `a request without an episode has nothing to confirm`() {
        val film = Release(title = "Blade Runner", year = 1982, type = ContentType.FILM)
        assertTrue(ContentIdentity.episodeConfirmed("Blade.Runner.1982.1080p.BluRay", film))
        val show = Release(title = "Transformers Prime", season = 1, type = ContentType.SERIES)
        assertTrue(ContentIdentity.episodeConfirmed("Transformers Prime", show))
    }

    @Test
    fun `a bare fansub number confirms season one but not a later season`() {
        // "[Group] Show - 06" means S01E06 by convention; for a season 3 request the same
        // name proves nothing about which season it came from
        val s1 = Release(title = "Overlord", season = 1, episode = 6, type = ContentType.ANIME)
        val s3 = Release(title = "Overlord", season = 3, episode = 6, type = ContentType.ANIME)
        assertTrue(ContentIdentity.episodeConfirmed("[Group] Overlord - 06 [1080p]", s1))
        assertFalse(ContentIdentity.episodeConfirmed("[Group] Overlord - 06 [1080p]", s3))
        // stating the season removes the ambiguity
        assertTrue(ContentIdentity.episodeConfirmed("[Group] Overlord III - 06 [1080p]", s3))
    }

    // --- what the user is told ---

    @Test
    fun `an unconfirmed episode cannot score as a delivered result`() {
        val capped = Quality.withUnverifiedEpisode(Quality.HUMAN_TAGS_MATCH)
        assertEquals(Quality.UNVERIFIED_EPISODE, capped)
        assertTrue("must fall out of the delivered band", capped < Quality.FLOOR)
        assertTrue(capped < Quality.HUMAN)
    }

    @Test
    fun `the cap never raises a score that was already lower`() {
        // it composes with the other penalties instead of overriding them
        assertEquals(20, Quality.withUnverifiedEpisode(20))
    }

    @Test
    fun `sync confidence cannot lift an unconfirmed episode back into the band`() {
        // aligning cues against the audio says nothing about which episode this is
        val capped = Quality.withUnverifiedEpisode(Quality.HUMAN)
        assertTrue(Quality.withSync(capped, 100) > capped) // withSync alone would lift it
        assertEquals(capped, Quality.episodeAwareSync(capped, 100, episodeVerified = false))
        assertEquals(
            Quality.withSync(Quality.HUMAN, 100),
            Quality.episodeAwareSync(Quality.HUMAN, 100, episodeVerified = true)
        )
    }
}
