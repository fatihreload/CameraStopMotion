package com.motionstudio.pro.export

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.motionstudio.pro.ui.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(id: String, onBack: () -> Unit, vm: ExportViewModel = viewModel()) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(id) { vm.load(id) }
    val running = state is ExportState.Running

    Scaffold(containerColor = Bg, topBar = { BasicTopBar("Export", { if (!running) onBack() }) }) { pad ->
        Column(Modifier.padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Resolusi", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(480, 720, 1080).forEachIndexed { i, h ->
                    SegmentedButton(vm.height == h, { vm.height = h }, SegmentedButtonDefaults.itemShape(i, 3), enabled = !running) { Text("${h}p") }
                }
            }
            Text("Kualitas", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Quality.entries.forEachIndexed { i, q ->
                    SegmentedButton(vm.quality == q, { vm.quality = q }, SegmentedButtonDefaults.itemShape(i, 3), enabled = !running) { Text(q.label) }
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Codec H.265 (HEVC)")
                    Text("Lebih kecil; otomatis kembali ke H.264 jika perangkat tidak mendukung.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(vm.h265, { vm.h265 = it }, enabled = !running)
            }
            Text("Perkiraan ukuran: ${formatSize(vm.estimateBytes())}  •  ${vm.bitrate() / 1000} kbps", color = Cyan)
            Text("2K/4K, pilihan FPS, dan audio-only menyusul di tahap berikutnya.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            when (val s = state) {
                is ExportState.Idle -> Button({ vm.start() }, Modifier.fillMaxWidth(), enabled = vm.project != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Purple)) { Text("Mulai ekspor") }
                is ExportState.Running -> {
                    LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth(), color = Cyan)
                    Text("Merender… ${(s.progress * 100).toInt()}%")
                    OutlinedButton({ vm.cancel() }) { Text("Batal") }
                }
                is ExportState.Done -> {
                    Text("Selesai! Tersimpan di Movies/MotionStudioPro", color = Cyan)
                    Button({
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, s.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Bagikan video"))
                    }, Modifier.fillMaxWidth()) { Text("Bagikan") }
                    OutlinedButton({ vm.reset() }, Modifier.fillMaxWidth()) { Text("Ekspor lagi") }
                }
                is ExportState.Failed -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    Button({ vm.reset() }) { Text("Coba lagi") }
                }
            }
        }
    }
}
