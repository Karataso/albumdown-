package com.albumdown.dl

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

data class Album(
    val artist: String,
    val name: String,
    val tracks: List<ResolvedTrack>,
    /** La portada mayor: la que muestra la UI de preview. */
    val coverUrl: String = "",
    /**
     * Candidatos de portada de mayor a menor (playlist-level, miniaturas de
     * entrada, maxres sintético…). El worker baja el primero que funcione —
     * mismo cascada que cover_urls en albumdown:549-566.
     */
    val coverUrls: List<String> = emptyList(),
)

private val inited = AtomicBoolean(false)

/** Inicializa yt-dlp + ffmpeg una vez (llamar desde IO). */
fun ensureInit(ctx: Context) {
    if (inited.getAndSet(true)) return
    try {
        YoutubeDL.getInstance().init(ctx.applicationContext)
        FFmpeg.getInstance().init(ctx.applicationContext)
    } catch (e: Exception) {
        inited.set(false)
        throw e
    }
}

/** Versión actual del extractor yt-dlp (null si falla). */
suspend fun ytDlpVersion(ctx: Context): String? = withContext(Dispatchers.IO) {
    try {
        ensureInit(ctx)
        YoutubeDL.getInstance().version(ctx.applicationContext)
    } catch (_: Exception) {
        null
    }
}

/** Resultado de actualizar yt-dlp: la UI lo formatea en el idioma activo. */
sealed interface YtDlpUpdate {
    data class Updated(val version: String) : YtDlpUpdate
    data class UpToDate(val version: String) : YtDlpUpdate
    data class Failed(val detail: String) : YtDlpUpdate
}

/**
 * Actualiza yt-dlp a la última release.
 * Bloqueante y pesado (baja ~30MB, solo si hay release nueva) → siempre en IO.
 */
suspend fun updateYtDlp(ctx: Context): YtDlpUpdate = withContext(Dispatchers.IO) {
    try {
        ensureInit(ctx)
        val st = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext)
        val v = YoutubeDL.getInstance().version(ctx.applicationContext) ?: "?"
        if (st == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) YtDlpUpdate.UpToDate(v)
        else YtDlpUpdate.Updated(v)
    } catch (e: Exception) {
        YtDlpUpdate.Failed((e.message ?: "?").take(80))
    }
}

/**
 * Resuelve cualquier URL soportada a un Álbum.
 * YouTube/SoundCloud → lista plana yt-dlp (port de GenericExtractor.ts:51).
 * Spotify → scrape del embed público (sin credenciales); el audio se busca en
 * YouTube al descargar (Spotify tiene DRM, de la página solo sale metadata).
 */
suspend fun resolveAlbum(ctx: Context, url: String): Album? = withContext(Dispatchers.IO) {
    ensureInit(ctx)
    return@withContext when (classifyUrl(url)) {
        UrlKind.YOUTUBE, UrlKind.SOUNDCLOUD -> resolveFlatPlaylist(url)
        UrlKind.SPOTIFY -> resolveSpotify(url)
        else -> null
    }
}

private data class Raw(
    val title: String, val uploader: String, val album: String,
    val artist: String, val page: String, val thumb: String, val dur: Int,
    /** thumbnails[] de la entrada: (ancho, url), de mayor a menor. */
    val cands: List<Pair<Int, String>> = emptyList(),
)

/** thumbnails[] de un dict → (ancho, url) ordenado de mayor a menor. */
private fun thumbCands(arr: JSONArray?): List<Pair<Int, String>> =
    if (arr == null) emptyList()
    else (0 until arr.length()).mapNotNull { i ->
        val t = arr.optJSONObject(i) ?: return@mapNotNull null
        t.optString("url").takeIf { u -> u.isNotEmpty() }?.let { t.optInt("width", 0) to it }
    }.sortedByDescending { it.first }

/** Id de un watch URL de YouTube, para derivar maxresdefault sin otra llamada. */
private val YT_VID_RE = Regex("""(?:v=|vi/|youtu\.be/)([\w-]{11})\b""")

private suspend fun resolveFlatPlaylist(url: String): Album? {
    val req = YoutubeDLRequest(url)
    req.addOption("--flat-playlist")
    req.addOption("--skip-download")
    req.addOption("--quiet")
    req.addOption("--no-warnings")
    // --dump-single-json en vez de --print: además de las entradas trae el dict
    // COMPLETO del playlist — su título (fallback del álbum, como hace el CLI en
    // albumdown:479-505) y sus thumbnails (la portada real: 544² en álbumes
    // OLAK5uy de YT Music). Con --print todo eso se perdía.
    req.addOption("--dump-single-json")
    val out = try {
        // processId no-nulo + callback vacío: evita NPEs dentro de la lib.
        YoutubeDL.getInstance().execute(req, "resolve") { _, _, _ -> }.out.orEmpty()
    } catch (e: Exception) {
        // Un bot check leyendo la lista NO es "link inválido": se clasifica
        // (403 / borrado / red) y se sube para que la UI lo diga como tal.
        throw DlError(classifyFailure(e.message.orEmpty()), e)
    }
    val root = try {
        JSONObject(out)
    } catch (_: Exception) {
        return null // la salida no es JSON → link que no sabemos resolver
    }

    // Video suelto → "álbum" de 1 pista (port de fetch_metadata, albumdown:476-483).
    val listTitle = root.optString("title")
    val entriesArr = root.optJSONArray("entries")
    val dicts = if (entriesArr == null) {
        if (listTitle.isBlank()) return null
        listOf(root)
    } else {
        (0 until entriesArr.length()).mapNotNull { entriesArr.optJSONObject(it) }
    }

    val parsed = dicts.mapNotNull { e ->
        val title = e.optString("title")
        if (title.isEmpty()) return@mapNotNull null
        Raw(
            title = title,
            uploader = e.optString("uploader").ifEmpty { e.optString("channel") },
            album = e.optString("album"),
            artist = e.optString("artist"),
            // En plana webpage_url no existe: la URL vive en url (verificado en
            // yt-dlp; --print sí la computaba, por eso antes funcionaba).
            page = e.optString("webpage_url").ifEmpty {
                e.optString("url").takeIf { u -> u.startsWith("http") }.orEmpty()
            },
            thumb = e.optString("thumbnail"),
            dur = e.optDouble("duration", -1.0).let { if (it.isNaN() || it < 0) 0 else it.toInt() },
            cands = thumbCands(e.optJSONArray("thumbnails")),
        )
    }
    if (parsed.isEmpty()) return null
    val raws = enrichThumbs(parsed, url)

    // Álbum: tag por-entry primero (más limpio que el título de la playlist), igual que albumdown.
    var album = raws.firstNotNullOfOrNull { it.album.takeIf { a -> a.isNotEmpty() && a != "NA" } }?.trim().orEmpty()
    // Sin tag (lo normal en playlists comunes): el TÍTULO DEL PLAYLIST, como el
    // CLI (albumdown:479-505). Antes caíamos al título de la primera entrada y
    // salía "[ost] dreamseeker" en vez del nombre real del álbum.
    if (album.isEmpty()) album = cleanPlaylistTitle(listTitle)
    if (album.isEmpty()) album = cleanPlaylistTitle(raws.first().title)
    if (album == "Unknown Album" && url.contains("/likes", true)) album = "Me gusta"

    // Artista: tag por-entry → uploader más común → primer uploader.
    val entryArtists = raws.mapNotNull {
        val a = it.artist.takeIf { a -> a.isNotEmpty() && a != "NA" }?.let(::cleanArtist)
        a?.split(",")?.firstOrNull()?.trim()?.takeIf { s -> s.isNotEmpty() }
    }
    var artist = mostCommonCase(entryArtists).orEmpty()
    if (artist.isEmpty()) {
        artist = raws.map { cleanArtist(it.uploader) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            .orEmpty().ifEmpty { "Unknown Artist" }
    }
    // Unifica mayorización en todos los entries (port de _apply_artist_case).
    val key = artist.lowercase()

    val tracks = raws.mapIndexed { i, r ->
        var ta = entryArtists.getOrNull(i).orEmpty()
        if (ta.isEmpty()) ta = cleanArtist(r.uploader)
        if (ta.lowercase() == key) ta = artist
        ResolvedTrack(
            title = r.title, artist = ta, durationSec = r.dur,
            pageUrl = r.page.ifEmpty { url },
            coverUrl = r.thumb.takeIf { it.isNotEmpty() && it != "NA" }.orEmpty(),
        )
    }
    // Portada (port del cascada cover_urls, albumdown:549-566): PRIMERO los
    // thumbnails del playlist — el mismo orden que usa el CLI, así que sale la
    // MISMA carátula que el script (en álbumes de YT Music: la portada oficial
    // cuadrada). El resto (maxres del video, miniaturas por entrada, oEmbed) va
    // de respaldo ordenado por ancho, por si el playlist no trae ninguno.
    val plCands = thumbCands(root.optJSONArray("thumbnails")).toMutableList()
    root.optString("thumbnail").takeIf { it.isNotEmpty() }?.let { u ->
        // info.thumbnail como último recurso del playlist, como en el CLI.
        if (plCands.none { it.second == u }) plCands += 0 to u
    }
    val synth = raws.mapNotNull { r -> YT_VID_RE.find(r.page)?.groupValues?.get(1) }
        .distinct()
        .flatMap { id ->
            // maxres primero y hq720 de respaldo: el "desnudo" suele 404
            // (mismo motivo que el comentario del CLI en albumdown:550-552).
            listOf(
                1280 to "https://i.ytimg.com/vi/$id/maxresdefault.jpg",
                1280 to "https://i.ytimg.com/vi/$id/hq720.jpg",
            )
        }
    val singles = raws.mapNotNull { r ->
        r.thumb.takeIf { it.isNotEmpty() && it != "NA" }?.let {
            // Último recurso: lo que derivó enrichThumbs (YouTube → mqdefault
            // 320px; SoundCloud → oEmbed t500x500).
            (if (it.contains("soundcloud.com", true)) 500 else 320) to it
        }
    }
    val rest = (raws.flatMap { it.cands } + synth + singles)
        .sortedByDescending { it.first }
        .map { it.second }
    val coverUrls = (plCands.map { it.second } + rest).distinct()
    return Album(
        artist, album.ifEmpty { "Unknown Album" }, tracks,
        coverUrls.firstOrNull().orEmpty(), coverUrls,
    )
}

/**
 * yt-dlp en lista plana ya no devuelve thumbnail (y en SoundCloud ni título):
 * YouTube → se deriva del id del video (gratis); SoundCloud → oEmbed por track
 * (título/autor/portada t500x500; no da duración). 8 en paralelo.
 */
private suspend fun enrichThumbs(raws: List<Raw>, listUrl: String): List<Raw> =
    raws.chunked(8).flatMap { chunk ->
        coroutineScope {
            chunk.map { r ->
                async {
                    var t = r
                    val page = r.page.ifEmpty { listUrl }
                    if (t.thumb.isEmpty() || t.thumb == "NA") {
                        ytThumbFrom(page)?.let { t = t.copy(thumb = it) }
                    }
                    val isSc = page.contains("soundcloud.com", true) || page.contains("snd.sc", true)
                    if (isSc && (t.thumb == "NA" || t.thumb.isEmpty() || t.title == "NA")) {
                        scOembed(page)?.let { (title, author, th) ->
                            if (title.isNotBlank()) t = t.copy(title = t.title.takeIf { x -> x != "NA" && x.isNotBlank() } ?: title)
                            if (author.isNotBlank()) t = t.copy(uploader = t.uploader.takeIf { x -> x != "NA" && x.isNotBlank() } ?: author)
                            if (th.isNotBlank()) t = t.copy(thumb = th)
                        }
                    }
                    t
                }
            }.awaitAll()
        }
    }

/** oEmbed público de SoundCloud: (título, autor, portada). null si falla. */
private fun scOembed(pageUrl: String): Triple<String, String, String>? = try {
    val conn = URL("https://soundcloud.com/oembed?format=json&url=" + URLEncoder.encode(pageUrl, "UTF-8"))
        .openConnection() as HttpURLConnection
    conn.connectTimeout = 10_000
    conn.readTimeout = 10_000
    if (conn.responseCode != 200) null else {
        val j = JSONObject(conn.inputStream.use { it.bufferedReader().readText() })
        val author = j.optString("author_name")
        // SC agrega " by <uploader>" al final del título del oEmbed.
        Triple(j.optString("title").removeSuffix(" by $author").trim(), author, j.optString("thumbnail_url"))
    }
} catch (_: Exception) {
    null
}

private val NEXT_DATA_RE = Regex("""(?s)<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""")

/**
 * Spotify sin credenciales: scrapea la página pública de embed y saca
 * título/artistas/duración/portada de cada tema. Frágil por diseño: si Spotify
 * cambia la página se rompe (no hay auto-update, a diferencia de yt-dlp).
 */
private suspend fun resolveSpotify(url: String): Album? {
    val (type, id) = parseSpotifyUrl(url) ?: return null
    val html = fetchText("https://open.spotify.com/embed/$type/$id") ?: return null
    val json = NEXT_DATA_RE.find(html)?.groupValues?.get(1) ?: return null
    val entity = try {
        JSONObject(json).getJSONObject("props").getJSONObject("pageProps")
            .getJSONObject("state").getJSONObject("data").getJSONObject("entity")
    } catch (_: Exception) {
        return null
    }

    // Portada: visualIdentity.image (la más grande) → coverArt.sources.
    val cover = entity.optJSONObject("visualIdentity")?.optJSONArray("image")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .maxByOrNull { it.optInt("maxWidth") }?.optString("url")
    } ?: entity.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url").orEmpty()

    val name = entity.optString("name").ifBlank { "Spotify" }
    val sub = entity.optString("subtitle").trim()
    val list = entity.optJSONArray("trackList")

    val tracks = if (list == null) { // link de un track suelto
        listOf(
            ResolvedTrack(
                name, sub.ifBlank { "Unknown Artist" },
                (entity.optLong("duration", 0) / 1000).toInt(), "", cover,
            )
        )
    } else {
        (0 until list.length()).mapNotNull { i ->
            val t = list.optJSONObject(i) ?: return@mapNotNull null
            val title = t.optString("title")
            if (title.isBlank()) return@mapNotNull null
            ResolvedTrack(
                title = title,
                artist = t.optString("subtitle").ifBlank { sub }.ifBlank { "Unknown Artist" },
                durationSec = (t.optLong("duration", 0) / 1000).toInt(),
                pageUrl = "", // se busca en YouTube al descargar
                coverUrl = cover,
            )
        }
    }
    if (tracks.isEmpty()) return null

    // Álbum/track: artista en subtitle ("Daft Punk"). Playlist: si todos comparten
    // artista, ese; si no, "Varios Artistas".
    val artist = if (type != "playlist" && sub.isNotBlank()) sub else tracks.map { it.artist }
        .distinctBy { it.lowercase() }.let { if (it.size == 1) it.first() else "Varios Artistas" }
    return Album(cleanArtist(artist), name, tracks, cover)
}

/** GET simple con User-Agent de browser (Spotify bloquea sin uno). */
private fun fetchText(url: String): String? = try {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15_000
    c.readTimeout = 15_000
    c.setRequestProperty("User-Agent", "Mozilla/5.0")
    if (c.responseCode != 200) null else c.inputStream.use { it.bufferedReader().readText() }
} catch (_: Exception) {
    null
}
