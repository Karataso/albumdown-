package com.albumdown.dl

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Port de albumdown:_lrclib_lookup (albumdown:840-891) y fetch_and_store_lyrics
// (albumdown:920-948). La parte pura — puntuar candidatos y derivar la letra
// plana desde la sincronizada — vive acá sin Android ni red, testeable. El
// fetch es IO y lo hace lrclibLookup().
//
// LRCLIB no necesita API key y pide ir con User-Agent de algo con nombre.

private const val UA = "albumdown-android/1.0 (+https://github.com/Karataso/albumdown)"

/** Un candidato de LRCLIB ya puntuado. */
data class LrcLibMatch(
    val plain: String?,
    val synced: String?,
    val score: Int,
)

/**
 * Puntuación de un candidato de LRCLIB (albumdown:863-886): duración (con
 * corte a ±15s — más alla es otra canción), artista (case-insensitive, subcadena
 * en cualquier dirección), letra sincronizada (+2) y nombre de álbum (+1).
 * Devuelve -1 si no sirve (instrumental, sin letra, o duración imposible).
 */
fun scoreLyricCandidate(
    durationSec: Int,
    artist: String,
    album: String?,
    candidateDuration: Int,
    candidateArtist: String,
    candidateAlbum: String?,
    hasSynced: Boolean,
    hasPlain: Boolean,
): Int {
    if (!hasSynced && !hasPlain) return -1
    var score = 0
    if (durationSec > 0 && candidateDuration > 0) {
        val diff = kotlin.math.abs(candidateDuration - durationSec)
        if (diff > 15) return -1 // probablemente otra canción
        score += when {
            diff <= 2 -> 4
            diff <= 5 -> 3
            else -> 1
        }
    }
    val ca = candidateArtist.lowercase()
    val a = artist.lowercase()
    if (ca.isNotEmpty() && (ca == a || a in ca || ca in a)) score += 2
    if (hasSynced) score += 2
    val candAlbum = candidateAlbum?.lowercase()
    if (!album.isNullOrBlank() && !candAlbum.isNullOrBlank() && album.lowercase() in candAlbum) score += 1
    return score
}

/**
 * Mejor candidato de una lista ya puntuada (albumdown:888-891: sort por score
 * descendente y quedarse con el primero). Los scores ya vienen calculados, así
 * que acá solo es maxByOrNull.
 */
fun bestLyricMatch(candidates: List<LrcLibMatch>): LrcLibMatch? =
    candidates.filter { it.score >= 0 }.maxByOrNull { it.score }

/** Deriva la letra plana quitando los timestamps [mm:ss.xx] (albumdown:933-935). */
fun stripSyncedTimestamps(synced: String): String =
    Regex("""^\[[^\]]+\]\s*""", RegexOption.MULTILINE).replace(synced, "").trim()

// ── IO ────────────────────────────────────────────────────────────

/**
 * Busca la letra en LRCLIB. Devuelve (plana, sincronizada) — ambas pueden ser
 * null. El primer intento es por título+artista exactos; si no, búsqueda libre.
 */
fun lrclibLookup(title: String, artist: String, album: String?, durationSec: Int): Pair<String?, String?> {
    var results = lrclibSearch(trackName = title, artistName = artist)
    if (results.isEmpty()) results = lrclibSearch(query = "$title $artist")
    if (results.isEmpty()) return null to null

    val matches = results.mapNotNull { r ->
        if (r.optBoolean("instrumental")) return@mapNotNull null
        val plain = r.optString("plainLyrics").ifBlank { null }
        val synced = r.optString("syncedLyrics").ifBlank { null }
        val score = scoreLyricCandidate(
            durationSec = durationSec,
            artist = artist,
            album = album,
            candidateDuration = r.optInt("duration"),
            candidateArtist = r.optString("artistName"),
            candidateAlbum = r.optString("albumName").ifBlank { null },
            hasSynced = synced != null,
            hasPlain = plain != null,
        )
        if (score < 0) null else LrcLibMatch(plain, synced, score)
    }
    val best = bestLyricMatch(matches) ?: return null to null
    return best.plain to best.synced
}

private fun lrclibSearch(trackName: String? = null, artistName: String? = null, query: String? = null): List<JSONObject> {
    val params = buildMap {
        trackName?.let { put("track_name", it) }
        artistName?.let { put("artist_name", it) }
        query?.let { put("q", it) }
    }
    if (params.isEmpty()) return emptyList()
    val qs = params.entries.joinToString("&") { (k, v) ->
        "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
    }
    return try {
        val conn = URL("https://lrclib.net/api/search?$qs").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", UA)
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val arr = org.json.JSONArray(body)
        (0 until arr.length()).map { arr.getJSONObject(it) }
    } catch (_: Exception) {
        emptyList()
    }
}
