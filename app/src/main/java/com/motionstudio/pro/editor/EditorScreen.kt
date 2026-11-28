@file:OptIn(UnstableApi::class)

package com.motionstudio.pro.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.motionstudio.pro.data.Clip
import com.motionstudio.pro.data.ClipType
import com.motionstudio.pro.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun Clip.toMediaItem(): MediaItem = MediaItem.Builder().setUri(uri).apply {
    if (type == ClipType.VIDEO) setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(inMs).setEndPositionMs(outMs).build())
    else setImageDurationMs(durationMs)
}.build()

private fun seekGlobal(player: ExoPlayer, clips: List<Clip>, ms: Long) {
    var start = 0L
    for ((i, c) in clips.withIndex()) {
        if (ms < start + c.durationMs || i == clips.lastIndex) {
            if (i < player.mediaItemCount) player.seekTo(i, (ms - start).coerceIn(0, c.durationMs)); return
        }
        start += c.durationMs
    }
}

private fun globalPos(player: ExoPlayer, clips: List<Clip>): Long {
    val idx = player.currentMediaItemIndex.coerceIn(0, (clips.size - 1).coerceAtLeast(0))
    return clips.take(idx).sumOf { it.durationMs } + player.currentPosition.coerceAtLeast(0)
}

private val comingSoon = listOf("Audio" to "Tahap 2", "Teks" to "Tahap 2", "Caption" to "Tahap 2", "Voice Over" to "Tahap 2",
    "Canvas" to "Tahap 2", "Efek" to "Tahap 3", "Filter" to "Tahap 3", "Adjust" to "Tahap 3", "Transisi" to "Tahap 3",
    "Keyframe" to "Tahap 3", "Chroma Key" to "Tahap 4", "Mask" to "Tahap 4", "Tracking" to "Tahap 4", "AI" to "Tahap 4")

@Composable
fun EditorScreen(id: String, onBack: () -> Unit, onExport: (String) -> Unit, vm: EditorViewModel = viewModel()) {
    val ctx = LocalContext.current
    val s by vm.state.collectAsStateWithLifecycle()
    val clips = s.project?.clips ?: emptyList()
    val total = s.project?.durationMs ?: 0L
    val selected = clips.firstOrNull { it.id == s.selectedId }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(id) { vm.load(id) }
    LaunchedEffect(s.message) { s.message?.let { snack.showSnackbar(it); vm.consumeMessage() } }

    val player = remember { ExoPlayer.Builder(ctx).build() }
    var isPlaying by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val l = object : Player.Listener { override fun onIsPlayingChanged(p: Boolean) { isPlaying = p } }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) { player.pause(); vm.flush() } }
        lifecycle.addObserver(o); onDispose { lifecycle.removeObserver(o); vm.flush() }
    }

    // Sinkronkan playlist player dengan daftar klip setiap ada perubahan edit.
    LaunchedEffect(clips) {
        if (clips.isEmpty()) player.clearMediaItems()
        else { player.setMediaItems(clips.map { it.toMediaItem() }); player.prepare(); seekGlobal(player, clips, vm.state.value.playheadMs) }
    }
    LaunchedEffect(isPlaying, clips) {
        while (isPlaying) { vm.setPlayhead(globalPos(player, clips)); delay(33) }
    }

    var dpPerSec by remember { mutableFloatStateOf(60f) }
    var showTrim by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { vm.importUris(it) }
    val importMedia = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
    val seek = { ms: Long -> vm.setPlayhead(ms); seekGlobal(player, clips, ms) }

    Scaffold(containerColor = Bg, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") }
                Text(s.project?.name ?: "Memuat…", Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.titleMedium)
                Button({ vm.flush(); player.pause(); onExport(id) }, enabled = clips.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple)) { Text("Ekspor") }
                Spacer(Modifier.width(8.dp))
            }
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black), Alignment.Center) {
                AndroidView({ c -> PlayerView(c).apply { useController = false; this.player = player } }, Modifier.fillMaxSize())
                if (clips.isEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Belum ada media", color = Color.Gray); Spacer(Modifier.height(8.dp))
                        Button(importMedia) { Text("Impor video / foto") }
                    }
                }
                if (s.importing) CircularProgressIndicator(color = Cyan)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({
                    if (isPlaying) player.pause() else if (clips.isNotEmpty()) {
                        if (s.playheadMs >= total - 50) seek(0); player.play()
                    }
                }) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", tint = Cyan) }
                Text("${formatTime(s.playheadMs)} / ${formatTime(total)}", fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                IconButton({ dpPerSec = (dpPerSec / 1.4f).coerceIn(8f, 600f) }) { Icon(Icons.Default.ZoomOut, "Zoom out") }
                IconButton({ dpPerSec = (dpPerSec * 1.4f).coerceIn(8f, 600f) }) { Icon(Icons.Default.ZoomIn, "Zoom in") }
            }
            Timeline(clips, s.selectedId, s.playheadMs, total, dpPerSec,
                onZoom = { dpPerSec = (dpPerSec * it).coerceIn(8f, 600f) }, onSelect = vm::select, onSeek = seek,
                modifier = Modifier.fillMaxWidth().height(96.dp))

            if (showTrim && selected != null) {
                var range by remember(selected.id, selected.inMs, selected.outMs) {
                    mutableStateOf(selected.inMs.toFloat()..selected.outMs.toFloat())
                }
                Column(Modifier.fillMaxWidth().background(SurfHigh).padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text("Trim: ${formatTime(range.start.toLong())} – ${formatTime(range.endInclusive.toLong())} " +
                        "(${formatTime((range.endInclusive - range.start).toLong())})", fontSize = 12.sp)
                    RangeSlider(range, { range = it }, valueRange = 0f..selected.sourceDurationMs.toFloat(),
                        onValueChangeFinished = { vm.trim(selected.id, range.start.toLong(), range.endInclusive.toLong()) })
                }
            }

            LazyRow(Modifier.fillMaxWidth().background(Surf).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)) {
                item { ToolBtn(Icons.Default.AddPhotoAlternate, "Impor", true, importMedia) }
                item { ToolBtn(Icons.Default.ContentCut, "Split", clips.isNotEmpty(), vm::split) }
                item { ToolBtn(Icons.Default.Crop, "Trim", selected != null) { showTrim = !showTrim } }
                item { ToolBtn(Icons.Default.ContentCopy, "Duplikat", selected != null, vm::duplicate) }
                item { ToolBtn(Icons.Default.Delete, "Hapus", selected != null, vm::delete) }
                item { ToolBtn(Icons.Default.ChevronLeft, "Geser ←", selected != null) { vm.move(-1) } }
                item { ToolBtn(Icons.Default.ChevronRight, "Geser →", selected != null) { vm.move(1) } }
                item { ToolBtn(Icons.AutoMirrored.Filled.Undo, "Undo", s.canUndo, vm::undo) }
                item { ToolBtn(Icons.AutoMirrored.Filled.Redo, "Redo", s.canRedo, vm::redo) }
            }
            LazyRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(comingSoon) { (name, phase) ->
                    AssistChip({ scope.launch { snack.showSnackbar("$name belum tersedia ($phase)") } }, { Text(name) },
                        colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.onSurfaceVariant))
                }
            }
        }
    }
}

@Composable
private fun ToolBtn(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = if (enabled) MaterialTheme.colorScheme.onSurface else Color(0xFF555566)
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = if (enabled) Cyan else c); Text(label, fontSize = 11.sp, color = c)
    }
}
