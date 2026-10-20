package com.albumdown.dl

import androidx.work.Data
import androidx.work.workDataOf

// Contrato único entre DownloadWorker y DownloadViewModel. Antes cada lado
// repetía los strings a mano ("done", "label", "failed", "error"…): un typo en
// un solo sitio y el progreso llegaba en silencio a 0 sin ningún error. Si
// falla, falla acá y en tiempo de compilación.

/** Ajustes capturados al encolar: el worker no vuelve a mirar la UI. */
data class SettingsSnapshot(
    val quality: Int = 0,
    val safe: Boolean = false,
    val lyrics: Boolean = true,
    val art: Boolean = true,
    val cookiesPath: String = "",
)

object WorkContract {

    /** Nombre de la cola única: dos álbumes nunca corren a la vez (chocarían). */
    const val UNIQUE_WORK = "album-down"

    // Entrada
    const val IN_URL = "url"
    const val IN_INDICES = "indices"

    // Snapshot de ajustes (ver SettingsRepository): viajan con el trabajo para
    // que un cambio a mitad de descarga no muta la corrida en marcha.
    const val IN_QUALITY = "quality"   // 0-10, --quality
    const val IN_SAFE = "safe"         // --safe
    const val IN_LYRICS = "lyrics"     // --lyrics
    const val IN_ART = "art"           // default on; --no-art
    const val IN_COOKIES = "cookies"   // ruta al cookies.txt, o vacío

    // Progreso (lo publica el worker; la UI lo lee — el álbum y el total salen
    // de acá, no de los parámetros que la UI adivinaba al observar).
    const val PR_DONE = "done"
    const val PR_TOTAL = "total"
    const val PR_LABEL = "label"
    const val PR_ALBUM = "album"

    // Salida
    const val OUT_OK = "ok"
    const val OUT_SKIPPED = "skipped"
    const val OUT_FAILED = "failed"
    const val OUT_ERROR = "error"

    fun input(url: String, indices: List<Int>, s: SettingsSnapshot): Data = workDataOf(
        IN_URL to url,
        IN_INDICES to indices.joinToString(","),
        IN_QUALITY to s.quality,
        IN_SAFE to s.safe,
        IN_LYRICS to s.lyrics,
        IN_ART to s.art,
        IN_COOKIES to s.cookiesPath,
    )

    fun progress(done: Int, total: Int, label: String, album: String): Data =
        workDataOf(PR_DONE to done, PR_TOTAL to total, PR_LABEL to label, PR_ALBUM to album)

    fun success(ok: Int, skipped: Int, failed: List<String>): Data =
        workDataOf(OUT_OK to ok, OUT_SKIPPED to skipped, OUT_FAILED to failed.joinToString(" | "))

    fun failure(message: String): Data = workDataOf(OUT_ERROR to message)
}
