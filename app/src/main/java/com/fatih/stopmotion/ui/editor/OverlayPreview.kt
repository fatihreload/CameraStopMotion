package com.fatih.stopmotion.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fatih.stopmotion.data.KeyedBitmapCache
import com.fatih.stopmotion.data.LayerEntity
import com.fatih.stopmotion.layers.LayerSource
import com.fatih.stopmotion.layers.LayerTiming
import com.fatih.stopmotion.layers.chromaState
import com.fatih.stopmotion.layers.isVideo
import com.fatih.stopmotion.layers.sanitized
import com.fatih.stopmotion.layers.timing
import com.fatih.stopmotion.ui.theme.FatihColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Menyiapkan bitmap layer overlay untuk pratinjau di Studio Animasi. Frame video diambil dan di-chroma-key
 * di thread latar (hasil di-cache), sementara layar tetap menampilkan frame layer yang terakhir siap, jadi
 * tidak berkedip. Semua fungsi publik dipanggil dari thread UI.
 */
class OverlayPreviewEngine(private val scope: CoroutineScope) {
    private val cache = KeyedBitmapCache(cacheBytes())
    private val pool = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    private val sources = HashMap<Long, Pair<String, LayerSource>>()
    private val inflight = HashSet<String>()
    private val lastShown = HashMap<Long, Bitmap>()

    /** Naik setiap ada bitmap baru siap; dibaca di blok gambar supaya layar digambar ulang. */
    var version by mutableIntStateOf(0)
        private set

    private fun cacheKey(l: LayerEntity, timeMs: Long): String =
        "${l.id}|${l.filePath.hashCode()}|$timeMs|${l.chromaState().cacheKey()}"

    /** Bitmap layer pada tick ini bila sudah siap; bila belum, bitmap terakhir yang tampil (atau null). */
    fun peek(l: LayerEntity, tick: Int, fps: Int): Bitmap? {
        val t = LayerTiming.sourceTimeMs(l.timing(), tick, fps) ?: return null
        val hit = cache.get(cacheKey(l, t))
        if (hit != null) {
            lastShown[l.id] = hit
            return hit
        }
        return lastShown[l.id]
    }

    private fun sourceFor(l: LayerEntity): LayerSource {
        val cur = sources[l.id]
        if (cur != null && cur.first == l.filePath) return cur.second
        cur?.second?.close()
        return LayerSource(l.filePath, l.isVideo).also { sources[l.id] = l.filePath to it }
    }

    /**
     * Meminta bitmap untuk tick sekarang dan [lookahead] tick berikutnya (saat playback agar mulus).
     * Permintaan yang sama tidak diulang selama masih diproses.
     */
    fun ensure(layers: List<LayerEntity>, tick: Int, fps: Int, lookahead: Int, lastTick: Int) {
        val ids = layers.map { it.id }.toSet()
        val gone = sources.keys.filter { it !in ids }
        for (id in gone) {
            sources.remove(id)?.second?.close()
            lastShown.remove(id)
        }
        for (l in layers) {
            if (!l.visible) continue
            for (k in 0..lookahead) {
                val tk = tick + k
                if (tk > lastTick) break
                val t = LayerTiming.sourceTimeMs(l.timing(), tk, fps) ?: continue
                val key = cacheKey(l, t)
                if (cache.get(key) != null || !inflight.add(key)) continue
                val source = sourceFor(l)
                val chroma = l.chromaState()
                scope.launch {
                    val bmp = withContext(pool) { source.render(t, PREVIEW_SIDE, chroma) }
                    if (bmp != null) cache.put(key, bmp)
                    inflight.remove(key)
                    version++
                }
            }
        }
    }

    fun close() {
        for ((_, p) in sources) p.second.close()
        sources.clear()
        lastShown.clear()
        cache.clear()
        pool.close()
    }

    companion object {
        /** Sisi terpanjang bitmap pratinjau layer; ekspor memakai ukuran sebenarnya. */
        const val PREVIEW_SIDE = 720

        private fun cacheBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 8).toInt().coerceIn(24 * 1024 * 1024, 96 * 1024 * 1024)
    }
}

/** Menggambar semua layer overlay yang tampil pada [tick], dari layer terbawah ke teratas. */
@Composable
fun OverlayLayersView(
    layers: List<LayerEntity>,
    tick: Int,
    fps: Int,
    engine: OverlayPreviewEngine,
    colorFilter: ColorFilter?,
    selectedId: Long,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        engine.version // membaca state: gambar ulang saat bitmap baru siap
        for (l in layers) {
            if (!l.visible) continue
            val active = LayerTiming.sourceTimeMs(l.timing(), tick, fps) != null
            val bmp = if (active) engine.peek(l, tick, fps) else null
            val dw = l.scale * size.width
            val dh = if (bmp != null) dw * bmp.height / bmp.width else dw / l.aspect.coerceAtLeast(0.01f)
            val cx = l.centerX * size.width
            val cy = l.centerY * size.height
            rotate(l.rotation, pivot = Offset(cx, cy)) {
                if (bmp != null) {
                    drawImage(
                        image = bmp.asImageBitmap(),
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(bmp.width, bmp.height),
                        dstOffset = IntOffset((cx - dw / 2f).roundToInt(), (cy - dh / 2f).roundToInt()),
                        dstSize = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)),
                        alpha = l.opacity,
                        colorFilter = colorFilter,
                        filterQuality = FilterQuality.Medium
                    )
                }
                if (l.id == selectedId) {
                    drawRect(
                        color = FatihColors.Purple,
                        topLeft = Offset(cx - dw / 2f, cy - dh / 2f),
                        size = Size(dw, dh),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }
    }
}

/**
 * Lapisan sentuh di atas pratinjau untuk layer terpilih: geser satu jari memindahkan, cubit mengubah ukuran,
 * dua jari memutar. [onChange] dipanggil terus selama gerakan, [onCommit] sekali saat jari dilepas.
 */
@Composable
fun LayerGestureLayer(
    layer: LayerEntity,
    onChange: (LayerEntity) -> Unit,
    onCommit: (LayerEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val latest by rememberUpdatedState(layer)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    Box(
        modifier.pointerInput(layer.id) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var cur = latest
                var moved = false
                do {
                    val event = awaitPointerEvent()
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    val rot = event.calculateRotation()
                    if (zoom != 1f || pan != Offset.Zero || rot != 0f) {
                        moved = true
                        cur = cur.copy(
                            centerX = cur.centerX + pan.x / size.width,
                            centerY = cur.centerY + pan.y / size.height,
                            scale = cur.scale * zoom,
                            rotation = cur.rotation + rot
                        ).sanitized()
                        change(cur)
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                } while (event.changes.any { it.pressed })
                if (moved) commit(cur)
            }
        }
    )
}
