package com.fatih.stopmotion.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fatih.stopmotion.data.LayerEntity
import com.fatih.stopmotion.fx.ChromaMode
import com.fatih.stopmotion.layers.isVideo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import com.fatih.stopmotion.layers.Ease
import com.fatih.stopmotion.layers.Keyframe
import com.fatih.stopmotion.layers.Keyframes
import com.fatih.stopmotion.layers.isAnimated
import com.fatih.stopmotion.layers.basePose
import com.fatih.stopmotion.layers.keys
import com.fatih.stopmotion.ui.theme.FatihColors
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

object LayerTab {
    const val TIME = 0
    const val LOOK = 1
    const val CHROMA = 2
    const val KEY = 3
}

private fun seconds(ticks: Int, fps: Int): String =
    String.format(Locale.US, "%.1f dtk", ticks / fps.coerceAtLeast(1).toFloat())

/**
 * Panel layer overlay di Studio Animasi. Layer 1 adalah foto frame; video/gambar yang ditambahkan menjadi
 * layer 2, 3, dst. (makin besar makin di atas). Posisi dan ukuran diatur dengan jari langsung di pratinjau,
 * sisanya (waktu, tampilan, chroma key) lewat slider di panel ini.
 */
@Composable
fun LayersPanel(
    layers: List<LayerEntity>,
    selectedId: Long,
    tab: Int,
    onTab: (Int) -> Unit,
    totalTicks: Int,
    currentTick: Int,
    fps: Int,
    onSelect: (Long) -> Unit,
    onAddVideo: () -> Unit,
    onAddImage: () -> Unit,
    onChange: (LayerEntity) -> Unit,
    onCommit: (LayerEntity) -> Unit,
    /** Jari dilepas dari slider: simpan nilai terbaru layer ini (bukan salinan lama dari komposisi sebelumnya). */
    onEditEnd: (Long) -> Unit,
    /** Loncat ke tick film tertentu (dipakai strip keyframe dan tombol key sebelumnya/berikutnya). */
    onSeek: (Int) -> Unit,
    onMove: (Long, Boolean) -> Unit,
    onDuplicate: (LayerEntity) -> Unit,
    onDelete: (LayerEntity) -> Unit,
    onAddAudio: (LayerEntity) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sel = layers.firstOrNull { it.id == selectedId }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxWidth()
            .background(FatihColors.Surface)
            .heightIn(max = 250.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Layer", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            AddButton(Icons.Filled.Videocam, "Video", onAddVideo)
            Spacer(Modifier.width(6.dp))
            AddButton(Icons.Filled.Image, "Gambar", onAddImage)
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Tutup panel layer")
            }
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LayerChip("1 Foto", null, false, dim = true) {}
            layers.forEachIndexed { i, l ->
                LayerChip("${i + 2} ${l.name.take(12)}", l, l.id == selectedId, dim = !l.visible) { onSelect(l.id) }
            }
        }

        if (layers.isEmpty()) {
            Hint(
                "Belum ada layer. Ketuk Video untuk memasukkan klip berlatar green screen (mis. efek peluru), " +
                    "atau Gambar untuk stiker/efek PNG. Bisa banyak layer."
            )
        } else if (sel == null) {
            Hint("Pilih layer di atas untuk mengatur waktu, tampilan, dan chroma key-nya.")
        } else {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LayerChip("Waktu", null, tab == LayerTab.TIME) { onTab(LayerTab.TIME) }
                LayerChip("Tampilan", null, tab == LayerTab.LOOK) { onTab(LayerTab.LOOK) }
                LayerChip("Chroma", null, tab == LayerTab.CHROMA) { onTab(LayerTab.CHROMA) }
                LayerChip(
                    if (sel.isAnimated) "Key \u25C6" else "Key", null, tab == LayerTab.KEY
                ) { onTab(LayerTab.KEY) }
            }
            when (tab) {
                LayerTab.TIME -> TimeTab(sel, totalTicks, currentTick, fps, onChange, onCommit, onEditEnd)
                LayerTab.LOOK -> LookTab(sel, onChange, onCommit, onEditEnd)
                LayerTab.KEY -> KeyTab(sel, currentTick, fps, onCommit, onSeek)
                else -> ChromaTab(sel, onChange, onCommit, onEditEnd)
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onCommit(sel.copy(visible = !sel.visible)) }) {
                    Icon(
                        if (sel.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = if (sel.visible) "Sembunyikan layer" else "Tampilkan layer"
                    )
                }
                IconButton(onClick = { onMove(sel.id, true) }) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Naikkan layer")
                }
                IconButton(onClick = { onMove(sel.id, false) }) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "Turunkan layer")
                }
                IconButton(onClick = { onDuplicate(sel) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Duplikat layer")
                }
                if (sel.isVideo && sel.hasAudio) {
                    IconButton(onClick = { onAddAudio(sel) }) {
                        Icon(Icons.Filled.MusicNote, contentDescription = "Tambahkan suara klip ke Audio")
                    }
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Hapus layer", tint = FatihColors.ShutterRed)
                }
            }
        }
    }

    val target = sel
    if (confirmDelete && target != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Hapus layer?") },
            text = { Text("\"${target.name}\" dan file klipnya dihapus dari proyek. Tindakan ini tidak bisa di-undo.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(target) }) {
                    Text("Hapus", color = FatihColors.ShutterRed)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Batal") } }
        )
    }
}

@Composable
private fun TimeTab(
    l: LayerEntity,
    total: Int,
    current: Int,
    fps: Int,
    onChange: (LayerEntity) -> Unit,
    onCommit: (LayerEntity) -> Unit,
    onEditEnd: (Long) -> Unit
) {
    val n = max(total, 1)
    ClipBar(l.startTick, l.lengthTicks, max(n, l.startTick + l.lengthTicks))
    LayerSlider(
        "Mulai", l.startTick.toFloat(), 0f..max(n - 1, 1).toFloat(), seconds(l.startTick, fps),
        onChange = { onChange(l.copy(startTick = it.roundToInt())) },
        onFinish = { onEditEnd(l.id) }
    )
    LayerSlider(
        "Durasi", l.lengthTicks.toFloat(), 1f..max(max(n, l.lengthTicks), 2).toFloat(), seconds(l.lengthTicks, fps),
        onChange = { onChange(l.copy(lengthTicks = it.roundToInt())) },
        onFinish = { onEditEnd(l.id) }
    )
    if (l.isVideo) {
        val maxTrim = max((l.durationMs - 1) / 1000f, 0.1f)
        LayerSlider(
            "Potong awal", l.trimStartMs / 1000f, 0f..maxTrim, String.format(Locale.US, "%.1f dtk", l.trimStartMs / 1000f),
            onChange = { onChange(l.copy(trimStartMs = (it * 1000f).toLong())) },
            onFinish = { onEditEnd(l.id) }
        )
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedButton(onClick = { onCommit(l.copy(startTick = current)) }, modifier = Modifier.height(34.dp)) {
            Text("Mulai di posisi ini", fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
        OutlinedButton(
            onClick = { onCommit(l.copy(lengthTicks = max(1, n - l.startTick))) },
            modifier = Modifier.height(34.dp)
        ) {
            Text("Sampai akhir", fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
        if (l.isVideo) {
            Text("Ulang", fontSize = 12.sp)
            Switch(checked = l.loop, onCheckedChange = { onCommit(l.copy(loop = it)) })
        }
    }
    if (l.isVideo) {
        Hint("Tanpa Ulang, klip hilang setelah selesai diputar. Durasi layer bisa lebih panjang dari klip.")
    }
}

@Composable
private fun LookTab(
    l: LayerEntity,
    onChange: (LayerEntity) -> Unit,
    onCommit: (LayerEntity) -> Unit,
    onEditEnd: (Long) -> Unit
) {
    LayerSlider(
        "Ukuran", l.scale, 0.05f..3f, "${(l.scale * 100).roundToInt()}%",
        onChange = { onChange(l.copy(scale = it)) }, onFinish = { onEditEnd(l.id) }
    )
    LayerSlider(
        "Transparan", l.opacity, 0f..1f, "${(l.opacity * 100).roundToInt()}%",
        onChange = { onChange(l.copy(opacity = it)) }, onFinish = { onEditEnd(l.id) }
    )
    LayerSlider(
        "Putar", l.rotation, -180f..180f, "${l.rotation.roundToInt()}°",
        onChange = { onChange(l.copy(rotation = it)) }, onFinish = { onEditEnd(l.id) }
    )
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedButton(
            onClick = { onCommit(l.copy(centerX = 0.5f, centerY = 0.5f)) },
            modifier = Modifier.height(34.dp)
        ) { Text("Tengah", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        OutlinedButton(
            onClick = { onCommit(l.copy(centerX = 0.5f, centerY = 0.5f, scale = 1f, rotation = 0f)) },
            modifier = Modifier.height(34.dp)
        ) { Text("Pas layar", fontSize = 12.sp, maxLines = 1, softWrap = false) }
    }
    Hint(
        "Geser dengan satu jari di pratinjau untuk memindah, cubit untuk ukuran, putar dengan dua jari. " +
            "Garis ungu = posisi layer; isinya hanya tampil di rentang waktunya."
    )
}

/**
 * Animasi keyframe: posisi, ukuran, putar, dan transparan berubah mulus antar keyframe.
 * Geser slider film ke saat yang diinginkan, atur layer (di pratinjau atau tab Tampilan), lalu ketuk "Tambah key".
 * Setelah ada keyframe, setiap perubahan di tick lain otomatis membuat keyframe baru (auto key).
 */
@Composable
private fun KeyTab(
    l: LayerEntity,
    currentTick: Int,
    fps: Int,
    onCommit: (LayerEntity) -> Unit,
    onSeek: (Int) -> Unit
) {
    val keys = l.keys
    val local = currentTick - l.startTick
    val inRange = local >= 0 && local < l.lengthTicks
    val here = Keyframes.at(keys, local)
    val prev = Keyframes.previous(keys, local)
    val next = Keyframes.next(keys, local)
    val canAdd = inRange && (here != null || keys.size < Keyframes.MAX_KEYS)

    KeyStrip(l, keys, local, onSeekLocal = { onSeek(l.startTick + it) })
    Text(
        when {
            keys.isEmpty() -> "Belum ada keyframe: layer diam."
            !inRange -> "${keys.size} keyframe. Geser film ke dalam rentang layer untuk mengubahnya."
            here != null -> "${keys.size} keyframe  \u2022  di keyframe ${seconds(local, fps)}"
            else -> "${keys.size} keyframe  \u2022  di ${seconds(local, fps)} (antara keyframe)"
        },
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 2.dp)
    )
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            enabled = prev != null,
            onClick = { prev?.let { onSeek(l.startTick + it.tick) } },
            modifier = Modifier.height(34.dp)
        ) { Text("\u25C0", fontSize = 12.sp) }
        OutlinedButton(
            enabled = canAdd,
            onClick = {
                val ease = here?.ease ?: prev?.ease ?: Ease.LINEAR
                val updated = Keyframes.upsert(keys, Keyframe(local, l.basePose(), ease))
                onCommit(l.copy(keyframes = Keyframes.encode(updated)))
            },
            modifier = Modifier.height(34.dp)
        ) { Text(if (here != null) "Perbarui key" else "Tambah key", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        OutlinedButton(
            enabled = next != null,
            onClick = { next?.let { onSeek(l.startTick + it.tick) } },
            modifier = Modifier.height(34.dp)
        ) { Text("\u25B6", fontSize = 12.sp) }
        OutlinedButton(
            enabled = here != null,
            onClick = { onCommit(l.copy(keyframes = Keyframes.encode(Keyframes.remove(keys, local)))) },
            modifier = Modifier.height(34.dp)
        ) { Text("Hapus key", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        OutlinedButton(
            enabled = keys.isNotEmpty(),
            onClick = { onCommit(l.copy(keyframes = null)) },
            modifier = Modifier.height(34.dp)
        ) { Text("Hapus semua", fontSize = 12.sp, maxLines = 1, softWrap = false) }
    }
    if (here != null) {
        Text("Gerak menuju key berikutnya:", fontSize = 11.sp, color = FatihColors.TextSecondary,
            modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
            Ease.labels.forEachIndexed { i, label ->
                LayerChip(label, null, here.ease == i) {
                    onCommit(l.copy(keyframes = Keyframes.encode(Keyframes.upsert(keys, here.copy(ease = i)))))
                }
            }
        }
    }
    Hint(
        "Contoh peluru: di awal layer letakkan peluru di moncong senjata, ketuk Tambah key; geser film ke " +
            "frame tujuan, tarik peluru ke sasaran (key dibuat otomatis). Lurus = kecepatan tetap, " +
            "Halus = pelan di ujung, Tahan = diam lalu melompat. Putar boleh lebih dari satu putaran."
    )
}

/** Strip waktu layer: wajik ungu = keyframe, garis putih = posisi film saat ini. Ketuk untuk loncat ke waktu itu. */
@Composable
private fun KeyStrip(l: LayerEntity, keys: List<Keyframe>, currentLocal: Int, onSeekLocal: (Int) -> Unit) {
    val len = max(l.lengthTicks, 1)
    val span = (len - 1).coerceAtLeast(1).toFloat()
    val trackColor = FatihColors.SurfaceHigh
    val keyColor = FatihColors.Purple
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(len) {
                detectTapGestures { pos ->
                    onSeekLocal((pos.x / size.width * span).roundToInt().coerceIn(0, len - 1))
                }
            }
    ) {
        drawRect(trackColor)
        val cy = size.height / 2f
        val r = 7.dp.toPx()
        for (k in keys) {
            val x = (k.tick / span).coerceIn(0f, 1f) * size.width
            val d = Path().apply {
                moveTo(x, cy - r); lineTo(x + r, cy); lineTo(x, cy + r); lineTo(x - r, cy); close()
            }
            drawPath(d, if (k.tick == currentLocal) Color.White else keyColor)
        }
        if (currentLocal in 0 until len) {
            val x = (currentLocal / span).coerceIn(0f, 1f) * size.width
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}

@Composable
private fun ChromaTab(
    l: LayerEntity,
    onChange: (LayerEntity) -> Unit,
    onCommit: (LayerEntity) -> Unit,
    onEditEnd: (Long) -> Unit
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LayerChip("Mati", null, l.chromaMode == ChromaMode.OFF, swatch = Color(0xFF55555F)) {
            onCommit(l.copy(chromaMode = ChromaMode.OFF))
        }
        LayerChip("Hijau", null, l.chromaMode == ChromaMode.GREEN, swatch = Color(0xFF1DB954)) {
            onCommit(l.copy(chromaMode = ChromaMode.GREEN))
        }
        LayerChip("Biru", null, l.chromaMode == ChromaMode.BLUE, swatch = Color(0xFF2F6FED)) {
            onCommit(l.copy(chromaMode = ChromaMode.BLUE))
        }
    }
    if (l.chromaMode != ChromaMode.OFF) {
        LayerSlider(
            "Toleransi", l.chromaTolerance, 0f..1f, "${(l.chromaTolerance * 100).roundToInt()}%",
            onChange = { onChange(l.copy(chromaTolerance = it)) }, onFinish = { onEditEnd(l.id) }
        )
        LayerSlider(
            "Tepi halus", l.chromaSoftness, 0f..1f, "${(l.chromaSoftness * 100).roundToInt()}%",
            onChange = { onChange(l.copy(chromaSoftness = it)) }, onFinish = { onEditEnd(l.id) }
        )
    } else {
        Hint("Klip berlatar hijau? Pilih Hijau (atau Biru) agar latarnya hilang dan hanya efeknya yang tampil di atas foto.")
    }
}

@Composable
private fun AddButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(FatihColors.ShutterRed)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = FatihColors.OnAccent, modifier = Modifier.size(14.dp))
        Icon(icon, contentDescription = null, tint = FatihColors.OnAccent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, color = FatihColors.OnAccent, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun LayerChip(
    label: String,
    layer: LayerEntity?,
    selected: Boolean,
    swatch: Color? = null,
    dim: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .alpha(if (dim) 0.55f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) FatihColors.Purple else FatihColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (swatch != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(swatch))
            Spacer(Modifier.width(4.dp))
        }
        if (layer != null && layer.isVideo) {
            Icon(Icons.Filled.Videocam, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            fontSize = 12.sp,
            maxLines = 1,
            softWrap = false,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = FatihColors.TextSecondary,
        modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp)
    )
}

@Composable
private fun LayerSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    text: String,
    onChange: (Float) -> Unit,
    onFinish: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, maxLines = 1, softWrap = false, modifier = Modifier.width(84.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onFinish,
            valueRange = range,
            modifier = Modifier.weight(1f).height(28.dp)
        )
        Text(
            text,
            fontSize = 11.sp,
            color = FatihColors.TextSecondary,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(56.dp).padding(start = 4.dp)
        )
    }
}

/** Batang kecil yang menunjukkan di mana klip berada di timeline film. */
@Composable
private fun ClipBar(start: Int, length: Int, total: Int) {
    val t = max(total, 1).toFloat()
    val s = (start / t).coerceIn(0f, 1f)
    val e = ((start + length) / t).coerceIn(s, 1f)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(FatihColors.SurfaceHigh)
    ) {
        if (s > 0f) Spacer(Modifier.weight(s))
        if (e - s > 0f) Box(Modifier.weight(e - s).fillMaxHeight().background(FatihColors.Purple))
        if (1f - e > 0f) Spacer(Modifier.weight(1f - e))
    }
}
