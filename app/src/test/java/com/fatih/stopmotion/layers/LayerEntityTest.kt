package com.fatih.stopmotion.layers

import com.fatih.stopmotion.data.LayerEntity
import com.fatih.stopmotion.fx.ChromaMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerEntityTest {

    private fun layer(
        kind: Int = LayerKind.VIDEO,
        duration: Long = 3000,
        trim: Long = 0,
        start: Int = 0,
        length: Int = 10
    ) = LayerEntity(
        id = 1, projectId = 1, name = "Peluru", filePath = "/x/l.mp4", kind = kind, durationMs = duration,
        aspect = 1.5f, hasAudio = true, zIndex = 0, startTick = start, lengthTicks = length, trimStartMs = trim,
        loop = false, centerX = 0.5f, centerY = 0.5f, scale = 1f, rotation = 0f, opacity = 1f, visible = true,
        chromaMode = ChromaMode.OFF, chromaTolerance = 0.4f, chromaSoftness = 0.3f, createdAt = 1
    )

    @Test
    fun sanitized_keepsValidLayerUnchanged() {
        val l = layer(trim = 500, start = 3, length = 12)
        assertEquals(l, l.sanitized())
    }

    @Test
    fun sanitized_isIdempotent_soDraftEqualsStoredValue() {
        val wild = layer().copy(
            name = "   ", scale = 99f, opacity = -1f, rotation = 725f, centerX = 9f, centerY = -9f,
            startTick = -5, lengthTicks = 0, trimStartMs = 99_999, chromaMode = 7,
            chromaTolerance = 5f, chromaSoftness = -1f, aspect = 0f
        )
        val once = wild.sanitized()
        assertEquals(once, once.sanitized())
    }

    @Test
    fun sanitized_clampsEverythingIntoRange() {
        val s = layer().copy(
            name = "   ", scale = 99f, opacity = -1f, rotation = 190f, centerX = 9f, centerY = -9f,
            startTick = -5, lengthTicks = 0, trimStartMs = 99_999, chromaMode = 7,
            chromaTolerance = 5f, chromaSoftness = -1f, aspect = 0f
        ).sanitized()
        assertEquals("Layer", s.name)
        assertEquals(LayerLimits.MAX_SCALE, s.scale, 0f)
        assertEquals(0f, s.opacity, 0f)
        assertEquals(-170f, s.rotation, 0.001f)
        assertEquals(1.5f, s.centerX, 0f)
        assertEquals(-0.5f, s.centerY, 0f)
        assertEquals(0, s.startTick)
        assertEquals(1, s.lengthTicks)
        assertEquals(2999L, s.trimStartMs)
        assertEquals(2, s.chromaMode)
        assertEquals(1f, s.chromaTolerance, 0f)
        assertEquals(0f, s.chromaSoftness, 0f)
        assertEquals(1f, s.aspect, 0f)
    }

    @Test
    fun sanitized_imageHasNoTrim() {
        assertEquals(0L, layer(kind = LayerKind.IMAGE, duration = 0, trim = 400).sanitized().trimStartMs)
    }

    @Test
    fun timing_reflectsLayerFields() {
        val t = layer(trim = 700, start = 4, length = 9).copy(loop = true).timing()
        assertEquals(4, t.startTick)
        assertEquals(9, t.lengthTicks)
        assertTrue(t.isVideo)
        assertEquals(3000L, t.mediaDurationMs)
        assertEquals(700L, t.trimStartMs)
        assertTrue(t.loop)
        assertFalse(layer(kind = LayerKind.IMAGE).timing().isVideo)
    }

    @Test
    fun chromaState_carriesKeySettingsButNoBackground() {
        val c = layer().copy(chromaMode = ChromaMode.GREEN, chromaTolerance = 0.7f, chromaSoftness = 0.2f).chromaState()
        assertTrue(c.active)
        assertEquals(0.7f, c.tolerance, 0f)
        assertEquals(0.2f, c.softness, 0f)
        assertEquals(null, c.bgPath)
    }

    // ---------- Keyframe ----------

    private fun animated(vararg keys: Keyframe, start: Int = 10, length: Int = 20) =
        layer(start = start, length = length).copy(keyframes = Keyframes.encode(keys.toList()))

    private fun k(t: Int, x: Float, ease: Int = Ease.LINEAR) = Keyframe(t, Pose(x, 0.5f, 1f, 0f, 1f), ease)

    @Test
    fun poseAt_withoutKeysIsStaticPose() {
        val l = layer()
        assertEquals(l.basePose(), l.poseAt(5))
        assertTrue(l.posedAt(5) === l)
    }

    @Test
    fun poseAt_usesTicksRelativeToLayerStart() {
        val l = animated(k(0, 0f), k(10, 1f), start = 10)
        assertEquals(0f, l.poseAt(10).x, 1e-5f)
        assertEquals(0.5f, l.poseAt(15).x, 1e-5f)
        assertEquals(1f, l.poseAt(20).x, 1e-5f)
        assertEquals(0f, l.poseAt(0).x, 1e-5f)   // sebelum layer mulai: nilai key pertama
    }

    @Test
    fun sanitized_normalizesKeyframes_andKeepsThemStable() {
        val raw = layer().copy(keyframes = "9:0.9:0.5:1:0:1:0;3:0.3:0.5:1:0:1:0;junk")
        val once = raw.sanitized()
        assertEquals(listOf(3, 9), once.keys.map { it.tick })
        assertEquals(once, once.sanitized())
        assertEquals(null, layer().copy(keyframes = "").sanitized().keyframes)
    }

    @Test
    fun withEdit_staticLayerAcceptsEditAsIs() {
        val l = layer()
        val edited = l.copy(centerX = 0.2f)
        assertEquals(edited, l.withEdit(edited, 3))
    }

    @Test
    fun withEdit_animatedLayerCreatesKeyAtCurrentTick() {
        val l = animated(k(0, 0f), k(10, 1f), start = 10)
        // tick film 15 = tick layer 5, pose saat ini x = 0.5; pengguna menggeser ke 0.7
        val edited = l.posedAt(15).copy(centerX = 0.7f)
        val result = l.withEdit(edited, 15)
        assertEquals(l.basePose(), result.basePose())            // pose statis tidak berubah
        assertEquals(listOf(0, 5, 10), result.keys.map { it.tick })
        assertEquals(0.7f, result.keys[1].pose.x, 1e-6f)
        assertEquals(0.7f, result.poseAt(15).x, 1e-5f)
    }

    @Test
    fun withEdit_animatedLayerUpdatesExistingKey() {
        val l = animated(k(0, 0f), k(10, 1f), start = 10)
        val result = l.withEdit(l.posedAt(20).copy(centerX = 0.6f), 20)
        assertEquals(listOf(0, 10), result.keys.map { it.tick })
        assertEquals(0.6f, result.keys[1].pose.x, 1e-6f)
    }

    @Test
    fun withEdit_nonPoseEditDoesNotCreateKeys() {
        val l = animated(k(0, 0f), k(10, 1f), start = 10)
        val edited = l.posedAt(15).copy(startTick = 12, chromaMode = ChromaMode.GREEN)
        val result = l.withEdit(edited, 15)
        assertEquals(l.keys, result.keys)
        assertEquals(12, result.startTick)
        assertEquals(ChromaMode.GREEN, result.chromaMode)
        assertEquals(l.basePose(), result.basePose())
    }

    @Test
    fun withEdit_outsideLayerRangeIgnoresPoseEdit() {
        val l = animated(k(0, 0f), k(10, 1f), start = 10, length = 20)
        val result = l.withEdit(l.posedAt(2).copy(centerX = 0.9f), 2)   // sebelum layer mulai
        assertEquals(l.keys, result.keys)
    }

    @Test
    fun withEdit_rotationOverOneTurnIsNotTreatedAsEdit() {
        val l = animated(
            Keyframe(0, Pose(0.5f, 0.5f, 1f, 0f, 1f)),
            Keyframe(10, Pose(0.5f, 0.5f, 1f, 540f, 1f)),
            start = 0
        )
        // di tick 10 pose.rotation = 540; sanitized() menormalkannya jadi 180, itu bukan perubahan pengguna
        val edited = l.posedAt(10).sanitized()
        val result = l.withEdit(edited, 10)
        assertEquals(l.keys, result.keys)
    }

    @Test
    fun withEdit_keyframeChangesAreAcceptedDirectly() {
        val l = animated(k(0, 0f), k(10, 1f))
        val edited = l.copy(keyframes = null)
        assertEquals(null, l.withEdit(edited, 5).keyframes)
    }
}
