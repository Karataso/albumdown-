package com.albumdown.dl

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.albumdown.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // Selección del preview: vive acá y no en el composable — la UI solo dibuja
    // y manda toggles, y download() lee su propia selección en vez de recibirla
    // como parámetro (imposible que se desincronice del preview en pantalla).
    // StateFlow (no estado de Compose): el paquete dl sigue sin depender de UI.
    private val _selected = MutableStateFlow<List<Boolean>>(emptyList())
    val selected: StateFlow<List<Boolean>> = _selected

    private var lastWorkId: UUID? = null
    private var observeJob: Job? = null // un solo observador: cancela el anterior

    // Los ajustes viven en SettingsRepository (StateFlow): la UI se suscribe y
    // el worker recibe un snapshot al encolar.
    fun settings(ctx: Context): SettingsRepository = SettingsRepository.get(ctx)

    fun resolve(ctx: Context, url: String) {
        val app = ctx.applicationContext
        val t = url.trim()
        if (classifyUrl(t) == UrlKind.UNSUPPORTED) {
            _state.value = UiState.Error(ctx.getString(R.string.err_unsupported))
            return
        }
        _state.value = UiState.Resolving
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val album = resolveAlbum(app, t)
                if (album == null || album.tracks.isEmpty()) {
                    _state.value = UiState.Error(ctx.getString(R.string.err_no_album))
                } else {
                    _selected.value = List(album.tracks.size) { true } // todo marcado
                    _state.value = UiState.Preview(album, t)
                    // --no-confirm: baja todo de una sin pasar por la selección.
                    if (settings(app).skipPreview.value) download(ctx)
                }
            } catch (e: DlError) {
                _state.value = UiState.Error(ctx.getString(failureUserMessageRes(e.kind)))
            } catch (e: Exception) {
                val detail = (e.message ?: "?").take(120)
                _state.value = UiState.Error(ctx.getString(R.string.err_unexpected, detail))
            }
        }
    }

    fun toggle(index: Int) {
        val sel = _selected.value
        if (index !in sel.indices) return
        _selected.value = sel.mapIndexed { i, v -> if (i == index) !v else v }
    }

    fun selectAll(value: Boolean) {
        if (_selected.value.isEmpty()) return
        _selected.value = List(_selected.value.size) { value }
    }

    /** Descarga lo seleccionado en el preview en curso. */
    fun download(ctx: Context) {
        val preview = _state.value as? UiState.Preview ?: return
        val indices = _selected.value.indices.filter { _selected.value[it] }
        if (indices.isEmpty()) return
        val app = ctx.applicationContext
        val s = settings(app)

        // ponytail: solo-WiFi por defecto (un álbum con datos duele). Se quita en ⚙.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (s.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        // Snapshot de ajustes al encolar: si el usuario cambia algo mientras
        // baja, la corrida en marcha no cambia de opinión a mitad de camino.
        val snapshot = SettingsSnapshot(
            quality = s.quality.value,
            safe = s.safe.value,
            lyrics = s.lyrics.value,
            art = s.art.value,
            cookiesPath = if (s.hasCookies) s.cookiesFile.absolutePath else "",
        )
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setInputData(WorkContract.input(preview.url, indices, snapshot))
            .build()
        lastWorkId = req.id
        // Cola única: dos álbumes a la vez nunca (el worker comparte proceso y
        // notificación). APPEND_OR_REPLACE = si el anterior falló se limpia la
        // cola; si está corriendo, el nuevo espera su turno.
        WorkManager.getInstance(app)
            .enqueueUniqueWork(WorkContract.UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, req)

        // Estado visible desde el instante: antes la pantalla se quedaba en el
        // preview hasta que el worker arrancara (o para siempre, si estaba en cola).
        _state.value = UiState.Downloading(0, indices.size, "", preview.album.name)
        observe(app, req.id)
    }

    fun cancel(ctx: Context) {
        lastWorkId?.let { WorkManager.getInstance(ctx.applicationContext).cancelWorkById(it) }
    }

    /**
     * Re-adjunta la UI a un trabajo que sigue vivo: si la app murió (y revivió
     * o el usuario la reabrió), WorkManager sigue descargando en background
     * pero la pantalla decía Idle con el botón Cancel muerto. Sin esto, una
     * descarga larga se "pierde" aunque el archivo termine llegando.
     */
    fun attachIfRunning(ctx: Context) {
        if (_state.value != UiState.Idle || lastWorkId != null) return
        val app = ctx.applicationContext
        viewModelScope.launch {
            val active = withContext(Dispatchers.IO) {
                try {
                    WorkManager.getInstance(app)
                        .getWorkInfosForUniqueWork(WorkContract.UNIQUE_WORK).get()
                        .firstOrNull { !it.state.isFinished }
                } catch (e: Exception) {
                    null
                }
            } ?: return@launch
            lastWorkId = active.id
            val p = active.progress
            _state.value = UiState.Downloading(
                0, p.getInt(WorkContract.PR_TOTAL, 0), ctx.getString(R.string.queued),
                p.getString(WorkContract.PR_ALBUM).orEmpty(),
            )
            observe(app, active.id)
        }
    }

    /**
     * Un solo observador por trabajo. El total y el nombre del álbum salen del
     * progreso que publica el worker (WorkContract) — antes se le pasaban a
     * mano desde la UI y podían no coincidir con lo que el worker hacía.
     */
    private fun observe(app: Context, id: UUID) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfoByIdFlow(id).collect { info ->
                if (info == null) return@collect
                val p = info.progress
                val total = p.getInt(WorkContract.PR_TOTAL, 0)
                val albumName = p.getString(WorkContract.PR_ALBUM).orEmpty()
                when (info.state) {
                    // Cola (unique work): todavía no arrancó — se muestra como
                    // "en cola" en vez de dejar la pantalla clavada en el preview.
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                        _state.value = UiState.Downloading(0, total, app.getString(R.string.queued), albumName)

                    WorkInfo.State.RUNNING ->
                        _state.value = UiState.Downloading(
                            p.getInt(WorkContract.PR_DONE, 0), total,
                            p.getString(WorkContract.PR_LABEL).orEmpty(), albumName,
                        )

                    WorkInfo.State.SUCCEEDED -> {
                        val o = info.outputData
                        _state.value = UiState.Done(
                            o.getInt(WorkContract.OUT_OK, 0),
                            o.getInt(WorkContract.OUT_SKIPPED, 0),
                            o.getString(WorkContract.OUT_FAILED).orEmpty(),
                        )
                    }

                    WorkInfo.State.FAILED ->
                        _state.value = UiState.Error(
                            info.outputData.getString(WorkContract.OUT_ERROR)
                                ?: app.getString(R.string.err_generic),
                        )

                    WorkInfo.State.CANCELLED -> _state.value = UiState.Idle
                    else -> Unit
                }
            }
        }
    }

    fun reset() {
        observeJob?.cancel()
        observeJob = null
        _selected.value = emptyList()
        _state.value = UiState.Idle
    }
}
