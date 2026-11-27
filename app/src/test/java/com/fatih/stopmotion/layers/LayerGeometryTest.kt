package com.fatih.stopmotion.layers

import org.junit.Assert.assertEquals
import org.junit.Test

class LayerGeometryTest {

    @Test
    fun width_isScaleTimesCanvasWidth() {
        assertEquals(500f, LayerGeometry.width(0.5f, 1000f), 0.001f)
        assertEquals(3000f, LayerGeometry.width(3f, 1000f), 0.001f)
    }

    @Test
    fun height_followsAspectRatio() {
        assertEquals(250f, LayerGeometry.height(500f, 2f), 0.001f)
        assertEquals(1000f, LayerGeometry.height(500f, 0.5f), 0.001f)
    }

    @Test
    fun height_survivesZeroAspect() {
        val h = LayerGeometry.height(100f, 0f)
        assertEquals(10_000f, h, 0.01f)
    }

    @Test
    fun neededLongSide_squareMediaIsLayerWidth() {
        assertEquals(360, LayerGeometry.neededLongSide(0.5f, 1f, 720))
    }

    @Test
    fun neededLongSide_wideMediaUsesWidth_tallMediaUsesHeight() {
        assertEquals(1000, LayerGeometry.neededLongSide(1f, 2f, 1000))
        assertEquals(2000, LayerGeometry.neededLongSide(1f, 0.5f, 1000))
    }

    @Test
    fun neededLongSide_isClamped() {
        assertEquals(2160, LayerGeometry.neededLongSide(3f, 1f, 2160))
        assertEquals(16, LayerGeometry.neededLongSide(0.05f, 1f, 100))
        assertEquals(1000, LayerGeometry.neededLongSide(3f, 1f, 2160, maxSide = 1000))
    }

    @Test
    fun neededLongSide_nanScaleFallsBackToMinimum() {
        assertEquals(16, LayerGeometry.neededLongSide(Float.NaN, 1f, 720))
    }
}
