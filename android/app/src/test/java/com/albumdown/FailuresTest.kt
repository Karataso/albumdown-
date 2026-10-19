package com.albumdown

import com.albumdown.dl.DlError
import com.albumdown.dl.FailureKind
import com.albumdown.dl.albumDelay
import com.albumdown.dl.classifyFailure
import com.albumdown.dl.failureNote
import com.albumdown.dl.failureUserMessage
import com.albumdown.dl.interTrackDelay
import com.albumdown.dl.retryTries
import com.albumdown.dl.retryWait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Port del "Failure Handling" de albumdown (albumdown:1008-1075) y de
// _album_delay (albumdown:1679-1693). Sin emulador: ./gradlew testDebugUnitTest

class FailuresTest {

    // ── classifyFailure ────────────────────────────────────────────

    @Test
    fun classify_bot_check_is_block() {
        assertEquals(
            FailureKind.BLOCK,
            classifyFailure("ERROR: [youtube] dQw4w9WgXcQ: Sign in to confirm you're not a bot. Use --cookies"),
        )
        assertEquals(FailureKind.BLOCK, classifyFailure("ERROR: HTTP Error 403: Forbidden"))
        assertEquals(FailureKind.BLOCK, classifyFailure("unable to download webpage: HTTP Error 429: Too Many Requests"))
        assertEquals(FailureKind.BLOCK, classifyFailure("The provided cookies are no longer valid"))
        assertEquals(FailureKind.BLOCK, classifyFailure("Please sign in"))
    }

    @Test
    fun classify_unavailable_is_gone() {
        assertEquals(FailureKind.GONE, classifyFailure("ERROR: [youtube] abc: Video unavailable"))
        assertEquals(FailureKind.GONE, classifyFailure("This video is private"))
        assertEquals(FailureKind.GONE, classifyFailure("The video is not available in your country"))
        // "Sign in to confirm your age" cae en el marcador de block, no en el de
        // gone: se compara contra BLOCK_MARKERS primero (misma regla que el CLI).
        assertEquals(FailureKind.BLOCK, classifyFailure("Sign in to confirm your age"))
    }

    @Test
    fun classify_network_is_transient() {
        assertEquals(FailureKind.TRANSIENT, classifyFailure("Connection reset by peer"))
        assertEquals(FailureKind.TRANSIENT, classifyFailure("HTTP Error 503: Service Unavailable"))
        assertEquals(FailureKind.TRANSIENT, classifyFailure(""))
        assertEquals(FailureKind.TRANSIENT, classifyFailure("nonsense error"))
    }

    @Test
    fun classify_is_case_insensitive() {
        assertEquals(FailureKind.BLOCK, classifyFailure("SIGN IN TO CONFIRM YOU'RE NOT A BOT"))
        assertEquals(FailureKind.GONE, classifyFailure("VIDEO UNAVAILABLE"))
    }

    // ── retry policy: intentos y backoff con tope ──────────────────

    @Test
    fun tries_per_kind() {
        assertEquals(3, retryTries(FailureKind.BLOCK))     // 3 reintentos y basta
        assertEquals(6, retryTries(FailureKind.TRANSIENT))
        assertEquals(0, retryTries(FailureKind.GONE))      // no se reintenta nunca
    }

    @Test
    fun block_backoff_30_60_120_con_tope() {
        assertEquals(30, retryWait(FailureKind.BLOCK, 0))
        assertEquals(60, retryWait(FailureKind.BLOCK, 1))
        assertEquals(120, retryWait(FailureKind.BLOCK, 2))
        // El tope (cap) nunca se pasa, por mucho intento que sea
        assertEquals(120, retryWait(FailureKind.BLOCK, 5))
        assertEquals(120, retryWait(FailureKind.BLOCK, 99))
    }

    @Test
    fun transient_backoff_2_hasta_30() {
        assertEquals(2, retryWait(FailureKind.TRANSIENT, 0))
        assertEquals(4, retryWait(FailureKind.TRANSIENT, 1))
        assertEquals(8, retryWait(FailureKind.TRANSIENT, 2))
        assertEquals(16, retryWait(FailureKind.TRANSIENT, 3))
        assertEquals(30, retryWait(FailureKind.TRANSIENT, 4)) // 2·2^4=32 → cap 30
        assertEquals(30, retryWait(FailureKind.TRANSIENT, 5))
        assertEquals(30, retryWait(FailureKind.TRANSIENT, 50))
    }

    @Test
    fun gone_never_waits() {
        assertEquals(0, retryWait(FailureKind.GONE, 0))
    }

    // ── mensajes ───────────────────────────────────────────────────

    @Test
    fun notes_are_distinct_and_non_empty() {
        val notes = FailureKind.entries.map(::failureNote)
        assertTrue(notes.all { it.isNotBlank() })
        assertEquals(notes.size, notes.distinct().size)
    }

    @Test
    fun user_messages_are_distinct_and_mention_the_cause() {
        val msgs = FailureKind.entries.map(::failureUserMessage)
        assertTrue(msgs.all { it.isNotBlank() })
        assertEquals(msgs.size, msgs.distinct().size)
        assertTrue(failureUserMessage(FailureKind.BLOCK).contains("403"))
    }

    @Test
    fun dl_error_carries_its_kind() {
        val e = DlError(FailureKind.BLOCK, RuntimeException("HTTP Error 403: Forbidden"))
        assertEquals(FailureKind.BLOCK, e.kind)
        assertEquals("HTTP Error 403: Forbidden", e.message)
        // Y el clasificador le daría el mismo sin saber nada del objeto
        assertEquals(FailureKind.BLOCK, classifyFailure(e.message.orEmpty()))
    }

    // ── delays ─────────────────────────────────────────────────────

    @Test
    fun inter_track_delay_normal_vs_bloqueado() {
        assertEquals(1.5, interTrackDelay(blocked = false), 1e-9)   // albumdown:1358
        assertEquals(10.0, interTrackDelay(blocked = true), 1e-9)   // albumdown:1354-1356
    }

    @Test
    fun inter_track_delay_safe_is_random_6_12() {
        repeat(500) {
            val d = interTrackDelay(blocked = false, safe = true)
            assertTrue("delay fuera de rango: $d", d >= 6.0 && d < 12.0)
        }
    }

    @Test
    fun album_delay_blocked_is_random_20_40() {
        repeat(500) {
            val d = albumDelay(blocked = true)
            assertTrue("delay fuera de rango: $d", d >= 20.0 && d < 40.0)
        }
    }

    @Test
    fun album_delay_normal_is_3_and_safe_6_12() {
        assertEquals(3.0, albumDelay(blocked = false), 1e-9)
        repeat(500) {
            val d = albumDelay(blocked = false, safe = true)
            assertTrue("delay fuera de rango: $d", d >= 6.0 && d < 12.0)
        }
    }

    @Test
    fun delays_really_vary() {
        // Si no varía, "randomizado" es mentira y todos los runs esperan igual.
        val samples = (1..200).map { albumDelay(blocked = true) }
        assertTrue("espera siempre el mismo valor: ${samples.first()}", samples.distinct().size > 1)
        assertTrue(samples.min() < samples.max())
    }
}
