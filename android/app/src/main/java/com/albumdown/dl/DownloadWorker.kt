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
import com.albumdown.R
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

    // Workspace propio de ESTE trabajo. Los nombres fijos en cacheDir
    // (__art__.jpg, __tmp_1__…) chocaban si corrían dos álbumes: la cola única
    // ya lo impedía, pero esto ataca la causa de raíz y hace trivial la limpieza.
    private val workDir: File by lazy {
        File(applicationContext.cacheDir, "dl-$id").apply { mkdirs() }
    }

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
            // Todo el temporal de este trabajo, haya salido bien o no: nada de
            // restos de descargas interrumpidas acumulándose en caché.
            workDir.deleteRecursively()
        }
    }

    private suspend fun runDownload(): Result {
        setForeground(makeForeground())
        ensureInit(applicationContext)
        // Basura con el esquema viejo (nombres fijos en cacheDir) de runs
        // anteriores interrumpidos: si no, __tmp_* y __fin_* se acumulan.
        applicationContext.cacheDir.listFiles { f ->
            f.name.startsWith("__tmp_") || f.name.startsWith("__fin_") ||
                f.name.startsWith("__art__")
        }?.forEach { it.delete() }
        maybeUpdateYtDlp() // 1 vez por semana, falla en silencio
        val url = inputData.getString(WorkContract.IN_URL).orEmpty()
        if (url.isEmpty()) return Result.failure()

        // Ajustes capturados al encolar (WorkContract): calidad, --safe, letras,
        // portada y cookies. Todo lo que el CLI exige por flag.
        val quality = inputData.getInt(WorkContract.IN_QUALITY, 0).coerceIn(0, 10)
        val safe = inputData.getBoolean(WorkContract.IN_SAFE, false)
        val wantLyrics = inputData.getBoolean(WorkContract.IN_LYRICS, true)
        val wantArt = inputData.getBoolean(WorkContract.IN_ART, true)
        val cookies = inputData.getString(WorkContract.IN_COOKIES).orEmpty()
            .takeIf { File(it).exists() }

        val album = try {
            resolveAlbum(applicationContext, url)
        } catch (e: DlError) {
            // Un bot check en la lectura no es "link inválido": se dice como tal.
            return Result.failure(WorkContract.failure(applicationContext.getString(failureUserMessageRes(e.kind))))
        } ?: return Result.failure(WorkContract.failure(applicationContext.getString(R.string.err_no_album)))
        if (album.tracks.isEmpty()) return Result.failure(WorkContract.failure(applicationContext.getString(R.string.err_no_tracks)))

        val relPath = "${Environment.DIRECTORY_MUSIC}/AlbumDown/${sanitize(album.artist)}/${sanitize(album.name)}"
        var ok = 0
        var skipped = 0
        val failed = mutableListOf<String>()

        // Selección del usuario (preview): índices originales a bajar; vacío/ausente = todo.
        val indices = inputData.getString(WorkContract.IN_INDICES)?.split(",")?.mapNotNull { it.toIntOrNull() }
        val chosen = if (indices.isNullOrEmpty()) album.tracks.withIndex().toList()
        else album.tracks.withIndex().filter { it.index in indices }
        if (chosen.isEmpty()) return Result.failure(WorkContract.failure(applicationContext.getString(R.string.err_no_selection)))

        // Progreso inicial: de acá sale el nombre del álbum y el total para la
        // UI (también si la app murió y vuelve a adjuntarse al trabajo).
        setProgress(WorkContract.progress(0, chosen.size, "", album.name))

        // Portada una vez para todo el álbum, con la MISMA cascada del CLI
        // (albumdown:1134-1145): candidatos de mayor a menor y gana el primero
        // que baje. --no-art la salta.
        val artFile = if (wantArt) {
            album.coverUrls.ifEmpty { listOf(album.coverUrl) }
                .firstNotNullOfOrNull { u -> downloadBytes(u)?.let { normalizeArt(it) } }
        } else null

        var processed = 0
        // Pistas con letra embebida: viaja al resumen final (albumdown:1377-1378)
        // para que "0 con letras" se VEA en vez de fallar en silencio.
        var withLyrics = 0
        // Primera vez que YouTube nos tira un 403 en este run: todo lo que sigue
        // corre más suave en vez de seguir pidiendo (albumdown:1150 block_seen).
        var blockSeen = false
        for ((i, t) in chosen) {
            val num = i + 1
            val displayName = "%02d. %s.mp3".format(num, sanitize(t.title))
            val progressLabel = applicationContext.getString(
                R.string.notif_progress, processed + 1, chosen.size, t.title,
            )
            setProgress(WorkContract.progress(processed, chosen.size, t.title, album.name))
            // Notificación con la pista en curso: sin esto la notificación se queda
            // clavada en "Descargando álbum…" durante 10 minutos.
            setForeground(makeForeground(progressLabel))

            if (mediaStoreExists(applicationContext, "$relPath/", displayName)) {
                skipped++
                processed++
                continue
            }
            // Retry con política (albumdown:1269-1306): block/gone/transient con
            // backoff con tope; "gone" jamás se reintenta. La pista solo cuenta
            // si su archivo de verdad existe. La búsqueda (Spotify → YouTube)
            // vive dentro del mismo try: ese request también recibe 403s.
            var audio: File? = null
            var kind = FailureKind.TRANSIENT
            var attempt = 0
            while (true) {
                try {
                    // Spotify no tiene URL de audio: se busca el mejor match en YouTube
                    // (título/artista/duración ya son exactos, sacados del embed).
                    val source = t.pageUrl.ifBlank {
                        pickBest(t.title, t.artist, t.durationSec, youtubeSearch3("${t.artist} ${t.title}"))
                            ?.pageUrl.orEmpty()
                    }
                    if (source.isBlank()) { // ni siquiera hay dónde buscarlo
                        kind = FailureKind.GONE
                        break
                    }
                    audio = downloadBestAudio(source, num, safe, cookies)
                    break
                } catch (e: Exception) {
                    kind = (e as? DlError)?.kind ?: classifyFailure(e.message.orEmpty())
                    if (kind == FailureKind.BLOCK) blockSeen = true
                    if (kind == FailureKind.GONE || attempt >= retryTries(kind)) break
                    val wait = retryWait(kind, attempt)
                    attempt++
                    val label = "⏳ ${note(kind)} — reintento " +
                        "$attempt/${retryTries(kind)} en ${wait}s"
                    setProgress(WorkContract.progress(processed, chosen.size, label, album.name))
                    setForeground(makeForeground(label))
                    delay(wait * 1000L)
                }
            }
            if (audio == null) {
                failed.add("%02d. %s (%s)".format(num, t.title, note(kind)))
                processed++
                continue
            }
            // Letras (--lyrics): se buscan antes de taggear para embeberlas en la
            // misma pasada de ffmpeg. Pausa de cortesía a LRCLIB como en el CLI
            // (albumdown:927: 2s en safe, 0.5s normal).
            val lyricsPlain = if (wantLyrics) {
                val (plain, synced) = lrclibLookup(
                    t.title, t.artist.ifEmpty { album.artist }, album.name, t.durationSec,
                )
                delay(if (safe) 2000 else 500)
                plain ?: synced?.let { stripSyncedTimestamps(it) }
            } else null

            val tagged = tagMp3(
                audio, artFile, t.title, t.artist.ifEmpty { album.artist }, album.name,
                num, quality, lyricsPlain,
            )
            if (tagged != null && lyricsPlain != null) withLyrics++
            audio.delete()
            if (tagged != null && insertToMusic(tagged, relPath, displayName)) ok++ else failed.add(
                "%02d. %s (%s)".format(num, t.title, note(kind))
            )
            tagged?.delete()
            processed++
            // Anti-rate-limit (albumdown:1339-1358): 1.5s normal, 10s si YouTube ya
            // nos cachó un 403 en este run, 6-12s al azar en --safe — y nada de
            // esperar tras la última pista.
            if (processed < chosen.size) delay((interTrackDelay(blockSeen, safe) * 1000).toLong())
        }

        artFile?.delete()
        setProgress(WorkContract.progress(chosen.size, chosen.size, "", album.name))
        return Result.success(WorkContract.success(ok, skipped, failed, if (wantLyrics) withLyrics else -1))
    }

    /**
     * Cascada yt-dlp: default → HLS → android client, con 2s entre rutas.
     * Si las 3 rutas fallan LANZA el último error (clasificado) en vez de
     * devolver null — así el caller sabe si es un 403, un video borrado o la
     * red, y decide cuánto esperar (albumdown:1008 Failure Handling).
     */
    private suspend fun downloadBestAudio(pageUrl: String, num: Int, safe: Boolean, cookies: String?): File {
        val out = File(workDir, "__tmp_${num}__.%(ext)s")
        val attempts = listOf(
            arrayOf("-f", "bestaudio/best"),
            arrayOf("-f", "bestaudio[protocol=m3u8]/bestaudio/best"),
            arrayOf("-f", "best", "--extractor-args", "youtube:player_client=android"),
        )
        var last: Exception? = null
        for ((i, fmt) in attempts.withIndex()) {
            // --safe (albumdown:1249-1252): pausas entre requests y antes de cada
            // descarga, para que la cadencia se vea humana y no de script.
            if (i > 0) delay(if (safe) 5000 else 2000) // delay, no Thread.sleep: no bloquea un hilo y respeta la cancelación
            try {
                val req = YoutubeDLRequest(pageUrl)
                req.addOption("--no-playlist")
                req.addOption("--quiet")
                req.addOption("--no-warnings")
                req.addOption("--retries", "3")
                req.addOption("--extractor-retries", "3")
                cookies?.let { req.addOption("--cookies", it) }
                if (safe) {
                    req.addOption("--sleep-requests", "3")     // 3s entre metadata requests
                    req.addOption("--sleep-interval", "5")     // min 5s antes de cada descarga
                    req.addOption("--max-sleep-interval", "15") // hasta 15s (al azar)
                    req.addOption("--fragment-retries", "2")   // menos retries = menos requests
                }
                fmt.toList().chunked(2).forEach { (k, v) -> req.addOption(k, v) }
                req.addOption("-o", out.absolutePath)
                currentProc = "dl$num"
                try {
                    YoutubeDL.getInstance().execute(req, "dl$num") { _, _, _ -> }
                } finally {
                    currentProc = null
                }
                File(workDir, "__tmp_${num}__.m4a").takeIf { it.exists() }?.let { return it }
                File(workDir, "__tmp_${num}__.webm").takeIf { it.exists() }?.let { return it }
                workDir.listFiles { f -> f.name.startsWith("__tmp_${num}__.") }
                    ?.firstOrNull()?.let { return it }
                // yt-dlp salió en 0 pero sin archivo: también es un fallo (el de
                // albumdown confía solo en el archivo en disco, albumdown:1092).
                last = RuntimeException("yt-dlp terminó sin producir audio")
            } catch (e: Exception) {
                last = e
            }
        }
        val err = last ?: RuntimeException("yt-dlp no produjo audio")
        throw DlError(classifyFailure(err.message.orEmpty()), err)
    }

    /**
     * Convierte a MP3 + tags + portada en UN comando ffmpeg (0 libs ID3).
     * Equivale a embed_metadata + embed_art de albumdown.
     */
    /**
     * Convierte a MP3 y taggea en una sola pasada. `quality` va 0-10 (como el
     * --quality del CLI: 0=mejor, 10=peor) y se mapea al -q:a de LAME, que es
     * 0-9 (VBR, 0 = mejor). `lyrics` se embebe como frame USLT (verificado: con
     * -id3v2_version 3 ffmpeg lo escribe como USLT real).
     */
    private fun tagMp3(
        audio: File, art: File?, title: String, artist: String, album: String,
        num: Int, quality: Int, lyrics: String?,
    ): File? {
        val out = File(workDir, "__fin_${num}__.mp3")
        out.delete()
        val lameQ = quality.coerceIn(0, 9) // 10 → 9 (el peor que LAME acepta)
        val cmd = mutableListOf("-y", "-i", audio.absolutePath)
        if (art != null) {
            cmd.addAll(listOf("-i", art.absolutePath, "-map", "0:a", "-map", "1", "-c:v", "copy", "-disposition:v", "attached_pic"))
        }
        cmd.addAll(
            listOf(
                "-c:a", "libmp3lame", "-q:a", "$lameQ", "-id3v2_version", "3",
                "-metadata", "title=$title", "-metadata", "artist=$artist",
                "-metadata", "album=$album", "-metadata", "track=$num",
            )
        )
        if (!lyrics.isNullOrBlank()) {
            // ffmpeg convierte esta llave en un frame USLT del ID3v2.
            cmd.addAll(listOf("-metadata", "lyrics=$lyrics"))
        }
        cmd.add(out.absolutePath)
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
        val f = File(workDir, "__art__.jpg")
        return try {
            if (isJpeg || isPng) {
                f.writeBytes(data)
                f
            } else {
                val src = File(workDir, "__art__.in").apply { writeBytes(data) }
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
            try {
                val copied = resolver.openOutputStream(itemUri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                    true
                } ?: false
                if (!copied) throw IllegalStateException("sin stream de salida")
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
                true
            } catch (e: Exception) {
                // Fila IS_PENDING a medias = un archivo que existe pero que ningún
                // player ni gestor va a mostrar jamás: se borra y se reporta.
                resolver.delete(itemUri, null, null)
                false
            }
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

    /** Nota corta de fallo, ya en el idioma activo (el recurso lleva el nº de intentos). */
    private fun note(kind: FailureKind): String =
        applicationContext.getString(failureNoteRes(kind), retryTries(kind))

    private fun makeForeground(text: String = applicationContext.getString(R.string.notif_default)): ForegroundInfo {
        val mgr = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    "dl",
                    applicationContext.getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = applicationContext.getString(R.string.notif_channel_desc)
                }
            )
        }
        val notif = NotificationCompat.Builder(applicationContext, "dl")
            .setContentTitle("AlbumDown")
            .setContentText(text)
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
