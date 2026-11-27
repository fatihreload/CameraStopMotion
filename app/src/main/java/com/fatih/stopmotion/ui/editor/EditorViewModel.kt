package com.fatih.stopmotion.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fatih.stopmotion.AppContainer
import com.fatih.stopmotion.data.AudioRepository
import android.net.Uri
import com.fatih.stopmotion.data.AudioTrackEntity
import com.fatih.stopmotion.data.ChromaRepository
import com.fatih.stopmotion.data.FrameEntity
import com.fatih.stopmotion.data.LayerEntity
import com.fatih.stopmotion.data.LayerRepository
import com.fatih.stopmotion.data.ProjectEntity
import com.fatih.stopmotion.data.ProjectRepository
import com.fatih.stopmotion.fx.ChromaState
import com.fatih.stopmotion.fx.FxState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class EditorViewModel(
    private val repo: ProjectRepository,
    private val audioRepo: AudioRepository,
    private val chromaRepo: ChromaRepository,
    private val layerRepo: LayerRepository,
    private val projectId: Long
) : ViewModel() {

    val project: StateFlow<ProjectEntity?> = repo.observeProject(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val frames: StateFlow<List<FrameEntity>> = repo.observeFrames(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val audio: StateFlow<List<AudioTrackEntity>> = audioRepo.observe(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Layer overlay (layer 2, 3, dst.), urut dari yang terbawah. */
    val layers: StateFlow<List<LayerEntity>> = layerRepo.observe(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _layerBusy = MutableStateFlow(false)
    /** True selama file video/gambar layer sedang disalin ke proyek. */
    val layerBusy: StateFlow<Boolean> = _layerBusy.asStateFlow()

    val history = repo.observeHistory(projectId)

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val mutex = Mutex()

    fun toggle(id: Long) = _selected.update { if (id in it) it - id else it + id }
    fun clearSelection() { _selected.value = emptySet() }

    private fun op(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutex.withLock {
                runCatching { block() }.onFailure { _messages.tryEmit("Gagal: ${it.message}") }
            }
        }
    }

    fun deleteSelected(ids: Set<Long>) = op {
        repo.deleteFrames(projectId, ids)
        _selected.value = emptySet()
    }

    fun duplicateSelected(ids: Set<Long>) = op { repo.duplicateFrames(projectId, ids) }
    fun move(frameId: Long, toIndex: Int) = op { repo.moveFrameTo(projectId, frameId, toIndex) }
    fun setFps(fps: Int) = op { repo.updateFps(projectId, fps) }
    fun setEffects(fx: FxState) = op {
        repo.updateEffects(projectId, fx.filter, fx.intensity, fx.vignette, fx.fadeIn, fx.fadeOut, fx.fadeWhite)
    }

    fun setChroma(c: ChromaState) = op {
        repo.updateChroma(projectId, c.mode, c.tolerance, c.softness, c.bgMode)
    }

    fun setBackgroundImage(uri: Uri) = op { chromaRepo.setBackground(projectId, uri) }

    fun clearBackgroundImage() = op { chromaRepo.clearBackground(projectId) }

    /** Menambah layer dari video/gambar pilihan. Layer baru berada paling atas dan mulai di [startTick]. */
    fun addLayer(uri: Uri, startTick: Int, fps: Int, totalTicks: Int, onAdded: (Long) -> Unit) = op {
        _layerBusy.value = true
        try {
            onAdded(layerRepo.addFromUri(projectId, uri, startTick, fps, totalTicks).id)
        } finally {
            _layerBusy.value = false
        }
    }

    fun updateLayer(layer: LayerEntity) = op { layerRepo.update(layer) }

    fun deleteLayer(layer: LayerEntity) = op { layerRepo.delete(layer) }

    fun moveLayer(layerId: Long, up: Boolean) = op { layerRepo.moveZ(projectId, layerId, up) }

    fun duplicateLayer(layer: LayerEntity, onAdded: (Long) -> Unit) = op { onAdded(layerRepo.duplicate(layer).id) }

    /** Menyalin suara klip video layer ke Studio Audio, dimulai pada waktu yang sama dengan layer. */
    fun addLayerAudio(layer: LayerEntity, fps: Int) = op {
        val f = fps.coerceAtLeast(1)
        audioRepo.addFromClip(
            projectId = projectId,
            clip = File(layer.filePath),
            name = "Suara: ${layer.name}".take(40),
            startMs = layer.startTick * 1000L / f,
            trimStartMs = layer.trimStartMs,
            playMs = if (layer.loop) null else layer.lengthTicks * 1000L / f
        )
        _messages.tryEmit("Suara klip ditambahkan ke Audio")
    }

    fun undo() = op { repo.undo(projectId) }
    fun redo() = op { repo.redo(projectId) }
}

class EditorViewModelFactory(
    private val container: AppContainer,
    private val projectId: Long
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        EditorViewModel(
            container.repository, container.audioRepository, container.chromaRepository,
            container.layerRepository, projectId
        ) as T
}
