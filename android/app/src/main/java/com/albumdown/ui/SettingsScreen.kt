package com.albumdown.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cookie
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.albumdown.R
import com.albumdown.dl.DownloadViewModel
import com.albumdown.dl.YtDlpUpdate
import com.albumdown.dl.updateYtDlp
import com.albumdown.dl.ytDlpVersion
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Ajustes a pantalla completa, agrupados como los flags del CLI:
// General (idioma, saltar preview) · Descargas (WiFi, calidad, letras, arte) ·
// Trato humano (--safe) · Avanzado (yt-dlp, cookies). El diálogo de dos filas
// que había antes no daba cabida a nada de esto.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: DownloadViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val s = vm.settings(ctx)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionCard(R.string.section_general) {
                // Estado local: getApplicationLocales() no es state de Compose, así
                // que sin esto el chip no se marca al tocarlo hasta que algo más
                // recomponga la pantalla.
                var lang by remember { mutableStateOf(s.currentLanguageTag()) }
                LanguageRow(lang) {
                    s.setLanguage(it)
                    lang = it
                }
                SettingSwitch(
                    R.string.skip_preview_title, R.string.skip_preview_desc,
                    s.skipPreview.collectAsState().value, s::setSkipPreview,
                )
            } }

            item { SectionCard(R.string.section_downloads) {
                SettingSwitch(
                    R.string.wifi_only_title, R.string.wifi_only_desc,
                    s.wifiOnly.collectAsState().value, s::setWifiOnly,
                )
                QualitySlider(s.quality.collectAsState().value, s::setQuality)
                SettingSwitch(
                    R.string.lyrics_title, R.string.lyrics_desc,
                    s.lyrics.collectAsState().value, s::setLyrics,
                )
                SettingSwitch(
                    R.string.art_title, R.string.art_desc,
                    s.art.collectAsState().value, s::setArt,
                )
            } }

            item { SectionCard(R.string.section_humanization) {
                SettingSwitch(
                    R.string.safe_mode_title, R.string.safe_mode_desc,
                    s.safe.collectAsState().value, s::setSafe,
                )
            } }

            item { AdvancedSection(vm) }
            item { Spacer(Modifier.size(8.dp)) }
        }
    }
}

// ── piezas reutilizables ──────────────────────────────────────────

@Composable
private fun SectionCard(@StringRes titleRes: Int, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

/** Fila con switch + descripción debajo (la descripción evita adivinar qué hace). */
@Composable
private fun SettingSwitch(
    @StringRes titleRes: Int,
    @StringRes descRes: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(descRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Calidad 0-10 como el --quality del CLI, donde 0 = mejor. El CLI guarda 0=mejor
 * (herencia de yt-dlp), pero a la barra le queda al revés: barra llena = mejor
 * calidad, así que se muestra invertida (10 - calidad) y se traduce al guardar.
 * La etiqueta muestra los kbps reales de LAME en VBR para que el número signifique algo.
 */
@Composable
private fun QualitySlider(value: Int, onChange: (Int) -> Unit) {
    // LAME -q:a en VBR: bitrates aproximados por nivel (0 = mejor → 9 = peor).
    val kbps = intArrayOf(245, 225, 190, 175, 165, 130, 115, 100, 85, 65)
    val quality = value.coerceIn(0, 9)
    var shown by remember(value) { mutableFloatStateOf((10 - quality).toFloat()) }
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.quality_title), style = MaterialTheme.typography.bodyLarge)
            // Siempre los kbps reales: "Best"/"Smallest" ya van en los extremos debajo.
            Text(
                stringResource(R.string.quality_bitrate, kbps[quality]),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            stringResource(R.string.quality_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = shown,
            onValueChange = { shown = it },
            // Guarda invertido: derecha (barra llena) = mejor calidad = 0 en el CLI.
            onValueChangeFinished = { onChange((10 - shown.roundToInt()).coerceIn(0, 10)) },
            valueRange = 0f..10f,
            steps = 8, // 10 posiciones enteras
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.quality_worst),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.quality_best),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Selector de idioma: seguir al sistema, o forzar en/es. */
@Composable
private fun LanguageRow(current: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.lang_title), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LangChip(stringResource(R.string.lang_system), current.isBlank(), { onSelect("") })
            LangChip(stringResource(R.string.lang_english), current.startsWith("en"), { onSelect("en") })
            LangChip(stringResource(R.string.lang_spanish), current.startsWith("es"), { onSelect("es") })
        }
    }
}

@Composable
private fun LangChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

/** Avanzado: versión de yt-dlp + actualización, y cookies (--cookies FILE). */
@Composable
private fun AdvancedSection(vm: DownloadViewModel) {
    val ctx = LocalContext.current
    val s = vm.settings(ctx)
    val scope = rememberCoroutineScope()
    var ver by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<YtDlpUpdate?>(null) }
    var hasCookies by remember { mutableStateOf(s.hasCookies) }
    var cookieMsg by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { ver = ytDlpVersion(ctx) }

    // Elegir un cookies.txt (Netscape) con el picker del sistema; la app lo copia
    // a su almacenamiento privado y lo pasa a yt-dlp como --cookies.
    val pickCookies = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { s.importCookies(it) } == true
            }.getOrDefault(false)
            hasCookies = s.hasCookies
            cookieMsg = if (ok) ctx.getString(R.string.cookies_state_active) else ""
        }
    }

    SectionCard(R.string.section_advanced) {
        // Fila de yt-dlp: estado claro (versión + resultado coloreado, no texto suelto).
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(stringResource(R.string.ytdlp_label), style = MaterialTheme.typography.bodyLarge)
                Text(
                    ver?.let { stringResource(R.string.ytdlp_version, it) } ?: "…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val status: Pair<String, Color>? = when (val r = update) {
                    null -> null
                    is YtDlpUpdate.Updated ->
                        stringResource(R.string.ytdlp_updated, r.version) to MaterialTheme.colorScheme.primary
                    is YtDlpUpdate.UpToDate ->
                        stringResource(R.string.ytdlp_uptodate, r.version) to MaterialTheme.colorScheme.onSurfaceVariant
                    is YtDlpUpdate.Failed ->
                        stringResource(R.string.ytdlp_update_error, r.detail) to MaterialTheme.colorScheme.error
                }
                if (status != null) Text(
                    status.first,
                    style = MaterialTheme.typography.bodySmall,
                    color = status.second,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            TextButton(
                enabled = !updating,
                onClick = {
                    updating = true
                    update = null
                    scope.launch {
                        update = updateYtDlp(ctx)
                        ver = ytDlpVersion(ctx)
                        updating = false
                    }
                },
            ) {
                if (updating) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.updating))
                } else {
                    Text(stringResource(R.string.update))
                }
            }
        }

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Cookie, contentDescription = null,
                    modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (hasCookies) R.string.cookies_state_active else R.string.cookies_state_none),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                stringResource(R.string.cookies_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(
                    onClick = { pickCookies.launch(arrayOf("text/*", "*/*")) },
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.cookies_import)) }
                if (hasCookies) TextButton(onClick = {
                    s.clearCookies()
                    hasCookies = false
                    cookieMsg = ""
                }) { Text(stringResource(R.string.cookies_clear)) }
            }
            if (cookieMsg.isNotEmpty()) Text(
                cookieMsg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
