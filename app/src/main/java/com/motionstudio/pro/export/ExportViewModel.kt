@file:OptIn(UnstableApi::class)

package com.motionstudio.pro.export

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.motionstudio.pro.MotionApp
import com.motionstudio.pro.data.ClipType
import com.motionstudio.pro.data.Project
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val progress: Float) : ExportState
    data class Done(val uri: Uri) : ExportState
    data class Failed(val message: String) : ExportState
}

enum class Quality(val label: String, val factor: Float) { LOW("Hemat", 0.6f), MEDIUM("Standar", 1f), HIGH("Tinggi", 1.6f) }

class ExportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as MotionApp).repo
    var project by mutableStateOf<Project?>(null); private set
    var height by mutableIntStateOf(1080)
    var quality by mutableStateOf(Quality.MEDIUM)
    var h265 by mutableStateOf(false)
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state = _state.asStateFlow()
    private var transformer: Transformer? = null
    private var loaded = false

    fun load(id: String) {
        if (loaded) return; loaded = true
        viewModelScope.launch { project = repo.get(id) }
    }

    fun bitrate(): Int {
        val base = when { height >= 1080 -> 6_000_000; height >= 720 -> 3_000_000; else -> 1_500_000 }
        return (base * quality.factor * if (h265) 0.7f else 1f).toInt()
    }

    fun estimateBytes(): Long = ((bitrate() + 128_000L) * ((project?.durationMs ?: 0) / 1000.0) / 8).toLong()

    private fun outSize(p: Project): Pair<Int, Int> {
        val f = p.clips.first()
        val long = (height * 16 / 9) / 2 * 2
        return if (f.height > f.width) height to long else long to height
    }

    fun start() {
        val p = project ?: return
        if (p.clips.isEmpty()) { _state.value = ExportState.Failed("Proyek kosong"); return }
        val ctx = getApplication<Application>()
        val (w, h) = outSize(p)
        val effects = Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)))
        val items = p.clips.map { c ->
            val mi = MediaItem.Builder().setUri(c.uri).apply {
                if (c.type == ClipType.VIDEO) setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.inMs).setEndPositionMs(c.outMs).build())
            }.build()
            EditedMediaItem.Builder(mi).apply {
                if (c.type == ClipType.IMAGE) { setDurationUs(c.durationMs * 1000); setFrameRate(30) }
                setEffects(effects)
            }.build()
        }
        val composition = Composition.Builder(EditedMediaItemSequence(items)).experimentalSetForceAudioTrack(true).build()
        val out = File(ctx.cacheDir, "export_${System.currentTimeMillis()}.mp4")

        val t = Transformer.Builder(ctx)
            .setVideoMimeType(if (h265) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            .setEncoderFactory(DefaultEncoderFactory.Builder(ctx)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate()).build()).build())
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    viewModelScope.launch {
                        val uri = withContext(Dispatchers.IO) { saveToGallery(out) }
                        _state.value = if (uri != null) ExportState.Done(uri) else ExportState.Failed("Gagal menyimpan ke galeri")
                        out.delete()
                    }
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    out.delete()
                    _state.value = ExportState.Failed("Ekspor gagal: ${exportException.message ?: exportException.errorCodeName}")
                }
            }).build()
        transformer = t
        _state.value = ExportState.Running(0f)
        try { t.start(composition, out.absolutePath) } catch (e: Exception) {
            _state.value = ExportState.Failed("Tidak bisa memulai ekspor: ${e.message}"); return
        }
        viewModelScope.launch {
            val holder = ProgressHolder()
            while (_state.value is ExportState.Running) {
                if (t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    _state.value = ExportState.Running(holder.progress / 100f)
                }
                delay(300)
            }
        }
    }

    fun cancel() { transformer?.cancel(); _state.value = ExportState.Idle }
    fun reset() { _state.value = ExportState.Idle }

    private fun saveToGallery(file: File): Uri? {
        val cr = getApplication<Application>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "MotionStudio_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MotionStudioPro")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            cr.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } } ?: return null
            cr.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (_: Exception) { cr.delete(uri, null, null); null }
    }

    override fun onCleared() { transformer?.cancel() }
}
