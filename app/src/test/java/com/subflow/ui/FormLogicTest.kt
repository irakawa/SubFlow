package com.subflow.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormLogicTest {

    @Test
    fun `select-all then type-over a new show is detected as different at the first char`() {
        // select-all then type over. first char is a substring but not a prefix, so different.
        assertTrue(isDifferentShow("Transformers Prime", "O"))
        assertTrue(isDifferentShow("Transformers Prime", "Ot"))
        assertTrue(isDifferentShow("Transformers Prime", "Otome Kaijuu Caraméliser"))
        // keep typing the established title, not a new change
        assertFalse(isDifferentShow("O", "Ot"))
        assertFalse(isDifferentShow("Otome", "Otome Kaijuu"))
    }

    @Test
    fun `typing the same title forward or backspacing is the same show`() {
        assertFalse(isDifferentShow("Transf", "Transformers"))       // forward
        assertFalse(isDifferentShow("Transformers", "Transf"))       // backspace
        assertFalse(isDifferentShow("Transformers Prime", "Transformers Prim"))
        assertFalse(isDifferentShow("Attack on Titan", "Attack on Titan Final Season")) // suffix
    }

    @Test
    fun `a mid-title typo fix keeps the same show (does not wipe episode)`() {
        // typo fix. not a prefix either way but shares a long leading run, so same show.
        assertFalse(isDifferentShow("Breaking Bde", "Breaking Bad"))
        assertFalse(isDifferentShow("Game of Thrnoes", "Game of Thrones"))
    }

    @Test
    fun `clearing the field or a truly unrelated title is different`() {
        assertTrue(isDifferentShow("Transformers", ""))              // cleared
        assertTrue(isDifferentShow("Transformers Prime", "Naruto"))
        assertTrue(isDifferentShow("Game of Thrones", "Breaking Bad"))
    }

    @Test
    fun `two different shows sharing a long leading word are different (stale S-E must clear)`() {
        // shared franchise word isn't a typo fix. switching shows must clear stale S/E.
        assertTrue(isDifferentShow("Transformers Prime", "Transformers War for Cybertron"))
        assertTrue(isDifferentShow("The Boys", "The Wire"))          // share "The "
        assertTrue(isDifferentShow("Star Wars", "Star Trek"))        // share "Star "
        assertTrue(isDifferentShow("The Last of Us", "The Last Kingdom"))
    }

    @Test
    fun `starting from an empty title is not a different-show clear`() {
        assertFalse(isDifferentShow("", "Naruto"))                   // fresh form, nothing to clear
    }

    @Test
    fun `case and surrounding whitespace do not affect the decision`() {
        assertFalse(isDifferentShow("naruto", "  Naruto  "))
        assertTrue(isDifferentShow("Naruto", "One Piece"))
    }
}
