package com.albumdown.dl

// Port del "Failure Handling" de albumdown (albumdown:1008-1075) y de los
// delays de _album_delay (albumdown:1679-1693). Puro: cero imports de Android,
// corre en `testDebugUnitTest` sin emulador (mismo trato que Media.kt).
//
// YouTube falla de 3 formas muy distintas, y reintentarlas todas igual es la
// razón de que haya que re-ejecutar el programa a mano:
//
//   block      403 / "Sign in to confirm you're not a bot". Chequeo de bot
//              transitorio: esperar y reintentar casi siempre funciona (eso ya
//              lo prueba re-ejecutar el programa), pero solo unas pocas veces.
//   gone       privado, borrado, geo-bloqueado. Ninguna espera lo resucita:
//              falla la pista y sigue de inmediato.
//   transient  timeouts, resets de conexión, 5xx. Backoff corto normal.

enum class FailureKind { BLOCK, GONE, TRANSIENT }

/** Error que ya sabe qué clase de fallo es — nadie tiene que re-adivinarlo después. */
class DlError(val kind: FailureKind, cause: Throwable) : Exception(cause.message, cause)

private val BLOCK_MARKERS = listOf(
    "sign in to confirm",
    "not a bot",
    "403: forbidden",
    "http error 403",
    "http error 429",
    "too many requests",
    "cookies are no longer valid",
    "please sign in",
    "login required",
)

private val GONE_MARKERS = listOf(
    "video unavailable",
    "is unavailable",
    "video is private",
    "has been removed",
    "available in your country",
    "account associated with this video",
    "uploader has closed",
    "no longer available",
    "confirm your age",
    "is not available",
    "copyright",
)

private data class RetryPolicy(val tries: Int, val base: Int, val cap: Int)

private val POLICY = mapOf(
    FailureKind.BLOCK to RetryPolicy(tries = 3, base = 30, cap = 120),   // 30s → 60s → 120s
    FailureKind.TRANSIENT to RetryPolicy(tries = 6, base = 2, cap = 30), // 2s → … → 30s
    FailureKind.GONE to RetryPolicy(tries = 0, base = 0, cap = 0),
)

/** ¿Qué clase de error es? El texto es lo que yt-dlp escupe (stderr / exception). */
fun classifyFailure(text: String): FailureKind {
    val t = text.lowercase()
    return when {
        BLOCK_MARKERS.any { it in t } -> FailureKind.BLOCK
        GONE_MARKERS.any { it in t } -> FailureKind.GONE
        else -> FailureKind.TRANSIENT
    }
}

/** Cuántos reintentos da esa clase de fallo (0 = no reintentar). */
fun retryTries(kind: FailureKind): Int = POLICY.getValue(kind).tries

/** Segundos antes del reintento `attempt` (0-based): backoff exponencial con tope. */
fun retryWait(kind: FailureKind, attempt: Int): Int {
    val p = POLICY.getValue(kind)
    val exp = 1L shl attempt.coerceIn(0, 30)
    return minOf(p.base.toLong() * exp, p.cap.toLong()).toInt()
}

/** Nota corta que va junto al título de la pista fallida en la pantalla de resumen. */
fun failureNote(kind: FailureKind): String = when (kind) {
    FailureKind.BLOCK -> "bloqueado, reintenta en un rato"
    FailureKind.GONE -> "no disponible"
    FailureKind.TRANSIENT -> "se rindió tras reintentos"
}

// TODO(strings.xml): Phase 1 mueve estas cadenas a recursos — igual que el resto
// de la UI, que hoy está hardcodeada en los composables.
/** Mensaje a pantalla completa (UiState.Error / error del worker). */
fun failureUserMessage(kind: FailureKind): String = when (kind) {
    FailureKind.BLOCK -> "YouTube está chequeando (403). Espera un rato y reintenta."
    FailureKind.GONE -> "No está disponible (privado, borrado o no disponible en tu país)."
    FailureKind.TRANSIENT -> "Falla de red. Reintenta en un momento."
}

/**
 * Segundos entre pistas (albumdown:1339-1358): 1.5 normal; 10 si YouTube ya nos
 * tiró un 403 en este run (en vez de seguir pidiendo); 6-12 en safe mode.
 */
fun interTrackDelay(blocked: Boolean, safe: Boolean = false): Double = when {
    safe -> randomBetween(6.0, 12.0)
    blocked -> 10.0
    else -> 1.5
}

/**
 * Segundos entre álbumes (albumdown:1679-1693): 3 normal; 20-40 aleatorios si
 * hubo 403 en el run (le da a YouTube aire real); 6-12 en safe mode.
 * Para el modo artista (fase 2): una discografía son muchas playlists seguidas.
 */
fun albumDelay(blocked: Boolean, safe: Boolean = false): Double = when {
    safe -> randomBetween(6.0, 12.0)
    blocked -> randomBetween(20.0, 40.0)
    else -> 3.0
}

private fun randomBetween(from: Double, until: Double): Double =
    kotlin.random.Random.nextDouble(from, until)
