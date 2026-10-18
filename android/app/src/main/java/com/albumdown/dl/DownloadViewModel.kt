package com.albumdown.dl

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface UiState {
    data object Idle : UiState
    data object Resolving : UiState
    data class Preview(val album: Album, val url: String) : UiState
    data class Downloading(val done: Int, val total: Int, val label: String, val albumName: String) : UiState
    data class Done(val ok: Int, val skipped: Int, val failed: String) : UiState
    data class Error(val msg: String) : UiState
}

class DownloadViewModel : ViewModel() {
    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state
    private var lastWorkId: UUID? = null

    fun wifiOnly(ctx: Context): Boolean =
        ctx.getSharedPreferences("albumdown", Context.MODE_PRIVATE).getBoolean("wifi_only", true)

    fun setWifiOnly(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences("albumdown", Context.MODE_PRIVATE).edit().putBoolean("wifi_only", v).apply()
    }

    fun resolve(ctx: Context, url: String) {
        val app = ctx.applicationContext
        val t = url.trim()
        if (classifyUrl(t) == UrlKind.UNSUPPORTED) {
            _state.value = UiState.Error("Link no soportado (YouTube, SoundCloud o Spotify)")
            return
        }
        _state.value = UiState.Resolving
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val album = resolveAlbum(app, t)
                _state.value = when {
                    album == null || album.tracks.isEmpty() ->
                        UiState.Error("No se pudo leer el álbum")
                    else -> UiState.Preview(album, t)
                }
            } catch (e: Exception) {
                _state.value = UiState.Error("Error: ${(e.message ?: "desconocido").take(120)}")
            }
        }
    }

    /**
     * indices = posiciones (0-based) de las canciones elegidas en el preview.
     */
    fun download(ctx: Context, album: Album, url: String, indices: List<Int>) {
        val app = ctx.applicationContext
        // ponytail: solo-WiFi por defecto (un álbum con datos duele). Se quita en ⚙.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly(app)) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf("url" to url, "indices" to indices.joinToString(",")))
            .build()
        lastWorkId = req.id
        WorkManager.getInstance(app).enqueue(req)
        observe(app, req.id, album.name, indices.size)
    }

    fun cancel(ctx: Context) {
        lastWorkId?.let { WorkManager.getInstance(ctx.applicationContext).cancelWorkById(it) }
    }

    private fun observe(app: Context, id: UUID, albumName: String, total: Int) {
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfoByIdFlow(id).collect { info ->
                if (info == null) return@collect
                when (info.state) {
                    WorkInfo.State.RUNNING -> {
                        val p = info.progress
                        _state.value = UiState.Downloading(
                            p.getInt("done", 0), p.getInt("total", total),
                            p.getString("label").orEmpty(), albumName
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val o = info.outputData
                        _state.value = UiState.Done(
                            o.getInt("ok", 0), o.getInt("skipped", 0), o.getString("failed").orEmpty()
                        )
                    }
                    WorkInfo.State.FAILED -> {
                        _state.value = UiState.Error(info.outputData.getString("error") ?: "Falló la descarga")
                    }
                    WorkInfo.State.CANCELLED -> _state.value = UiState.Idle
                    else -> Unit
                }
            }
        }
    }

    fun reset() {
        _state.value = UiState.Idle
    }
}
