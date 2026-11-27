package com.fatih.stopmotion.layers

import java.util.Locale
import kotlin.math.ceil

/** Jenis media layer. */
object LayerKind {
    const val IMAGE = 0
    const val VIDEO = 1
}

/** Batas nilai layer. Dipakai UI, repository, dan cadangan agar semuanya konsisten. */
object LayerLimits {
    const val MIN_SCALE = 0.05f
    const val MAX_SCALE = 3f
    const val MAX_TICKS = 100_000
    const val MAX_LAYERS = 50

    fun scale(v: Float): Float = if (v.isNaN()) 1f else v.coerceIn(MIN_SCALE, MAX_SCALE)
    fun opacity(v: Float): Float = if (v.isNaN()) 1f else v.coerceIn(0f, 1f)
    fun position(v: Float): Float = if (v.isNaN()) 0.5f else v.coerceIn(-0.5f, 1.5f)

    /** Putaran di dalam keyframe boleh lebih dari satu putaran penuh (mis. peluru berputar): -720..720 derajat. */
    const val MAX_KEY_ROTATION = 720f
    fun keyRotation(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(-MAX_KEY_ROTATION, MAX_KEY_ROTATION)

    /** Putaran dinormalkan ke -180..180 derajat. */
    fun rotation(v: Float): Float {
        if (v.isNaN()) return 0f
        var r = v % 360f
        if (r > 180f) r -= 360f
        if (r < -180f) r += 360f
        return r
    }
}

/** Data waktu satu layer (tanpa Android/Room) supaya perhitungannya mudah diuji. */
data class ClipTiming(
    val startTick: Int,
    val lengthTicks: Int,
    val isVideo: Boolean,
    val mediaDurationMs: Long,
    val trimStartMs: Long,
    val loop: Boolean
)

object LayerTiming {

    /** Layer tampil pada tick ini (dalam rentang mulai sampai mulai + panjang). */
    fun isActive(t: ClipTiming, tick: Int): Boolean =
        tick >= t.startTick && tick.toLong() < t.startTick.toLong() + t.lengthTicks

    /**
     * Waktu (ms) di dalam file media yang harus ditampilkan pada [tick], atau null bila layer tidak tampil.
     * Gambar selalu 0. Video non-loop menghilang setelah klip habis (efek seperti peluru tidak membeku di
     * frame terakhir); video loop mengulang dari titik potong awal.
     */
    fun sourceTimeMs(t: ClipTiming, tick: Int, fps: Int): Long? {
        if (!isActive(t, tick)) return null
        if (!t.isVideo) return 0L
        val elapsed = (tick - t.startTick) * 1000L / fps.coerceAtLeast(1)
        val span = (t.mediaDurationMs - t.trimStartMs).coerceAtLeast(1L)
        val within = if (t.loop) {
            elapsed % span
        } else {
            if (elapsed >= span) return null
            elapsed
        }
        return t.trimStartMs + within
    }

    /** Panjang bawaan video (dalam tick) dari durasi klip setelah dipotong awal. */
    fun videoLengthTicks(durationMs: Long, trimStartMs: Long, fps: Int): Int {
        val ms = (durationMs - trimStartMs).coerceAtLeast(1L)
        val ticks = ceil(ms * fps.coerceAtLeast(1) / 1000.0).toInt()
        return ticks.coerceIn(1, LayerLimits.MAX_TICKS)
    }

    /**
     * Tanda tangan keadaan semua layer pada satu tick. Dua tick dengan tanda tangan sama menggambar layer yang
     * persis sama, sehingga ekspor boleh memakai ulang hasil gambar. times[i] < 0 = layer i tidak tampil.
     */
    fun signature(ids: LongArray, times: LongArray): Long {
        var h = 1125899906842597L
        for (i in ids.indices) {
            h = h * 31 + ids[i]
            h = h * 31 + times.getOrElse(i) { -1L }
        }
        return h
    }
}

/** Fungsi murni untuk mengenali file media yang dipilih. */
object LayerFiles {
    private val videoExt = setOf("mp4", "m4v", "mov", "3gp", "3gpp", "mkv", "webm", "avi", "ts")
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

    /** [LayerKind.VIDEO], [LayerKind.IMAGE], atau null bila bukan video/gambar. Mime diutamakan, lalu ekstensi. */
    fun kindOf(mime: String?, displayName: String?): Int? {
        val m = mime.orEmpty().lowercase(Locale.US)
        if (m.startsWith("video/")) return LayerKind.VIDEO
        if (m.startsWith("image/")) return LayerKind.IMAGE
        val ext = displayName.orEmpty().substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            in videoExt -> LayerKind.VIDEO
            in imageExt -> LayerKind.IMAGE
            else -> null
        }
    }

    /** Ekstensi file yang aman (huruf/angka, 1..5 karakter) atau [fallback]. */
    fun safeExtension(name: String?, fallback: String): String {
        val ext = name.orEmpty().substringAfterLast('.', "").lowercase(Locale.US)
        return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) ext else fallback
    }
}

/**
 * Ukuran layer di kanvas. Dipakai pratinjau dan ekspor agar hasilnya sama persis:
 * lebar layer = scale x lebar kanvas, tinggi mengikuti rasio media.
 */
object LayerGeometry {
    fun width(scale: Float, canvasWidth: Float): Float = scale * canvasWidth

    fun height(layerWidth: Float, aspect: Float): Float = layerWidth / aspect.coerceAtLeast(0.01f)

    /**
     * Sisi terpanjang bitmap yang cukup tajam untuk layer ini pada kanvas selebar [canvasWidth] piksel
     * (tidak perlu lebih besar dari ukuran tampilnya). Dibatasi 16..[maxSide].
     */
    fun neededLongSide(scale: Float, aspect: Float, canvasWidth: Int, maxSide: Int = 2160): Int {
        val w = width(scale, canvasWidth.toFloat())
        val h = height(w, aspect)
        return kotlin.math.max(w, h).let { if (it.isNaN()) 16 else it.toInt() }.coerceIn(16, maxSide)
    }
}
