package com.fatih.stopmotion.ui.camera

import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * Kontrol kamera manual (fokus, white balance, eksposur, zoom, shutter, ISO).
 * File ini murni Kotlin (tanpa Android) supaya aturannya bisa diuji; CameraPreview yang menerjemahkan
 * RequestPlan menjadi permintaan Camera2.
 */

/** Mode fokus/eksposur. Urutan = urutan tampil di panel. */
object FocusMode {
    /** Fokus otomatis terus-menerus, eksposur otomatis. */
    const val AUTO = 0
    /** Fokus diatur dengan slider, eksposur otomatis. */
    const val MANUAL = 1
    /** Fokus dan eksposur dikunci pada nilai saat mode ini dipilih. */
    const val LOCK = 2
    /** Fokus dikunci, eksposur manual (shutter dan ISO dari slider). */
    const val LOCK_MANUAL_EXPOSURE = 3

    val labels = listOf(
        "Auto focus",
        "Manual focus",
        "Kunci fokus + eksposur",
        "Kunci fokus + eksposur manual"
    )
}

/** Satu pilihan white balance. [camera2Mode] = nilai CaptureRequest.CONTROL_AWB_MODE_*. */
data class AwbOption(val label: String, val camera2Mode: Int, val lock: Boolean = false)

object Awb {
    // Nilai konstanta platform Android: AUTO=1, INCANDESCENT=2, FLUORESCENT=3, DAYLIGHT=5, CLOUDY_DAYLIGHT=6,
    // TWILIGHT=7, SHADE=8.
    const val MODE_AUTO = 1

    val options = listOf(
        AwbOption("Auto", 1),
        AwbOption("Terkunci", 1, lock = true),
        AwbOption("Lampu pijar", 2),
        AwbOption("Neon", 3),
        AwbOption("Siang", 5),
        AwbOption("Mendung", 6),
        AwbOption("Senja", 7),
        AwbOption("Teduh", 8)
    )
}

/** Kemampuan kamera yang sedang aktif. Nilai bawaan = "tidak ada yang didukung". */
data class CameraCaps(
    val minExposureNs: Long = 0L,
    val maxExposureNs: Long = 0L,
    val minIso: Int = 0,
    val maxIso: Int = 0,
    /** Dioptri terdekat yang bisa difokus; 0 = fokus tetap. */
    val minFocusDiopter: Float = 0f,
    val awbModes: Set<Int> = setOf(Awb.MODE_AUTO),
    val awbLockSupported: Boolean = false,
    val manualExposure: Boolean = false,
    val evMin: Int = 0,
    val evMax: Int = 0,
    val evStepStops: Float = 0f,
    val maxZoom: Float = 1f
) {
    val manualFocus: Boolean get() = minFocusDiopter > 0f
    val evSupported: Boolean get() = evMax > evMin && evStepStops > 0f

    fun supports(o: AwbOption): Boolean =
        if (o.lock) awbLockSupported else o.camera2Mode in awbModes
}

/** Nilai yang sedang dipakai kamera (dibaca dari hasil tiap frame). */
data class Measured(
    val focusDiopter: Float = -1f,
    val exposureNs: Long = 0L,
    val iso: Int = 0
)

/** Rencana permintaan Camera2 yang sudah dirapikan terhadap kemampuan kamera. */
data class RequestPlan(
    /** True: AF dimatikan dan jarak fokus = [focusDiopter]. */
    val afOff: Boolean,
    val focusDiopter: Float?,
    val aeLock: Boolean,
    /** True: AE dimatikan dan eksposur dari [exposureNs] + [iso]. */
    val manualExposure: Boolean,
    val exposureNs: Long,
    val iso: Int,
    val awbMode: Int,
    val awbLock: Boolean,
    val evIndex: Int,
    val zoom: Float
)

object CameraMath {
    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 10f
    /** Shutter terlama yang ditawarkan slider (2 detik) walau sensor mampu lebih lama. */
    const val SLOWEST_SHUTTER_NS = 2_000_000_000L
    const val DEFAULT_SHUTTER_NS = 16_666_667L // 1/60 dtk
    const val DEFAULT_ISO = 100

    /** Batas zoom yang berlaku: 1x sampai 10x, tidak melebihi kemampuan kamera. */
    fun zoomMax(caps: CameraCaps): Float = caps.maxZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    fun clampZoom(z: Float, caps: CameraCaps): Float =
        (if (z.isNaN()) MIN_ZOOM else z).coerceIn(MIN_ZOOM, zoomMax(caps))

    private fun shutterRange(caps: CameraCaps): Pair<Long, Long> {
        val lo = max(caps.minExposureNs, 1L)
        val hi = max(min(caps.maxExposureNs, SLOWEST_SHUTTER_NS), lo)
        return lo to hi
    }

    fun clampShutter(ns: Long, caps: CameraCaps): Long {
        val (lo, hi) = shutterRange(caps)
        return ns.coerceIn(lo, hi)
    }

    fun clampIso(iso: Int, caps: CameraCaps): Int {
        val lo = max(caps.minIso, 1)
        val hi = max(caps.maxIso, lo)
        return iso.coerceIn(lo, hi)
    }

    /** Slider 0..1 -> waktu shutter (skala logaritmik: tiap geseran = jumlah "stop" yang sama). */
    fun shutterFromFraction(f: Float, caps: CameraCaps): Long {
        val (lo, hi) = shutterRange(caps)
        if (hi == lo || f <= 0f) return lo
        if (f >= 1f) return hi // ujung slider harus tepat (exp(ln(x)) bisa meleset 1 ns)
        val t = f.toDouble()
        return exp(ln(lo.toDouble()) + (ln(hi.toDouble()) - ln(lo.toDouble())) * t).roundToLong().coerceIn(lo, hi)
    }

    fun fractionFromShutter(ns: Long, caps: CameraCaps): Float {
        val (lo, hi) = shutterRange(caps)
        if (hi == lo) return 0f
        val v = ns.coerceIn(lo, hi).toDouble()
        return ((ln(v) - ln(lo.toDouble())) / (ln(hi.toDouble()) - ln(lo.toDouble()))).toFloat().coerceIn(0f, 1f)
    }

    fun isoFromFraction(f: Float, caps: CameraCaps): Int {
        val lo = max(caps.minIso, 1)
        val hi = max(caps.maxIso, lo)
        if (hi == lo) return lo
        val t = f.coerceIn(0f, 1f).toDouble()
        return exp(ln(lo.toDouble()) + (ln(hi.toDouble()) - ln(lo.toDouble())) * t).roundToInt().coerceIn(lo, hi)
    }

    fun fractionFromIso(iso: Int, caps: CameraCaps): Float {
        val lo = max(caps.minIso, 1)
        val hi = max(caps.maxIso, lo)
        if (hi == lo) return 0f
        val v = iso.coerceIn(lo, hi).toDouble()
        return ((ln(v) - ln(lo.toDouble())) / (ln(hi.toDouble()) - ln(lo.toDouble()))).toFloat().coerceIn(0f, 1f)
    }

    /** Slider fokus 0..1: 0 = jauh (tak hingga), 1 = paling dekat. Hasil dalam dioptri. */
    fun diopterFromFraction(f: Float, caps: CameraCaps): Float = f.coerceIn(0f, 1f) * caps.minFocusDiopter

    fun fractionFromDiopter(d: Float, caps: CameraCaps): Float =
        if (caps.minFocusDiopter <= 0f || d.isNaN()) 0f else (d / caps.minFocusDiopter).coerceIn(0f, 1f)

    fun formatShutter(ns: Long): String {
        if (ns <= 0L) return "Auto"
        if (ns >= 500_000_000L) return String.format(Locale.US, "%.1f dtk", ns / 1e9)
        return "1/${(1e9 / ns).roundToInt().coerceAtLeast(1)}"
    }

    fun formatFocus(diopter: Float): String =
        if (diopter < 0.05f) "∞" else "${(100f / diopter).roundToInt()} cm"

    fun formatEv(index: Int, caps: CameraCaps): String =
        String.format(Locale.US, "%+.1f EV", index * caps.evStepStops)

    fun formatZoom(z: Float): String = String.format(Locale.US, "%.1fx", z)
}

/**
 * Semua pilihan pengguna di panel kamera. [shutterNs] 0 = shutter otomatis, [iso] 0 = ISO otomatis.
 * Bila salah satunya manual, eksposur menjadi manual (AE mati); bagian yang masih otomatis memakai nilai
 * terakhir yang dipilih kamera.
 */
data class CameraAdjust(
    val focusMode: Int = FocusMode.AUTO,
    /** Mode manual: 0 = jauh, 1 = dekat. */
    val focusFraction: Float = 0f,
    /** Dioptri fokus saat mode kunci dipilih; negatif = belum diketahui. */
    val lockedDiopter: Float = -1f,
    /** Indeks di [Awb.options]. */
    val awb: Int = 0,
    val ev: Int = 0,
    val zoom: Float = 1f,
    val shutterNs: Long = 0L,
    val iso: Int = 0,
    /** Shutter/ISO manual ini diisi otomatis oleh mode "kunci fokus + eksposur manual" (dilepas saat mode berganti). */
    val seededByMode: Boolean = false
) {
    val manualExposure: Boolean
        get() = focusMode == FocusMode.LOCK_MANUAL_EXPOSURE || shutterNs > 0L || iso > 0

    fun withFocusMode(mode: Int, m: Measured, caps: CameraCaps): CameraAdjust {
        val target = mode.coerceIn(FocusMode.AUTO, FocusMode.LOCK_MANUAL_EXPOSURE)
        // Keluar dari mode "eksposur manual" membuang nilai yang diisi mode itu sendiri.
        val released = if (seededByMode && target != FocusMode.LOCK_MANUAL_EXPOSURE) {
            copy(shutterNs = 0L, iso = 0, seededByMode = false)
        } else this
        return when (target) {
            FocusMode.AUTO -> released.copy(focusMode = target, lockedDiopter = -1f)
            FocusMode.MANUAL -> released.copy(
                focusMode = target,
                lockedDiopter = -1f,
                focusFraction = if (m.focusDiopter >= 0f) CameraMath.fractionFromDiopter(m.focusDiopter, caps)
                else released.focusFraction
            )
            FocusMode.LOCK -> released.copy(focusMode = target, lockedDiopter = m.focusDiopter)
            else -> {
                val seed = shutterNs == 0L && iso == 0
                released.copy(
                    focusMode = target,
                    lockedDiopter = m.focusDiopter,
                    shutterNs = if (shutterNs > 0L) shutterNs
                    else if (m.exposureNs > 0L) CameraMath.clampShutter(m.exposureNs, caps)
                    else CameraMath.clampShutter(CameraMath.DEFAULT_SHUTTER_NS, caps),
                    iso = if (iso > 0) iso
                    else if (m.iso > 0) CameraMath.clampIso(m.iso, caps)
                    else CameraMath.clampIso(CameraMath.DEFAULT_ISO, caps),
                    seededByMode = seed || seededByMode
                )
            }
        }
    }

    fun withShutter(ns: Long) = copy(shutterNs = ns.coerceAtLeast(0L), seededByMode = false)
    fun withIso(v: Int) = copy(iso = v.coerceAtLeast(0), seededByMode = false)

    /** Rencana permintaan kamera, dirapikan terhadap [caps]. [m] mengisi bagian eksposur yang masih otomatis. */
    fun plan(caps: CameraCaps, m: Measured): RequestPlan {
        val manual = manualExposure && caps.manualExposure
        val shutter = CameraMath.clampShutter(
            if (shutterNs > 0L) shutterNs else if (m.exposureNs > 0L) m.exposureNs else CameraMath.DEFAULT_SHUTTER_NS,
            caps
        )
        val isoValue = CameraMath.clampIso(
            if (iso > 0) iso else if (m.iso > 0) m.iso else CameraMath.DEFAULT_ISO,
            caps
        )
        val locked = focusMode == FocusMode.LOCK || focusMode == FocusMode.LOCK_MANUAL_EXPOSURE
        val diopter: Float? = when {
            !caps.manualFocus -> null // fokus tetap: tidak ada yang bisa diatur
            focusMode == FocusMode.MANUAL -> CameraMath.diopterFromFraction(focusFraction, caps)
            locked && lockedDiopter >= 0f -> lockedDiopter.coerceIn(0f, caps.minFocusDiopter)
            else -> null
        }
        val opt = Awb.options[awb.coerceIn(0, Awb.options.lastIndex)]
        val awbOk = caps.supports(opt)
        return RequestPlan(
            afOff = diopter != null,
            focusDiopter = diopter,
            aeLock = focusMode == FocusMode.LOCK && !manual,
            manualExposure = manual,
            exposureNs = if (manual) shutter else 0L,
            iso = if (manual) isoValue else 0,
            awbMode = if (awbOk) opt.camera2Mode else Awb.MODE_AUTO,
            awbLock = awbOk && opt.lock,
            evIndex = if (manual || !caps.evSupported) 0 else ev.coerceIn(caps.evMin, caps.evMax),
            zoom = CameraMath.clampZoom(zoom, caps)
        )
    }
}
