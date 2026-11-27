package com.fatih.stopmotion.layers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframesTest {

    private val base = Pose(0.5f, 0.5f, 1f, 0f, 1f)
    private fun pose(x: Float, scale: Float = 1f, rot: Float = 0f, op: Float = 1f) = Pose(x, 0.5f, scale, rot, op)
    private fun key(t: Int, x: Float, ease: Int = Ease.LINEAR) = Keyframe(t, pose(x), ease)

    @Test
    fun sample_withoutKeysReturnsBase() {
        assertEquals(base, Keyframes.sample(emptyList(), 7, base))
    }

    @Test
    fun sample_holdsFirstAndLastOutsideRange() {
        val keys = listOf(key(10, 0.2f), key(20, 0.8f))
        assertEquals(0.2f, Keyframes.sample(keys, 0, base).x, 1e-6f)
        assertEquals(0.2f, Keyframes.sample(keys, 10, base).x, 1e-6f)
        assertEquals(0.8f, Keyframes.sample(keys, 20, base).x, 1e-6f)
        assertEquals(0.8f, Keyframes.sample(keys, 99, base).x, 1e-6f)
    }

    @Test
    fun sample_linearInterpolatesAllProperties() {
        val keys = listOf(
            Keyframe(0, Pose(0f, 0f, 0.5f, 0f, 0f)),
            Keyframe(10, Pose(1f, 1f, 1.5f, 90f, 1f))
        )
        val p = Keyframes.sample(keys, 5, base)
        assertEquals(0.5f, p.x, 1e-5f)
        assertEquals(0.5f, p.y, 1e-5f)
        assertEquals(1.0f, p.scale, 1e-5f)
        assertEquals(45f, p.rotation, 1e-4f)
        assertEquals(0.5f, p.opacity, 1e-5f)
    }

    @Test
    fun sample_smoothIsSlowAtEnds() {
        val keys = listOf(key(0, 0f, Ease.SMOOTH), key(10, 1f))
        assertTrue(Keyframes.sample(keys, 1, base).x < 0.1f)   // linear akan 0.1
        assertEquals(0.5f, Keyframes.sample(keys, 5, base).x, 1e-5f)
        assertTrue(Keyframes.sample(keys, 9, base).x > 0.9f)
    }

    @Test
    fun sample_holdStaysThenJumpsAtNextKey() {
        val keys = listOf(key(0, 0.1f, Ease.HOLD), key(10, 0.9f))
        assertEquals(0.1f, Keyframes.sample(keys, 9, base).x, 1e-6f)
        assertEquals(0.9f, Keyframes.sample(keys, 10, base).x, 1e-6f)
    }

    @Test
    fun sample_usesEaseOfSegmentStart_acrossThreeKeys() {
        val keys = listOf(key(0, 0f), key(10, 1f, Ease.HOLD), key(20, 0f))
        assertEquals(0.5f, Keyframes.sample(keys, 5, base).x, 1e-5f)
        assertEquals(1f, Keyframes.sample(keys, 15, base).x, 1e-5f)
        assertEquals(0f, Keyframes.sample(keys, 20, base).x, 1e-5f)
    }

    @Test
    fun upsert_replacesSameTickAndKeepsOrder() {
        var keys = emptyList<Keyframe>()
        keys = Keyframes.upsert(keys, key(20, 0.8f))
        keys = Keyframes.upsert(keys, key(5, 0.1f))
        keys = Keyframes.upsert(keys, key(20, 0.9f))
        assertEquals(listOf(5, 20), keys.map { it.tick })
        assertEquals(0.9f, keys[1].pose.x, 1e-6f)
    }

    @Test
    fun remove_andNeighbours() {
        val keys = listOf(key(0, 0f), key(10, 1f), key(20, 0f))
        assertEquals(listOf(0, 20), Keyframes.remove(keys, 10).map { it.tick })
        assertEquals(10, Keyframes.next(keys, 0)?.tick)
        assertEquals(10, Keyframes.previous(keys, 20)?.tick)
        assertNull(Keyframes.next(keys, 20))
        assertNull(Keyframes.previous(keys, 0))
        assertEquals(10, Keyframes.at(keys, 10)?.tick)
        assertNull(Keyframes.at(keys, 11))
    }

    @Test
    fun normalize_clampsValuesAndCapsCount() {
        val wild = Keyframe(-5, Pose(99f, Float.NaN, 99f, 5000f, 7f), 9)
        val c = Keyframes.normalize(listOf(wild)).single()
        assertEquals(0, c.tick)
        assertEquals(1.5f, c.pose.x, 0f)
        assertEquals(0.5f, c.pose.y, 0f)
        assertEquals(LayerLimits.MAX_SCALE, c.pose.scale, 0f)
        assertEquals(LayerLimits.MAX_KEY_ROTATION, c.pose.rotation, 0f)
        assertEquals(1f, c.pose.opacity, 0f)
        assertEquals(Ease.HOLD, c.ease)
        val many = (0 until Keyframes.MAX_KEYS + 30).map { key(it, 0.5f) }
        assertEquals(Keyframes.MAX_KEYS, Keyframes.normalize(many).size)
    }

    @Test
    fun encodeDecode_roundTrip() {
        val keys = listOf(
            Keyframe(0, Pose(0.1f, 0.2f, 0.5f, -30f, 1f), Ease.SMOOTH),
            Keyframe(12, Pose(0.9f, 0.8f, 2f, 400f, 0.25f), Ease.HOLD)
        )
        val text = Keyframes.encode(keys)!!
        assertEquals(keys, Keyframes.decode(text))
        assertEquals(text, Keyframes.encode(Keyframes.decode(text)))   // idempoten
    }

    @Test
    fun encode_emptyIsNull_andDecodeNullOrEmptyIsEmpty() {
        assertNull(Keyframes.encode(emptyList()))
        assertTrue(Keyframes.decode(null).isEmpty())
        assertTrue(Keyframes.decode("").isEmpty())
    }

    @Test
    fun decode_ignoresGarbageWithoutThrowing() {
        val text = "abc;1:2:3;5:0.5:0.5:1:0:1:0;x:0.5:0.5:1:0:1:0;7:NaN:0.5:1:0:1:0;9:0.5:0.5:1:0:1:zzz;;"
        val keys = Keyframes.decode(text)
        // hanya "5:..." dan "9:..." (ease rusak -> lurus) yang valid
        assertEquals(listOf(5, 9), keys.map { it.tick })
        assertEquals(Ease.LINEAR, keys[1].ease)
        assertTrue(Keyframes.decode("x".repeat(30_000)).isEmpty())
    }

    @Test
    fun decode_sortsAndDedupes() {
        val keys = Keyframes.decode("9:0.9:0.5:1:0:1:0;3:0.3:0.5:1:0:1:0;9:0.1:0.5:1:0:1:0")
        assertEquals(listOf(3, 9), keys.map { it.tick })
    }

    @Test
    fun poseSameAs_usesTolerance() {
        assertTrue(pose(0.5f).sameAs(pose(0.50001f)))
        assertTrue(!pose(0.5f).sameAs(pose(0.51f)))
    }
}
