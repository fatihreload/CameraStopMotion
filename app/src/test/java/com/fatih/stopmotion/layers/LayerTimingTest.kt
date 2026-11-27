package com.fatih.stopmotion.layers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerTimingTest {

    private fun video(start: Int = 10, length: Int = 24, duration: Long = 1000, trim: Long = 0, loop: Boolean = false) =
        ClipTiming(start, length, true, duration, trim, loop)

    private val image = ClipTiming(5, 10, false, 0, 0, false)

    @Test
    fun isActive_coversStartUpToButNotIncludingEnd() {
        val t = video(start = 10, length = 5)
        assertFalse(LayerTiming.isActive(t, 9))
        assertTrue(LayerTiming.isActive(t, 10))
        assertTrue(LayerTiming.isActive(t, 14))
        assertFalse(LayerTiming.isActive(t, 15))
    }

    @Test
    fun image_isAlwaysTimeZeroWhileActive() {
        assertNull(LayerTiming.sourceTimeMs(image, 4, 12))
        assertEquals(0L, LayerTiming.sourceTimeMs(image, 5, 12))
        assertEquals(0L, LayerTiming.sourceTimeMs(image, 14, 12))
        assertNull(LayerTiming.sourceTimeMs(image, 15, 12))
    }

    @Test
    fun video_advancesInRealTimeFromStart() {
        val t = video(start = 10, length = 100, duration = 5000)
        assertEquals(0L, LayerTiming.sourceTimeMs(t, 10, 10))
        assertEquals(100L, LayerTiming.sourceTimeMs(t, 11, 10))
        assertEquals(1000L, LayerTiming.sourceTimeMs(t, 20, 10))
        // fps 12: tick ke-6 = 500 ms
        assertEquals(500L, LayerTiming.sourceTimeMs(video(start = 0, length = 100, duration = 5000), 6, 12))
    }

    @Test
    fun video_trimStartShiftsSourceTime() {
        val t = video(start = 0, length = 100, duration = 5000, trim = 2000)
        assertEquals(2000L, LayerTiming.sourceTimeMs(t, 0, 10))
        assertEquals(2300L, LayerTiming.sourceTimeMs(t, 3, 10))
    }

    @Test
    fun video_nonLoopDisappearsAfterClipEnds() {
        // 1 detik klip pada 10 fps: tick 0..9 tampil, tick 10 sudah habis walau panjang layer lebih lama.
        val t = video(start = 0, length = 50, duration = 1000)
        assertEquals(900L, LayerTiming.sourceTimeMs(t, 9, 10))
        assertNull(LayerTiming.sourceTimeMs(t, 10, 10))
        assertNull(LayerTiming.sourceTimeMs(t, 40, 10))
    }

    @Test
    fun video_trimShortensPlayableSpan() {
        val t = video(start = 0, length = 50, duration = 1000, trim = 500)
        assertEquals(900L, LayerTiming.sourceTimeMs(t, 4, 10))
        assertNull(LayerTiming.sourceTimeMs(t, 5, 10))
    }

    @Test
    fun video_loopRepeatsFromTrimStart() {
        val t = video(start = 0, length = 50, duration = 1000, trim = 200, loop = true)
        // span 800 ms: tick 8 (800 ms) kembali ke awal potongan
        assertEquals(1000L - 100L, LayerTiming.sourceTimeMs(t, 7, 10))
        assertEquals(200L, LayerTiming.sourceTimeMs(t, 8, 10))
        assertEquals(300L, LayerTiming.sourceTimeMs(t, 9, 10))
    }

    @Test
    fun video_loopStillEndsAtLayerLength() {
        val t = video(start = 2, length = 3, duration = 1000, loop = true)
        assertNull(LayerTiming.sourceTimeMs(t, 5, 10))
    }

    @Test
    fun videoLengthTicks_roundsUpAndStaysPositive() {
        assertEquals(10, LayerTiming.videoLengthTicks(1000, 0, 10))
        assertEquals(11, LayerTiming.videoLengthTicks(1001, 0, 10))
        assertEquals(5, LayerTiming.videoLengthTicks(1000, 500, 10))
        assertEquals(1, LayerTiming.videoLengthTicks(1000, 5000, 10))
        assertEquals(1, LayerTiming.videoLengthTicks(0, 0, 12))
    }

    @Test
    fun signature_changesWhenAnyLayerTimeChanges() {
        val ids = longArrayOf(1, 2)
        val a = LayerTiming.signature(ids, longArrayOf(0, 100))
        assertEquals(a, LayerTiming.signature(ids, longArrayOf(0, 100)))
        assertNotEquals(a, LayerTiming.signature(ids, longArrayOf(0, 200)))
        assertNotEquals(a, LayerTiming.signature(ids, longArrayOf(-1, 100)))
        assertNotEquals(a, LayerTiming.signature(longArrayOf(1, 3), longArrayOf(0, 100)))
    }

    @Test
    fun signature_imageLayerStartingChangesIt() {
        val ids = longArrayOf(1)
        assertNotEquals(LayerTiming.signature(ids, longArrayOf(-1)), LayerTiming.signature(ids, longArrayOf(0)))
    }

    @Test
    fun limits_clampAndHandleNaN() {
        assertEquals(LayerLimits.MIN_SCALE, LayerLimits.scale(0f), 0f)
        assertEquals(LayerLimits.MAX_SCALE, LayerLimits.scale(99f), 0f)
        assertEquals(1f, LayerLimits.scale(Float.NaN), 0f)
        assertEquals(0f, LayerLimits.opacity(-3f), 0f)
        assertEquals(1f, LayerLimits.opacity(3f), 0f)
        assertEquals(1.5f, LayerLimits.position(7f), 0f)
        assertEquals(0.5f, LayerLimits.position(Float.NaN), 0f)
    }

    @Test
    fun rotation_isNormalizedToHalfTurnEitherSide() {
        assertEquals(0f, LayerLimits.rotation(360f), 0.001f)
        assertEquals(-170f, LayerLimits.rotation(190f), 0.001f)
        assertEquals(170f, LayerLimits.rotation(-190f), 0.001f)
        assertEquals(90f, LayerLimits.rotation(450f), 0.001f)
        assertEquals(0f, LayerLimits.rotation(Float.NaN), 0f)
    }

    @Test
    fun kindOf_prefersMimeThenExtension() {
        assertEquals(LayerKind.VIDEO, LayerFiles.kindOf("video/mp4", "x.png"))
        assertEquals(LayerKind.IMAGE, LayerFiles.kindOf("image/png", "x.mp4"))
        assertEquals(LayerKind.VIDEO, LayerFiles.kindOf("", "Peluru.MOV"))
        assertEquals(LayerKind.IMAGE, LayerFiles.kindOf(null, "ledakan.webp"))
        assertNull(LayerFiles.kindOf("application/pdf", "a.pdf"))
        assertNull(LayerFiles.kindOf(null, null))
    }

    @Test
    fun safeExtension_rejectsOddNames() {
        assertEquals("mp4", LayerFiles.safeExtension("a/b/clip.MP4", "bin"))
        assertEquals("bin", LayerFiles.safeExtension("clip", "bin"))
        assertEquals("bin", LayerFiles.safeExtension("clip.toolong1", "bin"))
        assertEquals("bin", LayerFiles.safeExtension("clip.a-b", "bin"))
    }
}
