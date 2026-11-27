package com.fatih.stopmotion.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fatih.stopmotion.ui.theme.FatihColors

private val TabNames = listOf("Fokus", "WB", "Eksposur", "Zoom")

/**
 * Panel kontrol kamera: fokus (4 mode), white balance (8 pilihan), kompensasi eksposur, shutter, ISO, dan zoom
 * 1.0x sampai 10.0x. Fitur yang tidak didukung kamera aktif dinonaktifkan dan diberi keterangan.
 */
@Composable
fun CameraAdjustPanel(
    adjust: CameraAdjust,
    caps: CameraCaps,
    measured: Measured,
    enabled: Boolean,
    onFocusMode: (Int) -> Unit,
    onChange: (CameraAdjust) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(
        modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(FatihColors.Surface.copy(alpha = 0.94f))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Kontrol kamera", fontWeight = FontWeight.Bold)
                Text(readout(measured), fontSize = 11.sp, color = FatihColors.TextSecondary, maxLines = 1)
            }
            TextButton(onClick = onReset) { Text("Reset", fontSize = 12.sp) }
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Tutup kontrol kamera")
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TabNames.forEachIndexed { i, name -> Chip(name, tab == i, true) { tab = i } }
        }
        Column(
            Modifier
                .heightIn(max = 230.dp)
                .alpha(if (enabled) 1f else 0.5f)
                .verticalScroll(rememberScrollState())
                .padding(top = 6.dp)
        ) {
            when (tab) {
                0 -> FocusTab(adjust, caps, measured, enabled, onFocusMode, onChange)
                1 -> WbTab(adjust, caps, enabled, onChange)
                2 -> ExposureTab(adjust, caps, measured, enabled, onChange)
                else -> ZoomTab(adjust, caps, enabled, onChange)
            }
        }
    }
}

private fun readout(m: Measured): String {
    val parts = ArrayList<String>()
    if (m.focusDiopter >= 0f) parts.add("Fokus " + CameraMath.formatFocus(m.focusDiopter))
    if (m.exposureNs > 0L) parts.add("Shutter " + CameraMath.formatShutter(m.exposureNs))
    if (m.iso > 0) parts.add("ISO ${m.iso}")
    return if (parts.isEmpty()) "Menunggu data kamera..." else parts.joinToString("  •  ")
}

// ---------------- Fokus ----------------

@Composable
private fun FocusTab(
    a: CameraAdjust,
    caps: CameraCaps,
    m: Measured,
    enabled: Boolean,
    onFocusMode: (Int) -> Unit,
    onChange: (CameraAdjust) -> Unit
) {
    FocusMode.labels.forEachIndexed { i, label ->
        val selected = a.focusMode == i
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) FatihColors.Purple else FatihColors.SurfaceHigh)
                .clickable(enabled = enabled) { onFocusMode(i) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
    if (a.focusMode == FocusMode.MANUAL) {
        val diopter = CameraMath.diopterFromFraction(a.focusFraction, caps)
        AdjSlider(
            "Jarak", a.focusFraction, 0f..1f, CameraMath.formatFocus(diopter),
            enabled = enabled && caps.manualFocus
        ) { onChange(a.copy(focusFraction = it)) }
        Text(
            "Kiri = jauh (∞), kanan = dekat.",
            fontSize = 11.sp, color = FatihColors.TextSecondary
        )
    }
    val note = when {
        !caps.manualFocus && a.focusMode != FocusMode.AUTO ->
            "Kamera ini punya fokus tetap, jadi pengaturan fokus tidak berpengaruh."
        a.focusMode == FocusMode.AUTO -> "Fokus dan eksposur diatur otomatis oleh kamera."
        a.focusMode == FocusMode.LOCK ->
            "Fokus dan eksposur terkunci pada nilai saat dipilih. Arahkan dan fokuskan dulu dengan Auto focus, " +
                "lalu pilih mode ini agar semua frame sama."
        a.focusMode == FocusMode.LOCK_MANUAL_EXPOSURE ->
            "Fokus terkunci. Shutter dan ISO diatur manual di tab Eksposur (awalnya sama dengan nilai sekarang)."
        else -> null
    }
    if (note != null) Text(note, fontSize = 11.sp, color = FatihColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
}

// ---------------- White balance ----------------

@Composable
private fun WbTab(a: CameraAdjust, caps: CameraCaps, enabled: Boolean, onChange: (CameraAdjust) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Awb.options.forEachIndexed { i, opt ->
            Chip(opt.label, a.awb == i, enabled && caps.supports(opt), dimWhenOff = true) { onChange(a.copy(awb = i)) }
        }
    }
    Text(
        "Terkunci = warna dibekukan pada keseimbangan saat ini. Pilihan redup tidak didukung kamera ini. " +
            "Kunci white balance agar warna tidak berubah antar frame.",
        fontSize = 11.sp, color = FatihColors.TextSecondary, modifier = Modifier.padding(top = 6.dp)
    )
}

// ---------------- Eksposur: kompensasi, shutter, ISO ----------------

@Composable
private fun ExposureTab(
    a: CameraAdjust,
    caps: CameraCaps,
    m: Measured,
    enabled: Boolean,
    onChange: (CameraAdjust) -> Unit
) {
    val manual = a.manualExposure && caps.manualExposure
    AdjSlider(
        "Kompensasi", a.ev.toFloat().coerceIn(caps.evMin.toFloat(), caps.evMax.toFloat()),
        caps.evMin.toFloat()..maxOf(caps.evMax, caps.evMin + 1).toFloat(),
        if (caps.evSupported) CameraMath.formatEv(a.ev, caps) else "-",
        enabled = enabled && caps.evSupported && !manual,
        steps = (caps.evMax - caps.evMin - 1).coerceAtLeast(0)
    ) { onChange(a.copy(ev = it.toInt())) }

    val shutterShown = if (a.shutterNs > 0L) a.shutterNs else m.exposureNs
    Row(verticalAlignment = Alignment.CenterVertically) {
        Chip("Auto", a.shutterNs == 0L, enabled && a.focusMode != FocusMode.LOCK_MANUAL_EXPOSURE) {
            onChange(a.withShutter(0L))
        }
    }
    AdjSlider(
        "Shutter",
        CameraMath.fractionFromShutter(
            if (shutterShown > 0L) shutterShown else CameraMath.DEFAULT_SHUTTER_NS, caps
        ),
        0f..1f,
        if (a.shutterNs > 0L) CameraMath.formatShutter(a.shutterNs)
        else "Auto " + CameraMath.formatShutter(m.exposureNs),
        enabled = enabled && caps.manualExposure
    ) { onChange(a.withShutter(CameraMath.shutterFromFraction(it, caps))) }

    val isoShown = if (a.iso > 0) a.iso else m.iso
    Row(verticalAlignment = Alignment.CenterVertically) {
        Chip("Auto", a.iso == 0, enabled && a.focusMode != FocusMode.LOCK_MANUAL_EXPOSURE) {
            onChange(a.withIso(0))
        }
    }
    AdjSlider(
        "ISO",
        CameraMath.fractionFromIso(if (isoShown > 0) isoShown else CameraMath.DEFAULT_ISO, caps),
        0f..1f,
        if (a.iso > 0) "${a.iso}" else "Auto ${if (m.iso > 0) m.iso.toString() else ""}".trim(),
        enabled = enabled && caps.manualExposure
    ) { onChange(a.withIso(CameraMath.isoFromFraction(it, caps))) }

    val note = when {
        !caps.manualExposure -> "Kamera ini tidak mendukung shutter dan ISO manual."
        manual -> "Eksposur manual aktif: kompensasi eksposur dan flash tidak berlaku. Geser Shutter/ISO atau " +
            "ketuk Auto untuk kembali ke otomatis."
        else -> "Geser Shutter atau ISO untuk eksposur manual. Cocok untuk stop motion agar kecerahan tidak berkedip."
    }
    Text(note, fontSize = 11.sp, color = FatihColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
}

// ---------------- Zoom ----------------

@Composable
private fun ZoomTab(a: CameraAdjust, caps: CameraCaps, enabled: Boolean, onChange: (CameraAdjust) -> Unit) {
    val max = CameraMath.zoomMax(caps)
    val zoom = CameraMath.clampZoom(a.zoom, caps)
    AdjSlider(
        "Zoom", zoom, CameraMath.MIN_ZOOM..maxOf(max, CameraMath.MIN_ZOOM + 0.1f), CameraMath.formatZoom(zoom),
        enabled = enabled && max > CameraMath.MIN_ZOOM
    ) { onChange(a.copy(zoom = (it * 10f).toInt() / 10f)) }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (z in listOf(1f, 2f, 3f, 5f, 10f)) {
            Chip(CameraMath.formatZoom(z), zoom == z, enabled && z <= max) { onChange(a.copy(zoom = z)) }
        }
    }
    Text(
        if (max < CameraMath.MAX_ZOOM) "Zoom maksimum kamera ini ${CameraMath.formatZoom(max)}."
        else "Zoom 1.0x sampai 10.0x. Zoom digital membuat foto kurang tajam.",
        fontSize = 11.sp, color = FatihColors.TextSecondary, modifier = Modifier.padding(top = 4.dp)
    )
}

// ---------------- Komponen kecil ----------------

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    dimWhenOff: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .alpha(if (!enabled && dimWhenOff) 0.35f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) FatihColors.Purple else FatihColors.SurfaceHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, fontSize = 12.sp, maxLines = 1, softWrap = false,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun AdjSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    text: String,
    enabled: Boolean,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (enabled) 1f else 0.45f)) {
        Text(label, fontSize = 12.sp, maxLines = 1, softWrap = false, modifier = Modifier.width(84.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.weight(1f).height(28.dp)
        )
        Text(
            text, fontSize = 11.sp, color = FatihColors.TextSecondary, textAlign = TextAlign.End,
            maxLines = 1, softWrap = false, modifier = Modifier.width(72.dp).padding(start = 4.dp)
        )
    }
}
