package com.albumdown.dl

// ponytail: todo lo puro y testeable vive aquí. Cero imports de Android,
// así corre en `testDebugUnitTest` sin emulador.

private val YT_PLAYLIST_RE =
    Regex("""(?:https?://)?(?:www\.)?(?:music\.)?youtube\.com/(?:playlist\?list=|watch\?.*list=)([A-Za-z0-9_-]+)""")
// Video suelto = álbum de 1 track (igual que albumdown single-video).
private val YT_VIDEO_RE =
    Regex("""(?:https?://)?(?:www\.|music\.|m\.)?(?:youtube\.com/watch\?v=|youtu\.be/)([A-Za-z0-9_-]{11})""")
private val SC_RE = Regex("""https?://(?:www\.|m\.)?(soundcloud\.com|snd\.sc)/\S+""", RegexOption.IGNORE_CASE)
private val SPOTIFY_RE = Regex("""open\.spotify\.com/(?:intl-[a-z-]+/)?(track|playlist|album)/([a-zA-Z0-9]+)""")
private val AUDIO_FILE_RE = Regex(""".*\.(mp3|m4a|ogg|opus|wav|flac)(\?.*)?$""", RegexOption.IGNORE_CASE)

enum class UrlKind { YOUTUBE, SOUNDCLOUD, SPOTIFY, UNSUPPORTED }

fun classifyUrl(url: String): UrlKind = when {
    YT_PLAYLIST_RE.containsMatchIn(url) || YT_VIDEO_RE.containsMatchIn(url) -> UrlKind.YOUTUBE
    SPOTIFY_RE.containsMatchIn(url) -> UrlKind.SPOTIFY
    SC_RE.containsMatchIn(url) -> UrlKind.SOUNDCLOUD
    url.startsWith("http") && AUDIO_FILE_RE.matches(url) -> UrlKind.SOUNDCLOUD // audio directo: misma vía genérica
    else -> UrlKind.UNSUPPORTED
}

/** (type, id) de un link de Spotify, o null. Port de SpotifyResolver.ts:13. */
fun parseSpotifyUrl(input: String): Pair<String, String>? {
    val m = SPOTIFY_RE.find(input) ?: return null
    return m.groupValues[1] to m.groupValues[2]
}

private val YT_THUMB_ID_RE = Regex("""(?:v=|youtu\.be/|/shorts/)([A-Za-z0-9_-]{11})""")

/** Thumbnail predecible de YouTube (la lista plana de yt-dlp ya no la trae). */
fun ytThumbFrom(pageUrl: String): String? =
    YT_THUMB_ID_RE.find(pageUrl)?.let { "https://i.ytimg.com/vi/${it.groupValues[1]}/mqdefault.jpg" }

/** Port de albumdown:_clean_artist — quita "- Topic", "- Album", etc. */
fun cleanArtist(name: String): String {
    if (name.isBlank()) return "Unknown Artist"
    val res = Regex("""\s*-\s*(Topic|Album|Single|VEVO)$""", RegexOption.IGNORE_CASE)
        .replace(name, "").trim()
    return res.ifEmpty { "Unknown Artist" }
}

/** Port de albumdown:_clean_playlist_title — quita "Album - X". */
fun cleanPlaylistTitle(name: String): String {
    val res = Regex("""^(full\s+album|album|lp|ep|single)\s*[-–—:]\s*""", RegexOption.IGNORE_CASE)
        .replace(name.trim(), "").trim()
    return res.ifEmpty { "Unknown Album" }
}

/** Port de albumdown:_sanitize — nombre seguro para archivo/MediaStore. */
fun sanitize(name: String): String {
    var s = Regex("""[\\/:*?"<>|]""").replace(name, "_")
    s = Regex("""_+""").replace(s, "_").trim(' ', '_', '.')
    return s.ifEmpty { "Unknown" }
}

fun formatDuration(totalSec: Int): String {
    if (totalSec <= 0) return "??:??"
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Mayorización más común entre variantes ("de Nos" vs "De Nos"). Port de _most_common_case. */
fun mostCommonCase(names: List<String>): String? {
    if (names.isEmpty()) return null
    val groups = names.filter { it.isNotEmpty() }.groupBy { it.lowercase() }
    if (groups.isEmpty()) return null
    val best = groups.values.maxByOrNull { it.size } ?: return null
    return best.groupBy { it }.values.maxByOrNull { it.size }?.first()
}

data class ResolvedTrack(
    val title: String,
    val artist: String,
    val durationSec: Int,
    val pageUrl: String,
    val coverUrl: String = "",
)

/**
 * Elige el mejor candidato de búsqueda por cercanía de duración + artista.
 * Port del scoring de albumdown:_lrclib_lookup (el bot tomaba [0] a ciegas — esto es mejor).
 */
fun pickBest(title: String, artist: String, durationSec: Int, candidates: List<ResolvedTrack>): ResolvedTrack? {
    if (candidates.isEmpty()) return null
    if (durationSec <= 0) return candidates.first()
    val a = artist.lowercase()
    var best: ResolvedTrack? = null
    var bestScore = Int.MIN_VALUE
    for (c in candidates) {
        if (c.durationSec > 0) {
            val diff = kotlin.math.abs(c.durationSec - durationSec)
            if (diff > 15) continue // probablemente otro tema
        }
        var score = 0
        if (c.durationSec > 0) {
            val diff = kotlin.math.abs(c.durationSec - durationSec)
            score += when {
                diff <= 2 -> 4
                diff <= 5 -> 3
                else -> 1
            }
        }
        val ca = c.artist.lowercase()
        if (ca.isNotEmpty() && (ca == a || a in ca || ca in a)) score += 2
        if (score > bestScore) {
            bestScore = score
            best = c
        }
    }
    return best ?: candidates.first()
}
