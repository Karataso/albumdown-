package com.albumdown.dl

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Ajustes de la app, con el mismo vocabulario que los flags del CLI:
 *
 *   wifi_only      (default)   solo descarga con WiFi
 *   skip_preview   (--no-confirm)  bajar todo sin pasar por la selección
 *   quality        (--quality 0-10)  0 = mejor, 10 = archivo más chico
 *   safe           (--safe)    pausas más largas para no parecer bot
 *   lyrics         (--lyrics)  letras de LRCLIB embebidas
 *   art            (default; el CLI tiene --no-art)  portada embebida
 *   cookies        (--cookies FILE)  sesión autenticada
 *
 * StateFlow para que la UI se actualice sola; el worker recibe un *snapshot*
 * por WorkContract, así un cambio a mitad de descarga no muta la corrida.
 */
class SettingsRepository private constructor(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("albumdown", Context.MODE_PRIVATE)
    private val filesDir = ctx.applicationContext.filesDir

    private fun bool(key: String, def: Boolean) = MutableStateFlow(prefs.getBoolean(key, def))
    private fun int(key: String, def: Int) = MutableStateFlow(prefs.getInt(key, def))

    val wifiOnly = bool("wifi_only", true)
    val skipPreview = bool("skip_preview", false)
    val quality = int("quality", 0)      // 0 = mejor, 10 = peor
    val safe = bool("safe", false)
    val lyrics = bool("lyrics", true)
    val art = bool("art", true)

    // ── escritura ─────────────────────────────────────────────────

    private fun put(key: String, v: Boolean, flow: MutableStateFlow<Boolean>) {
        prefs.edit().putBoolean(key, v).apply()
        flow.value = v
    }

    private fun put(key: String, v: Int, flow: MutableStateFlow<Int>) {
        prefs.edit().putInt(key, v).apply()
        flow.value = v
    }

    fun setWifiOnly(v: Boolean) = put("wifi_only", v, wifiOnly)
    fun setSkipPreview(v: Boolean) = put("skip_preview", v, skipPreview)
    fun setQuality(v: Int) = put("quality", v.coerceIn(0, 10), quality)
    fun setSafe(v: Boolean) = put("safe", v, safe)
    fun setLyrics(v: Boolean) = put("lyrics", v, lyrics)
    fun setArt(v: Boolean) = put("art", v, art)

    // ── cookies (--cookies FILE) ──────────────────────────────────

    /** Archivo cookies.txt copiado a almacenamiento privado, o null. */
    val cookiesFile: File get() = File(filesDir, "cookies.txt")

    val hasCookies: Boolean get() = cookiesFile.exists()

    /**
     * Copia un cookies.txt (Netscape) elegido con el file picker al almacenamiento
     * privado de la app. Devuelve true si quedó guardado.
     */
    fun importCookies(input: java.io.InputStream): Boolean = try {
        input.use { ins -> cookiesFile.outputStream().use { ins.copyTo(it) } }
        true
    } catch (_: Exception) {
        false
    }

    fun clearCookies() {
        cookiesFile.delete()
    }

    // ── idioma ────────────────────────────────────────────────────

    /** "" = seguir al sistema; "en"/"es" = forzar. Persistido por AppCompat. */
    fun currentLanguageTag(): String =
        AppCompatDelegate.getApplicationLocales().toLanguageTags().split(",").firstOrNull().orEmpty()

    fun setLanguage(tag: String) {
        val locales = if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    companion object {
        @Volatile
        private var instance: SettingsRepository? = null

        fun get(ctx: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(ctx.applicationContext).also { instance = it }
            }
    }
}
