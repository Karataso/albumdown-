package com.albumdown

import com.albumdown.dl.LrcLibMatch
import com.albumdown.dl.bestLyricMatch
import com.albumdown.dl.scoreLyricCandidate
import com.albumdown.dl.stripSyncedTimestamps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port de albumdown:_lrclib_lookup (albumdown:863-891). La lógica de puntuación
 * es la que decide si una canción se lleva su letra o se queda sin ella, así que
 * se prueba como cosa pura.
 */
class LrcLibTest {

    @Test
    fun exact_duration_and_album_scores_high() {
        // "BMTH" no es subcadena de "Bring Me The Horizon" (el CLI no resuelve
        // siglas), así que el artista no suma. Duración exacta (4) + synced (2)
        // + álbum (1) = 7.
        val s = scoreLyricCandidate(
            durationSec = 228, artist = "BMTH", album = "Sempiternal",
            candidateDuration = 228, candidateArtist = "Bring Me The Horizon",
            candidateAlbum = "Sempiternal", hasSynced = true, hasPlain = true,
        )
        assertEquals(7, s)
    }

    @Test
    fun full_artist_name_match_adds_two() {
        val s = scoreLyricCandidate(
            durationSec = 228, artist = "Bring Me The Horizon", album = null,
            candidateDuration = 228, candidateArtist = "Bring Me The Horizon",
            candidateAlbum = null, hasSynced = true, hasPlain = true,
        )
        // duración (4) + artista exacto (2) + synced (2) = 8
        assertEquals(8, s)
    }

    @Test
    fun duration_off_by_more_than_15_is_rejected() {
        val s = scoreLyricCandidate(
            durationSec = 228, artist = "X", album = null,
            candidateDuration = 300, candidateArtist = "X",
            candidateAlbum = null, hasSynced = true, hasPlain = true,
        )
        assertEquals(-1, s)
    }

    @Test
    fun instrumental_or_lyricless_is_rejected() {
        val s = scoreLyricCandidate(
            durationSec = 100, artist = "X", album = null,
            candidateDuration = 100, candidateArtist = "X",
            candidateAlbum = null, hasSynced = false, hasPlain = false,
        )
        assertEquals(-1, s)
    }

    @Test
    fun synced_beats_plain_only() {
        val base = scoreLyricCandidate(
            durationSec = 200, artist = "X", album = null,
            candidateDuration = 200, candidateArtist = "Y",
            candidateAlbum = null, hasSynced = false, hasPlain = true,
        )
        val withSynced = scoreLyricCandidate(
            durationSec = 200, artist = "X", album = null,
            candidateDuration = 200, candidateArtist = "Y",
            candidateAlbum = null, hasSynced = true, hasPlain = true,
        )
        assertTrue(withSynced > base)
    }

    @Test
    fun best_lyric_match_picks_the_top_score_and_skips_rejected() {
        val candidates = listOf(
            LrcLibMatch("a", null, score = 3),
            LrcLibMatch("b", null, score = 7),
            LrcLibMatch("c", null, score = -1), // rechazado
        )
        assertEquals("b", bestLyricMatch(candidates)?.plain)
    }

    @Test
    fun best_lyric_match_is_null_when_all_rejected() {
        assertNull(bestLyricMatch(listOf(LrcLibMatch(null, null, -1))))
        assertNull(bestLyricMatch(emptyList()))
    }

    @Test
    fun strip_synced_timestamps_keeps_text() {
        val synced = "[00:12.34] Never gonna give you up\n[00:15.00] Never gonna let you down\n"
        val plain = stripSyncedTimestamps(synced)
        assertEquals("Never gonna give you up\nNever gonna let you down", plain)
    }

    @Test
    fun strip_timestamps_on_plain_text_is_a_noop() {
        assertEquals("just words", stripSyncedTimestamps("just words"))
    }
}
