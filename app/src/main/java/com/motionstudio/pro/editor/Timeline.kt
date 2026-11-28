package com.motionstudio.pro.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motionstudio.pro.data.Clip
import com.motionstudio.pro.ui.*

/**
 * Timeline satu track utama. Geser untuk scrub (playhead tetap di tengah), cubit 2 jari untuk zoom.
 * Layer lain (audio, teks, overlay) ditambahkan di tahap berikutnya.
 */
@Composable
fun Timeline(
    clips: List<Clip>, selectedId: String?, playheadMs: Long, totalMs: Long, dpPerSec: Float,
    onZoom: (Float) -> Unit, onSelect: (String) -> Unit, onSeek: (Long) -> Unit, modifier: Modifier = Modifier,
) {
    val zoomState = rememberUpdatedState(onZoom)
    BoxWithConstraints(
        modifier.background(Surf).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                do {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    if (e.changes.size > 1) {
                        val z = e.calculateZoom()
                        if (z != 1f) { zoomState.value(z); e.changes.forEach { it.consume() } }
                    }
                } while (e.changes.any { it.pressed })
            }
        },
    ) {
        val density = LocalDensity.current
        val pxPerMs = with(density) { dpPerSec.dp.toPx() } / 1000f
        val half = maxWidth / 2
        val scroll = rememberScrollState()

        LaunchedEffect(scroll.value) {
            if (scroll.isScrollInProgress) onSeek((scroll.value / pxPerMs).toLong().coerceIn(0, totalMs))
        }
        LaunchedEffect(playheadMs, pxPerMs, totalMs) {
            if (!scroll.isScrollInProgress) scroll.scrollTo((playheadMs * pxPerMs).toInt())
        }

        Row(Modifier.horizontalScroll(scroll).fillMaxHeight().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(half))
            clips.forEach { c ->
                val w = with(density) { (c.durationMs * pxPerMs).toDp() }
                val sel = c.id == selectedId
                Box(
                    Modifier.width(w).fillMaxHeight().clip(RoundedCornerShape(6.dp))
                        .border(if (sel) 2.dp else 1.dp, if (sel) Cyan else Bg, RoundedCornerShape(6.dp))
                        .clickable { onSelect(c.id) },
                ) {
                    ClipThumb(c, Modifier.fillMaxSize(), frameMs = c.inMs)
                    Text(formatTime(c.durationMs), fontSize = 10.sp, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 4.dp))
                }
            }
            Spacer(Modifier.width(half))
        }
        Box(Modifier.align(Alignment.Center).width(2.dp).fillMaxHeight().background(Color.White))
    }
}
