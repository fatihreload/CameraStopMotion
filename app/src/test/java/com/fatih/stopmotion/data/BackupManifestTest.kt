package com.fatih.stopmotion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BackupManifestTest {

    private val project = ProjectEntity(
        id = 7, name = "Film Saya", createdAt = 1, updatedAt = 2, fps = 24,
        onionEnabled = false, onionOpacity = 0.25f, onionFrameCount = 3, onionColor = 2,
        fxFilter = 2, fxIntensity = 0.5f, fxVignette = 0.3f, fxFadeIn = 6, fxFadeOut = 8, fxFadeWhite = true
    )

    private val frames = listOf(
        FrameEntity(id = 1, projectId = 7, position = 0, filePath = "/x/a.jpg", thumbPath = "/x/t.jpg", holdCount = 3, createdAt = 111),
        FrameEntity(id = 2, projectId = 7, position = 1, filePath = "/x/b.jpg", thumbPath = "/x/u.jpg", holdCount = 1, createdAt = 222)
    )

    private val tracks = listOf(
        AudioTrackEntity(
            id = 1, projectId = 7, name = "Musik", filePath = "/x/a.MP3", kind = AudioKind.IMPORT,
            durationMs = 10_000, startMs = 500, trimStartMs = 1000, trimEndMs = 9000,
            volume = 0.8f, fadeInMs = 200, fadeOutMs = 300, muted = true, createdAt = 5
        )
    )

    @Test
    fun roundTrip_keepsProjectSettings() {
        val parsed = BackupManifest.parse(BackupManifest.toJson(project, frames, tracks))
        val p = parsed.project
        assertEquals("Film Saya", p.name)
        assertEquals(24, p.fps)
        assertFalse(p.onionEnabled)
        assertEquals(0.25f, p.onionOpacity, 0f)
        assertEquals(3, p.onionFrameCount)
        assertEquals(2, p.onionColor)
        assertEquals(2, p.fxFilter)
        assertEquals(0.5f, p.fxIntensity, 0f)
        assertEquals(0.3f, p.fxVignette, 0f)
        assertEquals(6, p.fxFadeIn)
        assertEquals(8, p.fxFadeOut)
        assertTrue(p.fxFadeWhite)
    }

    @Test
    fun roundTrip_keepsFramesInOrder() {
        val parsed = BackupManifest.parse(BackupManifest.toJson(project, frames, tracks))
        assertEquals(2, parsed.frames.size)
        assertEquals("frames/00001.jpg", parsed.frames[0].file)
        assertEquals(3, parsed.frames[0].hold)
        assertEquals(111L, parsed.frames[0].createdAt)
        assertEquals("frames/00002.jpg", parsed.frames[1].file)
    }

    @Test
    fun roundTrip_keepsAudioSettings() {
        val parsed = BackupManifest.parse(BackupManifest.toJson(project, frames, tracks))
        assertEquals(1, parsed.audio.size)
        val a = parsed.audio[0]
        assertEquals("audio/001.mp3", a.file)
        assertEquals("Musik", a.name)
        assertEquals(AudioKind.IMPORT, a.kind)
        assertEquals(10_000L, a.durationMs)
        assertEquals(500L, a.startMs)
        assertEquals(1000L, a.trimStartMs)
        assertEquals(9000L, a.trimEndMs)
        assertEquals(0.8f, a.volume, 0f)
        assertEquals(200L, a.fadeInMs)
        assertEquals(300L, a.fadeOutMs)
        assertTrue(a.muted)
    }

    @Test
    fun roundTrip_keepsChromaSettingsAndBackgroundEntry() {
        val withChroma = project.copy(
            chromaMode = 2, chromaTolerance = 0.7f, chromaSoftness = 0.15f, chromaBgMode = 4, chromaBgPath = "/x/bg.png"
        )
        val entry = BackupManifest.backgroundEntry("/x/bg.png")
        val parsed = BackupManifest.parse(BackupManifest.toJson(withChroma, frames, tracks, entry))
        assertEquals("background.png", parsed.backgroundFile)
        assertEquals(2, parsed.project.chromaMode)
        assertEquals(0.7f, parsed.project.chromaTolerance, 0f)
        assertEquals(0.15f, parsed.project.chromaSoftness, 0f)
        assertEquals(4, parsed.project.chromaBgMode)
        // Path file asli tidak dibawa ke cadangan; dibuat ulang saat dipulihkan.
        assertNull(parsed.project.chromaBgPath)
    }

    @Test
    fun roundTrip_withoutBackgroundHasNoBackgroundFile() {
        val parsed = BackupManifest.parse(BackupManifest.toJson(project, frames, tracks))
        assertNull(parsed.backgroundFile)
        assertEquals(0, parsed.project.chromaMode)
    }

    @Test
    fun parse_clampsChromaValues() {
        val json = """{"format":"FatihStopMotion","version":1,"frames":[],
            "chromaMode":9,"chromaTolerance":5,"chromaSoftness":-2,"chromaBgMode":99}"""
        val p = BackupManifest.parse(json).project
        assertEquals(2, p.chromaMode)
        assertEquals(1f, p.chromaTolerance, 0f)
        assertEquals(0f, p.chromaSoftness, 0f)
        assertEquals(4, p.chromaBgMode)
    }

    @Test
    fun backgroundEntry_usesSafeExtension() {
        assertEquals("background.png", BackupManifest.backgroundEntry("/a/b.PNG"))
        assertEquals("background.jpg", BackupManifest.backgroundEntry("/a/noext"))
        assertEquals("background.jpg", BackupManifest.backgroundEntry("/a/x.toolongext"))
    }

    @Test
    fun parse_fillsDefaultsAndClampsValues() {
        val json = """{"format":"FatihStopMotion","version":1,"name":"  X  ","fps":500,
            "frames":[{"file":"frames/00001.jpg","hold":99}]}"""
        val parsed = BackupManifest.parse(json)
        assertEquals("X", parsed.project.name)
        assertEquals(60, parsed.project.fps)
        assertTrue(parsed.project.onionEnabled)
        assertEquals(0, parsed.project.fxFilter)
        assertEquals(10, parsed.frames[0].hold)
        assertEquals(0L, parsed.frames[0].createdAt)
        assertTrue(parsed.audio.isEmpty())
    }

    @Test
    fun parse_clampsBrokenAudioValues() {
        val json = """{"format":"FatihStopMotion","version":1,"frames":[],
            "audio":[{"file":"audio/001.mp3","durationMs":1000,"trimStartMs":5000,"trimEndMs":10,"volume":7}]}"""
        val a = BackupManifest.parse(json).audio[0]
        assertEquals("Audio", a.name)
        assertEquals(999L, a.trimStartMs)
        assertEquals(1000L, a.trimEndMs)
        assertEquals(1f, a.volume, 0f)
    }

    @Test(expected = IOException::class)
    fun parse_rejectsBrokenJson() {
        BackupManifest.parse("{not json")
    }

    @Test(expected = IOException::class)
    fun parse_rejectsOtherFormat() {
        BackupManifest.parse("""{"format":"Lain","version":1,"frames":[]}""")
    }

    @Test(expected = IOException::class)
    fun parse_rejectsNewerVersion() {
        BackupManifest.parse("""{"format":"FatihStopMotion","version":99,"frames":[]}""")
    }

    @Test
    fun entryNames_areBuiltFromIndexNotSourcePath() {
        assertEquals("frames/00001.jpg", BackupManifest.frameEntry(0))
        assertEquals("frames/00012.jpg", BackupManifest.frameEntry(11))
        assertEquals("audio/001.m4a", BackupManifest.audioEntry(0, "/a/b.M4A"))
        assertEquals("audio/003.bin", BackupManifest.audioEntry(2, "/a/noext"))
        assertEquals("audio/001.bin", BackupManifest.audioEntry(0, "/a/b.verylongext"))
    }

    @Test
    fun roundTrip_keepsEraseStrokes() {
        val erased = frames.mapIndexed { i, f -> if (i == 0) f.copy(eraseData = "e30:1000,2000,3000,4000;r50:5000,5000") else f }
        val parsed = BackupManifest.parse(BackupManifest.toJson(project, erased, tracks))
        assertEquals("e30:1000,2000,3000,4000;r50:5000,5000", parsed.frames[0].erase)
        assertNull(parsed.frames[1].erase)
    }

    @Test
    fun parse_dropsBrokenEraseData() {
        val json = """{"format":"FatihStopMotion","version":1,
            "frames":[{"file":"frames/00001.jpg","hold":1,"erase":"garbage"}]}"""
        assertNull(BackupManifest.parse(json).frames[0].erase)
    }

    // ---------- Layer overlay ----------

    private fun layer(id: Long, name: String, kind: Int, z: Int, file: String) = LayerEntity(
        id = id, projectId = 7, name = name, filePath = file, kind = kind, durationMs = if (kind == 1) 2500 else 0,
        aspect = 1.5f, hasAudio = kind == 1, zIndex = z, startTick = 4, lengthTicks = 30, trimStartMs = if (kind == 1) 300 else 0,
        loop = true, centerX = 0.25f, centerY = 0.75f, scale = 0.6f, rotation = -30f, opacity = 0.8f, visible = false,
        chromaMode = 1, chromaTolerance = 0.55f, chromaSoftness = 0.2f, createdAt = 99
    )

    @Test
    fun layers_roundTrip_keepsOrderAndSettings() {
        val layers = listOf(
            layer(2, "Asap", 0, z = 5, file = "/x/asap.PNG"),
            layer(1, "Peluru", 1, z = 1, file = "/x/peluru.mp4")
        )
        val json = BackupManifest.toJson(project, frames, tracks, layers = layers)
        val parsed = BackupManifest.parse(json)
        assertEquals(2, parsed.layers.size)
        // urutan mengikuti zIndex (terbawah dulu), nomor urut zIndex dibuat ulang 0..n-1
        assertEquals("layers/001.mp4", parsed.layers[0].file)
        assertEquals("layers/002.png", parsed.layers[1].file)
        assertEquals(listOf(0, 1), parsed.layers.map { it.layer.zIndex })
        val l = parsed.layers[0].layer
        assertEquals("Peluru", l.name)
        assertEquals(1, l.kind)
        assertEquals(2500L, l.durationMs)
        assertEquals(1.5f, l.aspect, 0f)
        assertTrue(l.hasAudio)
        assertEquals(4, l.startTick)
        assertEquals(30, l.lengthTicks)
        assertEquals(300L, l.trimStartMs)
        assertTrue(l.loop)
        assertEquals(0.25f, l.centerX, 0f)
        assertEquals(0.75f, l.centerY, 0f)
        assertEquals(0.6f, l.scale, 0f)
        assertEquals(-30f, l.rotation, 0f)
        assertEquals(0.8f, l.opacity, 0f)
        assertFalse(l.visible)
        assertEquals(1, l.chromaMode)
        assertEquals(0.55f, l.chromaTolerance, 0f)
        assertEquals(0.2f, l.chromaSoftness, 0f)
        assertEquals(99L, l.createdAt)
    }

    @Test
    fun layers_oldBackupWithoutLayersParsesAsEmpty() {
        val json = BackupManifest.toJson(project, frames, tracks)
        val stripped = org.json.JSONObject(json).also { it.remove("layers") }.toString()
        assertTrue(BackupManifest.parse(stripped).layers.isEmpty())
    }

    @Test
    fun layers_wildValuesAreClampedOnRestore() {
        val wild = layer(1, "x", 1, z = 0, file = "/x/a.mp4").copy(
            scale = 99f, opacity = 5f, startTick = -3, lengthTicks = 0, chromaMode = 9, trimStartMs = 999_999
        )
        val parsed = BackupManifest.parse(BackupManifest.toJson(project, frames, tracks, layers = listOf(wild)))
        val l = parsed.layers.single().layer
        assertEquals(com.fatih.stopmotion.layers.LayerLimits.MAX_SCALE, l.scale, 0f)
        assertEquals(1f, l.opacity, 0f)
        assertEquals(0, l.startTick)
        assertEquals(1, l.lengthTicks)
        assertEquals(2, l.chromaMode)
        assertEquals(2499L, l.trimStartMs)
    }

    @Test
    fun layers_tooManyIsRejected() {
        val many = (1..com.fatih.stopmotion.layers.LayerLimits.MAX_LAYERS + 1).map {
            layer(it.toLong(), "L$it", 0, z = it, file = "/x/$it.png")
        }
        val json = BackupManifest.toJson(project, frames, tracks, layers = many)
        try {
            BackupManifest.parse(json)
            org.junit.Assert.fail("harus ditolak")
        } catch (e: IOException) {
            // diharapkan
        }
    }

    @Test
    fun layerEntry_usesSafeExtension() {
        assertEquals("layers/001.mp4", BackupManifest.layerEntry(0, "/a/b.MP4"))
        assertEquals("layers/012.bin", BackupManifest.layerEntry(11, "/a/b.we!rd"))
        assertEquals("layers/003.bin", BackupManifest.layerEntry(2, "/a/noext"))
    }
}

