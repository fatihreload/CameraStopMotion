package com.fatih.stopmotion.layers

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.fatih.stopmotion.fx.ChromaKey
import com.fatih.stopmotion.fx.ChromaState
import com.fatih.stopmotion.fx.ImageFx
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

data class MediaProbe(val durationMs: Long, val aspect: Float, val hasAudio: Boolean)

object LayerMedia {

    /** Membaca info dasar file media. null bila file tidak dapat dibaca sebagai video/gambar. */
    fun probe(file: File, isVideo: Boolean): MediaProbe? {
        if (!isVideo) {
            val b = ImageFx.bounds(file.path) ?: return null
            return MediaProbe(0L, b.first / b.second.toFloat(), false)
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            // Ukuran dari frame sungguhan: sudah memperhitungkan rotasi video dari kamera ponsel.
            val frame = r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
            val aspect = if (frame != null && frame.height > 0) {
                (frame.width / frame.height.toFloat()).also { frame.recycle() }
            } else {
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 0f
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 0f
                if (w > 0f && h > 0f) w / h else return null
            }
            MediaProbe(duration.coerceAtLeast(1L), aspect, hasAudio)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Mengecilkan bila sisi terpanjang melebihi maxLongSide. Bila dikecilkan, src di-recycle. */
    internal fun fitLongSide(src: Bitmap, maxLongSide: Int): Bitmap {
        val longSide = max(src.width, src.height)
        if (maxLongSide <= 0 || longSide <= maxLongSide) return src
        val s = maxLongSide / longSide.toFloat()
        val out = Bitmap.createScaledBitmap(
            src, (src.width * s).roundToInt().coerceAtLeast(1), (src.height * s).roundToInt().coerceAtLeast(1), true
        )
        if (out !== src) src.recycle()
        return out
    }
}

/**
 * Sumber piksel satu layer (video atau gambar). Boleh dipakai dari beberapa thread: semua akses ke
 * decoder disinkronkan. Bitmap yang dikembalikan selalu milik pemanggil (boleh di-recycle).
 */
class LayerSource(private val path: String, private val isVideo: Boolean) {
    private var retriever: MediaMetadataRetriever? = null
    private var still: Bitmap? = null
    private var stillSide = 0
    private var closed = false

    /**
     * Frame pada waktu [sourceTimeMs] (video) atau gambar (waktu diabaikan), setelah chroma key bila aktif.
     * Sisi terpanjang hasil paling besar [maxLongSide]. null bila gagal dibaca atau sumber sudah ditutup.
     */
    @Synchronized
    fun render(sourceTimeMs: Long, maxLongSide: Int, chroma: ChromaState): Bitmap? {
        if (closed) return null
        val raw = try {
            if (isVideo) videoFrame(sourceTimeMs, maxLongSide) else imageCopy(maxLongSide)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null
        if (!chroma.active) return raw
        return try {
            ChromaKey.render(raw, chroma, maxLongSide).also { raw.recycle() }
        } catch (e: OutOfMemoryError) {
            raw.recycle()
            null
        }
    }

    private fun imageCopy(maxLongSide: Int): Bitmap? {
        val cur = still
        if (cur == null || cur.isRecycled || stillSide < maxLongSide) {
            cur?.recycle()
            still = ImageFx.decode(path, maxLongSide)
            stillSide = maxLongSide
        }
        val base = still ?: return null
        val copy = base.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        return LayerMedia.fitLongSide(copy, maxLongSide)
    }

    private fun videoFrame(timeMs: Long, maxLongSide: Int): Bitmap? {
        val r = retriever ?: MediaMetadataRetriever().also {
            it.setDataSource(path)
            retriever = it
        }
        val us = timeMs.coerceAtLeast(0L) * 1000L
        val frame = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: return null
        val argb = if (frame.config == Bitmap.Config.ARGB_8888) frame else {
            frame.copy(Bitmap.Config.ARGB_8888, true).also { frame.recycle() } ?: return null
        }
        return LayerMedia.fitLongSide(argb, maxLongSide)
    }

    @Synchronized
    fun close() {
        closed = true
        runCatching { retriever?.release() }
        retriever = null
        still?.recycle()
        still = null
    }
}
