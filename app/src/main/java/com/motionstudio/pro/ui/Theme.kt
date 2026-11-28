package com.motionstudio.pro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

val Bg = Color(0xFF0B0B10)
val Surf = Color(0xFF16161F)
val SurfHigh = Color(0xFF212130)
val Purple = Color(0xFF8B5CF6)
val Cyan = Color(0xFF22D3EE)
val brand = Brush.horizontalGradient(listOf(Purple, Cyan))

private val scheme = darkColorScheme(
    primary = Purple, secondary = Cyan, background = Bg, surface = Surf,
    surfaceVariant = SurfHigh, onPrimary = Color.White, onSurface = Color(0xFFECECF4),
    onBackground = Color(0xFFECECF4), onSurfaceVariant = Color(0xFFA0A0B8),
)

@Composable fun MotionTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

fun formatTime(ms: Long): String {
    val t = ms.coerceAtLeast(0); val m = t / 60000; val s = (t / 1000) % 60; val d = (t % 1000) / 100
    return "%02d:%02d.%d".format(m, s, d)
}

fun formatSize(b: Long): String = when {
    b <= 0 -> "-"; b < 1_000_000 -> "${b / 1000} KB"; else -> "%.1f MB".format(b / 1_000_000.0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasicTopBar(title: String, onBack: () -> Unit) = TopAppBar(
    title = { Text(title) },
    navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") } },
    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = MaterialTheme.colorScheme.onSurface,
        navigationIconContentColor = MaterialTheme.colorScheme.onSurface),
)

@Composable
fun ComingSoon(title: String, note: String, modifier: Modifier = Modifier) = Column(
    modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally,
) {
    Icon(Icons.Default.Construction, null, tint = Cyan, modifier = Modifier.size(48.dp))
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(6.dp))
    Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
fun Modifier.brandBg(): Modifier = this.background(brand)
