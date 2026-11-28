package com.motionstudio.pro.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motionstudio.pro.MotionApp
import com.motionstudio.pro.data.Clip
import com.motionstudio.pro.data.MediaImporter
import com.motionstudio.pro.data.Project
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorState(
    val project: Project? = null,
    val selectedId: String? = null,
    val playheadMs: Long = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val importing: Boolean = false,
    val message: String? = null,
)

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as MotionApp).repo
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val undoStack = ArrayDeque<List<Clip>>()
    private val redoStack = ArrayDeque<List<Clip>>()
    private var saveJob: Job? = null
    private var loadedId: String? = null
    private val MIN = 100L

    private val clips get() = _state.value.project?.clips ?: emptyList()

    fun load(id: String) {
        if (loadedId == id) return
        loadedId = id
        viewModelScope.launch {
            val p = repo.get(id)
            if (p == null) _state.update { it.copy(message = "Proyek tidak ditemukan atau rusak") }
            else _state.update { EditorState(project = p) }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
    private fun msg(m: String) = _state.update { it.copy(message = m) }

    private fun commit(new: List<Clip>, select: String? = _state.value.selectedId) {
        if (_state.value.project == null) return
        undoStack.addLast(clips); if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        apply(new, select)
    }

    private fun apply(new: List<Clip>, select: String?) {
        _state.update {
            val p = it.project ?: return@update it
            val np = p.copy(clips = new)
            it.copy(project = np, selectedId = select?.takeIf { s -> new.any { c -> c.id == s } },
                playheadMs = it.playheadMs.coerceAtMost(np.durationMs),
                canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())
        }
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(500); _state.value.project?.let { repo.save(it) } }
    }

    /** Simpan segera (dipanggil saat app ke background / layar ditutup). */
    fun flush() { saveJob?.cancel(); _state.value.project?.let(repo::saveAsync) }

    fun undo() { val prev = undoStack.removeLastOrNull() ?: return; redoStack.addLast(clips); apply(prev, _state.value.selectedId) }
    fun redo() { val next = redoStack.removeLastOrNull() ?: return; undoStack.addLast(clips); apply(next, _state.value.selectedId) }

    fun select(id: String?) = _state.update { it.copy(selectedId = id) }
    fun setPlayhead(ms: Long) = _state.update { it.copy(playheadMs = ms.coerceIn(0, it.project?.durationMs ?: 0)) }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(importing = true) }
            val ok = withContext(Dispatchers.IO) { uris.mapNotNull { MediaImporter.import(getApplication(), it) } }
            if (ok.isNotEmpty()) commit(clips + ok, ok.last().id)
            val failed = uris.size - ok.size
            _state.update { it.copy(importing = false, message = if (failed > 0) "$failed file tidak didukung atau gagal dibaca" else null) }
        }
    }

    fun split() {
        val ph = _state.value.playheadMs
        var start = 0L
        for ((i, c) in clips.withIndex()) {
            val end = start + c.durationMs
            if (ph > start && ph < end) {
                val cut = c.inMs + (ph - start)
                if (cut - c.inMs < MIN || c.outMs - cut < MIN) { msg("Potongan terlalu pendek"); return }
                val a = c.copy(outMs = cut); val b = c.copy(id = UUID.randomUUID().toString(), inMs = cut)
                commit(clips.take(i) + a + b + clips.drop(i + 1), a.id); return
            }
            start = end
        }
        msg("Geser playhead ke dalam klip untuk memotong")
    }

    fun trim(id: String, inMs: Long, outMs: Long) {
        val c = clips.firstOrNull { it.id == id } ?: return
        val i = inMs.coerceIn(0, c.sourceDurationMs); val o = outMs.coerceIn(0, c.sourceDurationMs)
        if (o - i < MIN || (i == c.inMs && o == c.outMs)) return
        commit(clips.map { if (it.id == id) it.copy(inMs = i, outMs = o) else it })
    }

    fun duplicate() {
        val id = _state.value.selectedId ?: return
        val i = clips.indexOfFirst { it.id == id }; if (i < 0) return
        val copy = clips[i].copy(id = UUID.randomUUID().toString())
        commit(clips.take(i + 1) + copy + clips.drop(i + 1), copy.id)
    }

    fun delete() {
        val id = _state.value.selectedId ?: return
        commit(clips.filter { it.id != id }, null)
    }

    fun move(dir: Int) {
        val id = _state.value.selectedId ?: return
        val i = clips.indexOfFirst { it.id == id }; val j = i + dir
        if (i < 0 || j !in clips.indices) return
        val m = clips.toMutableList(); m[i] = clips[j]; m[j] = clips[i]; commit(m)
    }
}
