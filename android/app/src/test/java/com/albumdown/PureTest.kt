package com.albumdown

import com.albumdown.dl.classifyUrl
import com.albumdown.dl.cleanArtist
import com.albumdown.dl.cleanPlaylistTitle
import com.albumdown.dl.formatDuration
import com.albumdown.dl.mostCommonCase
import com.albumdown.dl.parseSpotifyUrl
import com.albumdown.dl.pickBest
import com.albumdown.dl.ResolvedTrack
import com.albumdown.dl.sanitize
import com.albumdown.dl.UrlKind
import com.albumdown.dl.ytThumbFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Lógica pura: corre sin emulador (`./gradlew testDebugUnitTest`).

class PureTest {

    @Test
    fun classify_youtube() {
        assertEquals(UrlKind.YOUTUBE, classifyUrl("https://music.youtube.com/playlist?list=PLxxx"))
        assertEquals(UrlKind.YOUTUBE, classifyUrl("https://www.youtube.com/watch?v=abc&list=PLxxx"))
        assertEquals(UrlKind.YOUTUBE, classifyUrl("youtube.com/playlist?list=PLxxx"))
        assertEquals(UrlKind.YOUTUBE, classifyUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(UrlKind.YOUTUBE, classifyUrl("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(UrlKind.YOUTUBE, classifyUrl("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun classify_spotify_soundcloud() {
        assertEquals(UrlKind.SPOTIFY, classifyUrl("https://open.spotify.com/album/abc123"))
        assertEquals(UrlKind.SPOTIFY, classifyUrl("https://open.spotify.com/intl-es/track/abc123?si=x"))
        assertEquals(UrlKind.SOUNDCLOUD, classifyUrl("https://soundcloud.com/artista/sets/album"))
        assertEquals(UrlKind.SOUNDCLOUD, classifyUrl("https://example.com/a.mp3"))
        assertEquals(UrlKind.UNSUPPORTED, classifyUrl("hola"))
        assertEquals(UrlKind.UNSUPPORTED, classifyUrl("https://open.spotify.com/episode/abc"))
    }

    @Test
    fun spotify_parse() {
        assertEquals("album" to "abc123", parseSpotifyUrl("https://open.spotify.com/album/abc123"))
        assertEquals("track" to "z9", parseSpotifyUrl("https://open.spotify.com/intl-es/track/z9"))
        assertNull(parseSpotifyUrl("https://youtube.com/playlist?list=x"))
    }

    @Test
    fun yt_thumb_from_url() {
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", ytThumbFrom("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("https://i.ytimg.com/vi/abc12345678/mqdefault.jpg", ytThumbFrom("https://youtu.be/abc12345678"))
        assertNull(ytThumbFrom("https://soundcloud.com/artista/tema"))
    }

    @Test
    fun cleaners() {
        assertEquals("Soda Stereo", cleanArtist(" Soda Stereo - Topic"))
        assertEquals("Unknown Artist", cleanArtist(""))
        assertEquals("Lámina Once", cleanPlaylistTitle("Album - Lámina Once"))
        assertEquals("a_b", sanitize("a/b"))
        assertEquals("Unknown", sanitize("///"))
    }

    @Test
    fun durations() {
        assertEquals("3:05", formatDuration(185))
        assertEquals("1:02:30", formatDuration(3750))
        assertEquals("??:??", formatDuration(0))
    }

    @Test
    fun casing() {
        assertEquals("El Cuarteto de Nos", mostCommonCase(listOf("El Cuarteto de Nos", "El Cuarteto De Nos", "El Cuarteto de Nos")))
        assertNull(mostCommonCase(emptyList()))
    }

    @Test
    fun best_match_prefers_duration_and_artist() {
        val cands = listOf(
            ResolvedTrack("X (en vivo)", "Artista", 400, "u1"),
            ResolvedTrack("X", "Artista", 185, "u2"),
            ResolvedTrack("X", "Otro", 184, "u3"),
        )
        assertEquals("u2", pickBest("X", "Artista", 185, cands)?.pageUrl)
    }
}
