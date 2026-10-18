package com.albumdown.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.albumdown.dl.DownloadViewModel
import com.albumdown.dl.UiState
import com.albumdown.dl.UrlKind
import com.albumdown.dl.classifyUrl
import com.albumdown.dl.formatDuration
import com.albumdown.dl.updateYtDlp
import com.albumdown.dl.ytDlpVersion
import kotlinx.coroutines.launch

// Una sola pantalla: input → preview (con selector) → progreso → resultado.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: DownloadViewModel, shared: String? = null) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsState()
    var url by remember { mutableStateOf(shared ?: "") }
    var showSettings by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val kind = classifyUrl(url.trim())

    // Viene de Compartir → auto-resuelve.
    LaunchedEffect(shared) {
        if (!shared.isNullOrBlank() && state is UiState.Idle) {
            url = shared
            vm.resolve(ctx, shared)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.LibraryMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("AlbumDown", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Ajustes")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("Link de YouTube, SoundCloud o Spotify") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                trailingIcon = {
                    if (url.isNotEmpty()) IconButton(onClick = { url = "" }) {
                        Icon(Icons.Rounded.Clear, contentDescription = "Borrar")
                    }
                },
            )
            if (url.isNotBlank()) Text(
                when (kind) {
                    UrlKind.YOUTUBE -> "▶ YouTube detectado"
                    UrlKind.SOUNDCLOUD -> "☁ SoundCloud / audio detectado"
                    UrlKind.SPOTIFY -> "🟢 Spotify detectado (se busca el audio en YouTube)"
                    UrlKind.UNSUPPORTED -> "✖ Link no soportado"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (kind == UrlKind.UNSUPPORTED)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val s = state) {
                is UiState.Idle -> Button(
                    onClick = { focus.clearFocus(); vm.resolve(ctx, url) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = url.isNotBlank() && kind != UrlKind.UNSUPPORTED,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Buscar álbum")
                }

                is UiState.Resolving -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Leyendo álbum…")
                }

                is UiState.Preview -> {
                    val a = s.album
                    val selected = remember(s) { mutableStateListOf(*Array(a.tracks.size) { true }) }
                    val nSel = selected.count { it }
                    Card(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(20.dp)) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (a.coverUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = a.coverUrl, contentDescription = null,
                                        modifier = Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Box(
                                        Modifier.size(84.dp).clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Rounded.MusicNote, contentDescription = null,
                                            modifier = Modifier.size(36.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        a.artist, style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        a.name, style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                    val totalDur = a.tracks.sumOf { it.durationSec }
                                    Text(
                                        "${a.tracks.size} canciones" +
                                            if (totalDur > 0) " • ${formatDuration(totalDur)}" else "",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Seleccionadas: $nSel de ${a.tracks.size}", style = MaterialTheme.typography.labelLarge)
                                TextButton(onClick = {
                                    val all = nSel == a.tracks.size
                                    for (k in selected.indices) selected[k] = !all
                                }) { Text(if (nSel == a.tracks.size) "Ninguna" else "Todas") }
                            }
                            LazyColumn(Modifier.weight(1f)) {
                                itemsIndexed(a.tracks) { i, t ->
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                            .clickable { selected[i] = !selected[i] }
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(checked = selected[i], onCheckedChange = { selected[i] = it })
                                        val thumb = t.coverUrl.ifEmpty { a.coverUrl }
                                        if (thumb.isNotBlank()) {
                                            AsyncImage(
                                                model = thumb, contentDescription = null,
                                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                                                contentScale = ContentScale.Crop,
                                            )
                                        } else {
                                            Box(
                                                Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    Icons.Rounded.MusicNote, contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "%02d".format(i + 1),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.width(26.dp),
                                        )
                                        Text(
                                            t.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            formatDuration(t.durationSec),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { vm.reset() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                        ) { Text("Atrás") }
                        Button(
                            onClick = { vm.download(ctx, a, s.url, selected.indices.filter { selected[it] }) },
                            modifier = Modifier.weight(2f),
                            enabled = nSel > 0,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Descargar $nSel MP3")
                        }
                    }
                }

                is UiState.Downloading -> {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        s.albumName, style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        s.label.ifBlank { "Preparando…" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            LinearProgressIndicator(
                                progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f },
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                            )
                            Text(
                                "${s.done} de ${s.total}" + if (s.total > 0) "  (${s.done * 100 / s.total}%)" else "",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { vm.cancel(ctx) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text("Cancelar") }
                }

                is UiState.Done -> {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Rounded.CheckCircle, contentDescription = null,
                                modifier = Modifier.size(46.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text("¡Listo!", style = MaterialTheme.typography.titleMedium)
                            Text("${s.ok} descargadas • ${s.skipped} ya estaban", style = MaterialTheme.typography.bodyMedium)
                            if (s.failed.isNotEmpty()) Text(
                                "Fallaron: ${s.failed.take(200)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.FolderOpen, contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "Music/AlbumDown/",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Button(
                        onClick = { vm.reset() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text("Otro álbum") }
                }

                is UiState.Error -> {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Rounded.ErrorOutline, contentDescription = null,
                                modifier = Modifier.size(46.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Text("Error", style = MaterialTheme.typography.titleMedium)
                            Text(s.msg, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        }
                    }
                    OutlinedButton(
                        onClick = { vm.reset() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text("Reintentar") }
                }
            }
        }
    }
    if (showSettings) SettingsDialog(
        vm = vm,
        onClose = { showSettings = false },
    )
}

@Composable
private fun SettingsDialog(vm: DownloadViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var wifi by remember { mutableStateOf(vm.wifiOnly(ctx)) }
    var ver by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { ver = ytDlpVersion(ctx) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Ajustes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Descargar solo con WiFi")
                    Switch(checked = wifi, onCheckedChange = { wifi = it; vm.setWifiOnly(ctx, it) })
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("yt-dlp: ${ver ?: "…"}", style = MaterialTheme.typography.bodySmall)
                    TextButton(
                        enabled = !updating,
                        onClick = {
                            updating = true
                            result = ""
                            scope.launch {
                                result = updateYtDlp(ctx)
                                ver = ytDlpVersion(ctx)
                                updating = false
                            }
                        },
                    ) {
                        if (updating) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Actualizar")
                        }
                    }
                }
                if (result.isNotEmpty()) Text(
                    result,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Listo") } },
    )
}
