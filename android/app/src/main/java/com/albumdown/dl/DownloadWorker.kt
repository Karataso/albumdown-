package com.albumdown.dl

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Un worker por álbum: resuelve (o recibe tracks), descarga, taggea, guarda en
// Music/AlbumDown/Artista/Álbum vía MediaStore. Foreground obligatorio: un álbum
// tarda más de los ~10 min que WorkManager permite en fondo.

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    @Volatile
    private var currentProc: String? = null

    // onStopped() de CoroutineWorker es final; nos colgamos de la cancelación del Job.
    private fun killCurrent() {
        currentProc?.let { try {
            YoutubeDL.getInstance().destroyProcessById(it)
        } catch (_: Exception) {
        } }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // CoroutineWorker.onStopped() es final → monitor de cancelación: al cancelar
        // despierta al instante y mata el yt-dlp en curso.
        val killer = launch { try { awaitCancellation() } finally { killCurrent() } }
        try {
            runDownload()
        } finally {
            killer.cancel()
        }
    }

    private suspend fun runDownload(): Result {
        setForeground(makeForeground())
        ensureInit(applicationContext)
        maybeUpdateYtDlp() // 1 vez por semana, falla en silencio
        val url = inputData.getString("url").orEmpty()
        if (url.isEmpty()) return Result.failure()

        val album = resolveAlbum(applicationContext, url)
            ?: return Result.failure(workDataOf("error" to "No se pudo leer el álbum (¿link válido?)"))
        if (album.tracks.isEmpty()) return Result.failure(workDataOf("error" to "Sin canciones"))

        val relPath = "${Environment.DIRECTORY_MUSIC}/AlbumDown/${sanitize(album.artist)}/${sanitize(album.name)}"
        var ok = 0
        var skipped = 0
        val failed = mutableListOf<String>()

        // Selección del usuario (preview): índices originales a bajar; vacío/ausente = todo.
        val indices = inputData.getString("indices")?.split(",")?.mapNotNull { it.toIntOrNull() }
        val chosen = if (indices.isNullOrEmpty()) album.tracks.withIndex().toList()
        else album.tracks.withIndex().filter { it.index in indices }
        if (chosen.isEmpty()) return Result.failure(workDataOf("error" to "Sin canciones seleccionadas"))

        // Portada una vez para todo el álbum (albumdown:591).
        val artFile = downloadBytes(album.coverUrl)?.let { normalizeArt(it) }

        var processed = 0
        for ((i, t) in chosen) {
            val num = i + 1
            val displayName = "%02d. %s.mp3".format(num, sanitize(t.title))
            setProgress(workDataOf("done" to processed, "total" to chosen.size, "label" to t.title))

            if (mediaStoreExists(applicationContext, "$relPath/", displayName)) {
                skipped++
                processed++
                continue
            }
            // Spotify no tiene URL de audio: se busca el mejor match en YouTube
            // (título/artista/duración ya son exactos, sacados del embed).
            val source = t.pageUrl.ifBlank {
                pickBest(t.title, t.artist, t.durationSec, youtubeSearch3("${t.artist} ${t.title}"))
                    ?.pageUrl.orEmpty()
            }
            val audio = if (source.isBlank()) null else downloadBestAudio(source, num) // cascada de 3 intentos (bot:537)
            if (audio == null) {
                failed.add("%02d. %s".format(num, t.title))
                processed++
                continue
            }
            val tagged = tagMp3(audio, artFile, t.title, t.artist.ifEmpty { album.artist }, album.name, num)
            audio.delete()
            if (tagged != null && insertToMusic(tagged, relPath, displayName)) ok++ else failed.add("%02d. %s".format(num, t.title))
            tagged?.delete()
            processed++
            delay(1500) // anti-rate-limit (albumdown:795)
        }

        artFile?.delete()
        setProgress(workDataOf("done" to chosen.size, "total" to chosen.size, "label" to ""))
        return Result.success(workDataOf("ok" to ok, "skipped" to skipped, "failed" to failed.joinToString(" | ")))
    }

    /** Cascada yt-dlp: default → HLS → android client, con backoff de 2s. */
    private fun downloadBestAudio(pageUrl: String, num: Int): File? {
        val out = File(applicationContext.cacheDir, "__tmp_${num}__.%(ext)s")
        val attempts = listOf(
            arrayOf("-f", "bestaudio/best"),
            arrayOf("-f", "bestaudio[protocol=m3u8]/bestaudio/best"),
            arrayOf("-f", "best", "--extractor-args", "youtube:player_client=android"),
        )
        for ((i, fmt) in attempts.withIndex()) {
            if (i > 0) Thread.sleep(2000)
            try {
                val req = YoutubeDLRequest(pageUrl)
                req.addOption("--no-playlist")
                req.addOption("--quiet")
                req.addOption("--no-warnings")
                req.addOption("--retries", "3")
                fmt.toList().chunked(2).forEach { (k, v) -> req.addOption(k, v) }
                req.addOption("-o", out.absolutePath)
                currentProc = "dl$num"
                try {
                    YoutubeDL.getInstance().execute(req, "dl$num") { _, _, _ -> }
                } finally {
                    currentProc = null
                }
                File(applicationContext.cacheDir, "__tmp_${num}__.m4a").takeIf { it.exists() }?.let { return it }
                File(applicationContext.cacheDir, "__tmp_${num}__.webm").takeIf { it.exists() }?.let { return it }
                applicationContext.cacheDir.listFiles { f -> f.name.startsWith("__tmp_${num}__.") }
                    ?.firstOrNull()?.let { return it }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Convierte a MP3 + tags + portada en UN comando ffmpeg (0 libs ID3).
     * Equivale a embed_metadata + embed_art de albumdown.
     */
    private fun tagMp3(audio: File, art: File?, title: String, artist: String, album: String, num: Int): File? {
        val out = File(applicationContext.cacheDir, "__fin_${num}__.mp3")
        out.delete()
        val cmd = mutableListOf("-y", "-i", audio.absolutePath)
        if (art != null) {
            cmd.addAll(listOf("-i", art.absolutePath, "-map", "0:a", "-map", "1", "-c:v", "copy", "-disposition:v", "attached_pic"))
        }
        cmd.addAll(
            listOf(
                "-c:a", "libmp3lame", "-q:a", "0", "-id3v2_version", "3",
                "-metadata", "title=$title", "-metadata", "artist=$artist",
                "-metadata", "album=$album", "-metadata", "track=$num",
                out.absolutePath
            )
        )
        return try {
            val s = FFmpegKit.executeWithArguments(cmd.toTypedArray())
            if (ReturnCode.isSuccess(s.returnCode) && out.exists()) out else null
        } catch (_: Exception) {
            null
        }
    }

    /** JPEG/PNG pasan; webp/avif → jpg vía ffmpeg (port de _image_normalize). */
    private fun normalizeArt(data: ByteArray): File? {
        val isJpeg = data.size > 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()
        val isPng = data.size > 8 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte()
        val f = File(applicationContext.cacheDir, "__art__.jpg")
        return try {
            if (isJpeg || isPng) {
                f.writeBytes(data)
                f
            } else {
                val src = File(applicationContext.cacheDir, "__art__.in").apply { writeBytes(data) }
                val s = FFmpegKit.executeWithArguments(arrayOf("-y", "-i", src.absolutePath, "-frames:v", "1", f.absolutePath))
                src.delete()
                if (ReturnCode.isSuccess(s.returnCode)) f else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Guarda en Music/... vía MediaStore (scoped storage: sin WRITE permission). */
    private fun insertToMusic(file: File, relPath: String, displayName: String): Boolean {
        return try {
            val resolver = applicationContext.contentResolver
            val uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val itemUri = resolver.insert(uri, values) ?: return false
            resolver.openOutputStream(itemUri)?.use { out -> file.inputStream().copyTo(out) }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Actualiza yt-dlp como máximo 1 vez/semana. Corre dentro del worker así
     * respeta la regla de solo-WiFi. Falla en silencio: mejor versión vieja que nada. */
    private fun maybeUpdateYtDlp() {
        try {
            val p = applicationContext.getSharedPreferences("albumdown", Context.MODE_PRIVATE)
            if (System.currentTimeMillis() - p.getLong("ytdlp_updated", 0) < 7L * 24 * 3600 * 1000) return
            YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
            p.edit().putLong("ytdlp_updated", System.currentTimeMillis()).apply()
        } catch (_: Exception) {
        }
    }

    private fun makeForeground(): ForegroundInfo {
        val mgr = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            mgr.createNotificationChannel(
                NotificationChannel("dl", "Descargas", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = NotificationCompat.Builder(applicationContext, "dl")
            .setContentTitle("AlbumDown")
            .setContentText("Descargando álbum…")
            .setSmallIcon(android.R.drawable.stat_sys_download) // ponytail: icono del sistema, 0 assets
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(1, notif)
        }
    }
}
