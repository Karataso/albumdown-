package com.albumdown.dl

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// Utilidades de red/MediaStore que usa el DownloadWorker.

/** Descarga bytes de una imagen (portada). null si falla. */
fun downloadBytes(url: String): ByteArray? {
    if (url.isBlank()) return null
    return try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (c.responseCode != 200) null else c.inputStream.readBytes().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}

/** Query de MediaStore: ¿ya existe este archivo en la carpeta del álbum? */
fun mediaStoreExists(ctx: Context, relativePath: String, displayName: String): Boolean {
    return try {
        val uri = android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)
        ctx.contentResolver.query(
            uri, arrayOf(android.provider.MediaStore.MediaColumns._ID),
            "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relativePath, displayName), null
        )?.use { it.count > 0 } ?: false
    } catch (_: Exception) {
        false // sin permiso o error: se re-descarga, no se rompe nada
    }
}

/** Top-3 de YouTube vía `ytsearch3:` (sin API key). Para temas de Spotify. */
suspend fun youtubeSearch3(query: String): List<ResolvedTrack> = withContext(Dispatchers.IO) {
    try {
        val req = YoutubeDLRequest("ytsearch3:${query}")
        req.addOption("--flat-playlist")
        req.addOption("--skip-download")
        req.addOption("--quiet")
        req.addOption("--no-warnings")
        req.addOption("--print", "%(title)s\t%(uploader)s\t%(duration)s\t%(thumbnail)s\t%(webpage_url)s")
        val out = YoutubeDL.getInstance().execute(req, "search") { _, _, _ -> }.out
        out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(3).mapNotNull { line ->
            val p = line.split('\t')
            if (p.isEmpty() || p[0].isEmpty()) return@mapNotNull null
            ResolvedTrack(
                title = p.getOrElse(0) { "" }, artist = cleanArtist(p.getOrElse(1) { "" }),
                durationSec = p.getOrElse(2) { "0" }.toIntOrNull() ?: 0,
                pageUrl = p.getOrElse(4) { "" }, coverUrl = p.getOrElse(3) { "" }.takeIf { it != "NA" }.orEmpty(),
            )
        }.toList()
    } catch (e: Exception) {
        // Error de verdad (403, red, yt-dlp caído): sube clasificado. Los
        // cero resultados no llegan acá — eso sale como lista vacía.
        throw DlError(classifyFailure(e.message.orEmpty()), e)
    }
}
