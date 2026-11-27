package com.fatih.stopmotion.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControlsTest {

    private val caps = CameraCaps(
        minExposureNs = 100_000L, maxExposureNs = 1_000_000_000L,
        minIso = 50, maxIso = 3200,
        minFocusDiopter = 10f,
        awbModes = setOf(1, 2, 3, 5, 6, 7, 8), awbLockSupported = true,
        manualExposure = true,
        evMin = -12, evMax = 12, evStepStops = 1f / 6f,
        maxZoom = 8f
    )
    private val measured = Measured(focusDiopter = 2.5f, exposureNs = 8_333_333L, iso = 200)

    // ---------- zoom ----------
    @Test
    fun zoom_isClampedBetween1xAnd10xAndDeviceMax() {
        assertEquals(1f, CameraMath.clampZoom(0.4f, caps), 0f)
        assertEquals(8f, CameraMath.clampZoom(10f, caps), 0f)           // kamera hanya 8x
        val big = caps.copy(maxZoom = 30f)
        assertEquals(10f, CameraMath.clampZoom(25f, big), 0f)           // batas 10x
        assertEquals(1f, CameraMath.clampZoom(Float.NaN, big), 0f)
        assertEquals(1f, CameraMath.zoomMax(CameraCaps()), 0f)          // kamera tanpa zoom
    }

    @Test
    fun formatZoom_oneDecimal() {
        assertEquals("1.0x", CameraMath.formatZoom(1f))
        assertEquals("10.0x", CameraMath.formatZoom(10f))
        assertEquals("2.5x", CameraMath.formatZoom(2.5f))
    }

    // ---------- shutter ----------
    @Test
    fun shutter_sliderEndsMatchDeviceRange_andIsCappedAt2s() {
        assertEquals(100_000L, CameraMath.shutterFromFraction(0f, caps))
        assertEquals(1_000_000_000L, CameraMath.shutterFromFraction(1f, caps))
        val long = caps.copy(maxExposureNs = 30_000_000_000L)
        assertEquals(CameraMath.SLOWEST_SHUTTER_NS, CameraMath.shutterFromFraction(1f, long))
    }

    @Test
    fun shutter_roundTripThroughSlider() {
        for (ns in listOf(100_000L, 1_000_000L, 8_333_333L, 100_000_000L, 1_000_000_000L)) {
            val back = CameraMath.shutterFromFraction(CameraMath.fractionFromShutter(ns, caps), caps)
            assertEquals(ns.toDouble(), back.toDouble(), ns * 0.001)
        }
    }

    @Test
    fun shutter_formatting() {
        assertEquals("1/125", CameraMath.formatShutter(8_000_000L))
        assertEquals("1/60", CameraMath.formatShutter(16_666_667L))
        assertEquals("1.0 dtk", CameraMath.formatShutter(1_000_000_000L))
        assertEquals("Auto", CameraMath.formatShutter(0L))
    }

    // ---------- ISO ----------
    @Test
    fun iso_sliderEndsAndRoundTrip() {
        assertEquals(50, CameraMath.isoFromFraction(0f, caps))
        assertEquals(3200, CameraMath.isoFromFraction(1f, caps))
        for (iso in listOf(50, 100, 400, 1600, 3200)) {
            assertEquals(iso, CameraMath.isoFromFraction(CameraMath.fractionFromIso(iso, caps), caps))
        }
        assertEquals(50, CameraMath.clampIso(1, caps))
        assertEquals(3200, CameraMath.clampIso(99999, caps))
    }

    // ---------- fokus ----------
    @Test
    fun focus_sliderMapsToDiopters() {
        assertEquals(0f, CameraMath.diopterFromFraction(0f, caps), 0f)
        assertEquals(10f, CameraMath.diopterFromFraction(1f, caps), 0f)
        assertEquals(0.25f, CameraMath.fractionFromDiopter(2.5f, caps), 1e-6f)
        assertEquals(0f, CameraMath.fractionFromDiopter(3f, CameraCaps()), 0f)   // fokus tetap
        assertEquals("∞", CameraMath.formatFocus(0f))
        assertEquals("40 cm", CameraMath.formatFocus(2.5f))
    }

    @Test
    fun evLabel() {
        assertEquals("+1.0 EV", CameraMath.formatEv(6, caps))
        assertEquals("-2.0 EV", CameraMath.formatEv(-12, caps))
        assertEquals("+0.0 EV", CameraMath.formatEv(0, caps))
    }

    // ---------- 4 mode fokus ----------
    @Test
    fun autoFocus_leavesFocusAndExposureToCamera() {
        val p = CameraAdjust().plan(caps, measured)
        assertFalse(p.afOff)
        assertNull(p.focusDiopter)
        assertFalse(p.aeLock)
        assertFalse(p.manualExposure)
    }

    @Test
    fun manualFocus_setsFocusDistance_keepsAutoExposure() {
        val a = CameraAdjust(focusMode = FocusMode.MANUAL, focusFraction = 0.5f)
        val p = a.plan(caps, measured)
        assertTrue(p.afOff)
        assertEquals(5f, p.focusDiopter!!, 1e-6f)
        assertFalse(p.manualExposure)
        assertFalse(p.aeLock)
    }

    @Test
    fun selectingManualFocus_startsFromCurrentFocus() {
        val a = CameraAdjust().withFocusMode(FocusMode.MANUAL, measured, caps)
        assertEquals(0.25f, a.focusFraction, 1e-6f)
    }

    @Test
    fun lockFocusAndExposure_freezesCurrentFocus_andLocksAe() {
        val a = CameraAdjust().withFocusMode(FocusMode.LOCK, measured, caps)
        assertEquals(2.5f, a.lockedDiopter, 0f)
        val p = a.plan(caps, Measured(focusDiopter = 7f, exposureNs = 1L, iso = 1)) // kamera bergerak, kunci tetap
        assertTrue(p.afOff)
        assertEquals(2.5f, p.focusDiopter!!, 1e-6f)
        assertTrue(p.aeLock)
        assertFalse(p.manualExposure)
    }

    @Test
    fun lockFocusWithManualExposure_seedsFromCamera_andUsesManualValues() {
        val a = CameraAdjust().withFocusMode(FocusMode.LOCK_MANUAL_EXPOSURE, measured, caps)
        assertEquals(8_333_333L, a.shutterNs)
        assertEquals(200, a.iso)
        assertTrue(a.seededByMode)
        val p = a.plan(caps, measured)
        assertTrue(p.afOff)
        assertEquals(2.5f, p.focusDiopter!!, 1e-6f)
        assertTrue(p.manualExposure)
        assertFalse(p.aeLock)
        assertEquals(8_333_333L, p.exposureNs)
        assertEquals(200, p.iso)
    }

    @Test
    fun leavingManualExposureMode_releasesSeededValues_butKeepsUserValues() {
        val seeded = CameraAdjust().withFocusMode(FocusMode.LOCK_MANUAL_EXPOSURE, measured, caps)
        val back = seeded.withFocusMode(FocusMode.AUTO, measured, caps)
        assertEquals(0L, back.shutterNs)
        assertEquals(0, back.iso)
        assertFalse(back.manualExposure)

        val edited = seeded.withShutter(4_000_000L)       // pengguna mengubah sendiri
        val kept = edited.withFocusMode(FocusMode.AUTO, measured, caps)
        assertEquals(4_000_000L, kept.shutterNs)
        assertTrue(kept.manualExposure)
    }

    @Test
    fun manualExposureWithoutLockedFocus_whenOnlyShutterIsManual_isoFollowsCamera() {
        val a = CameraAdjust().withShutter(2_000_000L)
        val p = a.plan(caps, measured)
        assertTrue(p.manualExposure)
        assertEquals(2_000_000L, p.exposureNs)
        assertEquals(200, p.iso)       // ISO ikut nilai terakhir kamera
        assertEquals(0, p.evIndex)     // kompensasi eksposur tidak berlaku saat manual
    }

    // ---------- kamera yang tidak mendukung ----------
    @Test
    fun fixedFocusCamera_ignoresFocusModes() {
        val fixed = caps.copy(minFocusDiopter = 0f)
        for (mode in listOf(FocusMode.MANUAL, FocusMode.LOCK, FocusMode.LOCK_MANUAL_EXPOSURE)) {
            val p = CameraAdjust(focusMode = mode, focusFraction = 1f, lockedDiopter = 3f).plan(fixed, measured)
            assertFalse(p.afOff)
            assertNull(p.focusDiopter)
        }
    }

    @Test
    fun cameraWithoutManualSensor_neverGoesManualExposure() {
        val noManual = caps.copy(manualExposure = false)
        val p = CameraAdjust(focusMode = FocusMode.LOCK_MANUAL_EXPOSURE, shutterNs = 1_000_000L, iso = 100)
            .plan(noManual, measured)
        assertFalse(p.manualExposure)
    }

    // ---------- white balance ----------
    @Test
    fun awb_hasAllEightOptionsInOrder() {
        assertEquals(
            listOf("Auto", "Terkunci", "Lampu pijar", "Neon", "Siang", "Mendung", "Senja", "Teduh"),
            Awb.options.map { it.label }
        )
    }

    @Test
    fun awb_lockedUsesAutoModeWithLock() {
        val p = CameraAdjust(awb = 1).plan(caps, measured)
        assertEquals(1, p.awbMode)
        assertTrue(p.awbLock)
        val q = CameraAdjust(awb = 4).plan(caps, measured)   // Siang
        assertEquals(5, q.awbMode)
        assertFalse(q.awbLock)
    }

    @Test
    fun awb_unsupportedFallsBackToAuto() {
        val limited = caps.copy(awbModes = setOf(1), awbLockSupported = false)
        for (i in 1..7) {
            val p = CameraAdjust(awb = i).plan(limited, measured)
            assertEquals("opsi $i", Awb.MODE_AUTO, p.awbMode)
            assertFalse(p.awbLock)
        }
    }

    // ---------- kompensasi eksposur & zoom di rencana ----------
    @Test
    fun evAndZoom_areClampedInPlan() {
        val p = CameraAdjust(ev = 99, zoom = 50f).plan(caps, measured)
        assertEquals(12, p.evIndex)
        assertEquals(8f, p.zoom, 0f)
        assertEquals(-12, CameraAdjust(ev = -99).plan(caps, measured).evIndex)
        assertEquals(0, CameraAdjust(ev = 5).plan(caps.copy(evMin = 0, evMax = 0), measured).evIndex)
    }

    @Test
    fun planIsStableForSameInput_soCameraIsNotReconfiguredNeedlessly() {
        val a = CameraAdjust(focusMode = FocusMode.LOCK)
        assertEquals(a.plan(caps, measured), a.plan(caps, measured))
    }
}
