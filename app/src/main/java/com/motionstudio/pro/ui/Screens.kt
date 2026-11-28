package com.motionstudio.pro.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.motionstudio.pro.data.Clip
import com.motionstudio.pro.data.ClipType
import com.motionstudio.pro.data.ProjectSummary
import java.io.File

@Composable
fun ClipThumb(clip: Clip?, modifier: Modifier = Modifier, frameMs: Long = 0) {
    val ctx = LocalContext.current
    if (clip == null) {
        Box(modifier.background(SurfHigh), Alignment.Center) { Icon(Icons.Default.Movie, null, tint = Color.Gray) }
        return
    }
    val req = ImageRequest.Builder(ctx).data(clip.uri).apply {
        if (clip.type == ClipType.VIDEO) videoFrameMillis(frameMs)
    }.crossfade(true).build()
    AsyncImage(req, clip.name, modifier, contentScale = ContentScale.Crop)
}

@Composable
fun HomeScreen(onOpen: (String) -> Unit, onLibrary: () -> Unit, onAssets: () -> Unit, onSettings: () -> Unit,
               vm: LibraryViewModel = viewModel()) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    Scaffold(containerColor = Bg) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Spacer(Modifier.height(24.dp))
                Text("Motion Studio Pro", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Edit video profesional dari smartphone", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(20.dp)).brandBg()
                    .clickable { vm.create(onOpen) }, Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Proyek baru", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                    HomeTile("Proyek", Icons.Default.Folder, Modifier.weight(1f), onLibrary)
                    HomeTile("Aset", Icons.Default.LibraryMusic, Modifier.weight(1f), onAssets)
                    HomeTile("Setelan", Icons.Default.Settings, Modifier.weight(1f), onSettings)
                }
            }
            item { Text("Terakhir diedit", style = MaterialTheme.typography.titleMedium) }
            if (projects.isEmpty()) item { Text("Belum ada proyek.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(projects.take(5), key = { it.id }) { ProjectRow(it, onClick = { onOpen(it.id) }) }
        }
    }
}

@Composable
private fun HomeTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) =
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Surf).clickable(onClick = onClick).padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Cyan); Spacer(Modifier.height(6.dp)); Text(label)
    }

@Composable
fun ProjectRow(p: ProjectSummary, onClick: () -> Unit, menu: (@Composable () -> Unit)? = null) =
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surf).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ClipThumb(p.thumb, Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${p.clipCount} klip • ${formatTime(p.durationMs)}", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
        menu?.invoke()
    }

@Composable
fun LibraryScreen(onBack: () -> Unit, onOpen: (String) -> Unit, vm: LibraryViewModel = viewModel()) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }
    Scaffold(containerColor = Bg, topBar = { BasicTopBar("Project Library", onBack) },
        floatingActionButton = { FloatingActionButton({ vm.create(onOpen) }, containerColor = Purple) { Icon(Icons.Default.Add, "Baru") } }) { pad ->
        if (projects.isEmpty()) ComingSoon("Belum ada proyek", "Ketuk + untuk membuat proyek pertama.", Modifier.padding(pad))
        else LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(projects, key = { it.id }) { p ->
                var open by remember { mutableStateOf(false) }
                ProjectRow(p, onClick = { onOpen(p.id) }) {
                    Box {
                        IconButton({ open = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                        DropdownMenu(open, { open = false }) {
                            DropdownMenuItem({ Text("Ganti nama") }, { open = false; renaming = p })
                            DropdownMenuItem({ Text("Duplikat") }, { open = false; vm.duplicate(p.id) })
                            DropdownMenuItem({ Text("Hapus") }, { open = false; deleting = p })
                        }
                    }
                }
            }
        }
    }
    renaming?.let { p ->
        var text by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Ganti nama") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton({ vm.rename(p.id, text); renaming = null }) { Text("Simpan") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Batal") } })
    }
    deleting?.let { p ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Hapus proyek?") },
            text = { Text("\"${p.name}\" akan dihapus. File media asli tidak terhapus.") },
            confirmButton = { TextButton({ vm.delete(p.id); deleting = null }) { Text("Hapus") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Batal") } })
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var cacheMb by remember { mutableStateOf(cacheSize(ctx)) }
    Scaffold(containerColor = Bg, topBar = { BasicTopBar("Settings", onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Penyimpanan", style = MaterialTheme.typography.titleMedium)
            Text("Cache ekspor sementara: $cacheMb", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button({
                ctx.cacheDir.listFiles()?.filter { it.name.startsWith("export_") }?.forEach { it.delete() }
                cacheMb = cacheSize(ctx)
            }) { Text("Bersihkan cache ekspor") }
            HorizontalDivider()
            Text("Tentang", style = MaterialTheme.typography.titleMedium)
            Text("Motion Studio Pro 0.1.0 (Tahap 1)\nFitur lanjutan (audio, teks, efek, dll.) menyusul di tahap berikutnya.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun cacheSize(ctx: Context): String =
    formatSize(ctx.cacheDir.listFiles()?.filter { it.name.startsWith("export_") }?.sumOf(File::length) ?: 0L)

@Composable
fun AssetLibraryScreen(onBack: () -> Unit) = Scaffold(containerColor = Bg, topBar = { BasicTopBar("Asset Library", onBack) }) {
    ComingSoon("Belum tersedia", "Koleksi musik, sound effect, font, dan stiker bawaan hadir di Tahap 2–3.", Modifier.padding(it))
}
