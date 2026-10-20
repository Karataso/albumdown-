package com.albumdown.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.albumdown.R
import com.albumdown.dl.DownloadViewModel
import com.albumdown.dl.UiState
import com.albumdown.dl.UrlKind
import com.albumdown.dl.classifyUrl
import com.albumdown.dl.formatDuration

// Una sola pantalla: input → preview (con selector) → progreso → resultado.
// Los ajustes son una pantalla aparte (SettingsScreen), no un diálogo encima.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: DownloadViewModel, shared: String? = null) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsState()
    // rememberSaveable: si no, girar el celu borra el link pegado.
    var url by rememberSaveable { mutableStateOf(shared ?: "") }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val kind = classifyUrl(url.trim())

    // Viene de Compartir → auto-resuelve.
    LaunchedEffect(shared) {
        if (!shared.isNullOrBlank() && state is UiState.Idle) {
            url = shared
            vm.resolve(ctx, shared)
        }
    }

    // Si la app revivió con una descarga en marcha, la UI se vuelve a colgar
    // del trabajo real en vez de quedar en Idle con el Cancel muerto.
    LaunchedEffect(Unit) { vm.attachIfRunning(ctx) }

    if (showSettings) {
        SettingsScreen(vm = vm, onClose = { showSettings = false })
        return
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
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings_cd))
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
                label = { Text(stringResource(R.string.url_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                trailingIcon = {
                    if (url.isNotEmpty()) IconButton(onClick = { url = "" }) {
                        Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.clear_cd))
                    }
                },
            )
            if (url.isNotBlank()) Text(
                when (kind) {
                    UrlKind.YOUTUBE -> stringResource(R.string.kind_youtube)
                    UrlKind.SOUNDCLOUD -> stringResource(R.string.kind_soundcloud)
                    UrlKind.SPOTIFY -> stringResource(R.string.kind_spotify)
                    UrlKind.UNSUPPORTED -> stringResource(R.string.kind_unsupported)
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
                    Text(stringResource(R.string.search_album))
                }

                is UiState.Resolving -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.reading_album))
                }

                is UiState.Preview -> {
                    val a = s.album
                    val selected by vm.selected.collectAsState()
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
                                    val count = pluralStringResource(R.plurals.track_count, a.tracks.size, a.tracks.size)
                                    Text(
                                        count + if (totalDur > 0) " • ${formatDuration(totalDur)}" else "",
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
                                Text(
                                    stringResource(R.string.selected_of, nSel, a.tracks.size),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                TextButton(onClick = {
                                    val all = nSel == a.tracks.size
                                    vm.selectAll(!all)
                                }) {
                                    Text(
                                        stringResource(
                                            if (nSel == a.tracks.size) R.string.none else R.string.all,
                                        ),
                                    )
                                }
                            }
                            LazyColumn(Modifier.weight(1f)) {
                                itemsIndexed(a.tracks) { i, t ->
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                            .clickable { vm.toggle(i) }
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(checked = selected[i], onCheckedChange = { vm.toggle(i) })
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
                        ) { Text(stringResource(R.string.back)) }
                        Button(
                            onClick = { vm.download(ctx) },
                            modifier = Modifier.weight(2f),
                            enabled = nSel > 0,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.download_n_mp3, nSel))
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
                                        s.label.ifBlank { stringResource(R.string.preparing) },
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
                            val base = stringResource(R.string.progress_of, s.done, s.total)
                            val pct = if (s.total > 0) "  (${stringResource(R.string.percent, s.done * 100 / s.total)})" else ""
                            Text(base + pct, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    OutlinedButton(
                        onClick = { vm.cancel(ctx) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(stringResource(R.string.cancel)) }
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
                            Text(stringResource(R.string.done_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.done_summary, s.ok, s.skipped),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            // Siempre que las letras estaban activas: "♪ 0 con
                            // letras" es la única señal visible de que LRCLIB
                            // no respondió (antes: silencio total).
                            if (s.lyrics >= 0) Text(
                                stringResource(R.string.done_lyrics, s.lyrics),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (s.lyrics > 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (s.failed.isNotEmpty()) Text(
                                stringResource(R.string.failed_prefix, s.failed.take(200)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { openAlbumFolder(ctx) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.FolderOpen, contentDescription = stringResource(R.string.open_folder_cd),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    "Music/", // ruta literal: no se traduce
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    Button(
                        onClick = { vm.reset() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(stringResource(R.string.another_album)) }
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
                            Text(stringResource(R.string.error_title), style = MaterialTheme.typography.titleMedium)
                            Text(s.msg, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        }
                    }
                    OutlinedButton(
                        onClick = { vm.reset() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(stringResource(R.string.retry)) }
                }
            }
        }
    }
}

/**
 * Abre Music/ en el gestor de archivos — la raíz con las carpetas de artista,
 * igual que la salida del CLI. DocumentsUI identifica la carpeta por el
 * documentId "primary:<ruta>"; si ningún gestor lo agarra (ROMs chinas sin
 * DocumentsUI), fallback al listado de audio del MediaStore.
 */
private fun openAlbumFolder(ctx: Context) {
    val rel = Environment.DIRECTORY_MUSIC
    val doc = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
    val attempts = listOf(
        Intent(Intent.ACTION_VIEW).setDataAndType(doc, "vnd.android.document/directory"),
        Intent(Intent.ACTION_VIEW).setDataAndType(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio/*"),
    )
    for (i in attempts) {
        try {
            ctx.startActivity(i)
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
}
