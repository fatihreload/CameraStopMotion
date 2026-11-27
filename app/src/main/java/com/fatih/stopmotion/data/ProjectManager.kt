package com.fatih.stopmotion.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Operasi tingkat proyek: info, duplikat, cadangan (ZIP) dan pemulihan, serta pembersihan penyimpanan. */
class ProjectManager(
    private val db: AppDatabase,
    private val storage: ProjectStorage
) {
    private val projectDao = db.projectDao()
    private val frameDao = db.frameDao()
    private val audioDao = db.audioDao()
    private val layerDao = db.layerDao()

    data class Info(
        val project: ProjectEntity,
        val frameCount: Int,
        val ticks: Int,
        val audioCount: Int,
        val bytes: Long,
        val layerCount: Int = 0
    )

    data class StorageStats(val projectsBytes: Long, val freeBytes: Long)

    suspend fun info(id: Long): Info = withContext(Dispatchers.IO) {
        val p = projectDao.get(id) ?: throw IOException("Proyek tidak ditemukan")
        val frames = frameDao.getFrames(id)
        Info(
            project = p,
            frameCount = frames.size,
            ticks = frames.sumOf { it.holdCount.coerceAtLeast(1) },
            audioCount = audioDao.getAll(id).size,
            bytes = storage.projectBytes(id),
            layerCount = layerDao.getAll(id).size
        )
    }

    suspend fun storageStats(): StorageStats = withContext(Dispatchers.IO) {
        StorageStats(storage.totalBytes(), storage.usableBytes())
    }

    suspend fun clearExports(): Long = withContext(Dispatchers.IO) { storage.clearExports() }

    /** Menyalin seluruh proyek (foto, thumbnail, audio, pengaturan) menjadi proyek baru. */
    suspend fun duplicate(id: Long, onProgress: (Float) -> Unit = {}): Long = withContext(Dispatchers.IO) {
        val src = projectDao.get(id) ?: throw IOException("Proyek tidak ditemukan")
        val needed = storage.projectBytes(id) + 50L * 1024 * 1024
        if (storage.usableBytes() < needed) throw IOException("Penyimpanan tidak cukup untuk menduplikat proyek")

        val frames = frameDao.getFrames(id)
        val tracks = audioDao.getAll(id)
        val layers = layerDao.getAll(id)
        val now = System.currentTimeMillis()
        val newName = ProjectNames.copyName(src.name, projectDao.allNames())
        val newId = projectDao.insert(
            src.copy(id = 0, name = newName, createdAt = now, updatedAt = now, thumbnailPath = null, chromaBgPath = null)
        )
        try {
            storage.projectDir(newId)
            val total = (frames.size + tracks.size + layers.size).coerceAtLeast(1)
            var done = 0
            var lastThumb: String? = null
            for (f in frames) {
                ensureActive()
                val photo = storage.newFrameFile(newId)
                File(f.filePath).copyTo(photo, overwrite = false)
                val thumb = File(storage.thumbsDir(newId), photo.name)
                File(f.thumbPath).copyTo(thumb, overwrite = false)
                frameDao.insert(
                    f.copy(id = 0, projectId = newId, filePath = photo.absolutePath, thumbPath = thumb.absolutePath)
                )
                lastThumb = thumb.absolutePath
                onProgress(++done / total.toFloat())
            }
            for (t in tracks) {
                ensureActive()
                val ext = File(t.filePath).extension.ifEmpty { "bin" }
                val dest = storage.newAudioFile(newId, ext)
                File(t.filePath).copyTo(dest, overwrite = false)
                audioDao.insert(t.copy(id = 0, projectId = newId, filePath = dest.absolutePath))
                onProgress(++done / total.toFloat())
            }
            for (l in layers) {
                ensureActive()
                val ext = File(l.filePath).extension.ifEmpty { "bin" }
                val dest = storage.newLayerFile(newId, ext)
                File(l.filePath).copyTo(dest, overwrite = false)
                layerDao.insert(l.copy(id = 0, projectId = newId, filePath = dest.absolutePath))
                onProgress(++done / total.toFloat())
            }
            src.chromaBgPath?.let { File(it) }?.takeIf { it.exists() }?.let { bg ->
                val dest = storage.newBgFile(newId, bg.extension.ifEmpty { "jpg" })
                bg.copyTo(dest, overwrite = false)
                projectDao.updateChromaBgPath(newId, dest.absolutePath)
            }
            projectDao.touch(newId, now, lastThumb)
            newId
        } catch (e: Throwable) {
            projectDao.delete(newId)
            storage.deleteProject(newId)
            throw e
        }
    }

    /** Menulis proyek ke satu file ZIP: manifest.json lebih dulu, lalu foto dan audio. */
    suspend fun backup(id: Long, out: OutputStream, onProgress: (Float) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            val p = projectDao.get(id) ?: throw IOException("Proyek tidak ditemukan")
            val frames = frameDao.getFrames(id)
            val tracks = audioDao.getAll(id)
            val layers = layerDao.getAll(id)
            val total = (frames.size + tracks.size + layers.size).coerceAtLeast(1)
            var done = 0
            val bgFile = p.chromaBgPath?.let { File(it) }?.takeIf { it.exists() }
            val bgEntry = bgFile?.let { BackupManifest.backgroundEntry(it.path) }
            ZipOutputStream(BufferedOutputStream(out, 1 shl 16)).use { zip ->
                zip.putNextEntry(ZipEntry(BackupManifest.ENTRY))
                zip.write(BackupManifest.toJson(p, frames, tracks, bgEntry, layers).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                if (bgFile != null && bgEntry != null) {
                    zip.putNextEntry(ZipEntry(bgEntry))
                    bgFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                frames.forEachIndexed { i, f ->
                    ensureActive()
                    zip.putNextEntry(ZipEntry(BackupManifest.frameEntry(i)))
                    File(f.filePath).inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    onProgress(++done / total.toFloat())
                }
                tracks.forEachIndexed { i, t ->
                    ensureActive()
                    zip.putNextEntry(ZipEntry(BackupManifest.audioEntry(i, t.filePath)))
                    File(t.filePath).inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    onProgress(++done / total.toFloat())
                }
                layers.sortedBy { it.zIndex }.forEachIndexed { i, l ->
                    ensureActive()
                    zip.putNextEntry(ZipEntry(BackupManifest.layerEntry(i, l.filePath)))
                    File(l.filePath).inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    onProgress(++done / total.toFloat())
                }
            }
        }

    /** Membaca file cadangan menjadi proyek baru. Mengembalikan id proyek. Bila gagal, tidak ada yang tersisa. */
    suspend fun restore(input: InputStream, onProgress: (Float) -> Unit = {}): Long =
        withContext(Dispatchers.IO) {
            if (!storage.hasEnoughSpace(100L * 1024 * 1024)) throw IOException("Penyimpanan hampir penuh")
            val zip = ZipInputStream(BufferedInputStream(input, 1 shl 16))
            val first = zip.nextEntry
            if (first == null || first.name != BackupManifest.ENTRY) {
                throw IOException("Bukan file cadangan Fatih Stop Motion")
            }
            val manifestBytes = readLimited(zip, 8 * 1024 * 1024)
            val parsed = BackupManifest.parse(String(manifestBytes, Charsets.UTF_8))

            val now = System.currentTimeMillis()
            val name = ProjectNames.uniqueName(parsed.project.name, projectDao.allNames())
            val newId = projectDao.insert(parsed.project.copy(name = name, createdAt = now, updatedAt = now))
            try {
                storage.projectDir(newId)
                val frameIndex = HashMap<String, Int>()
                parsed.frames.forEachIndexed { i, m -> frameIndex[m.file] = i }
                val audioByFile = parsed.audio.associateBy { it.file }
                val layerByFile = parsed.layers.associateBy { it.file }
                val frameDone = BooleanArray(parsed.frames.size)
                val total = (parsed.frames.size + parsed.audio.size + parsed.layers.size).coerceAtLeast(1)
                var done = 0

                while (true) {
                    ensureActive()
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val fi = frameIndex[e.name]
                    val am = audioByFile[e.name]
                    val lm = layerByFile[e.name]
                    if (parsed.backgroundFile != null && e.name == parsed.backgroundFile) {
                        val ext = BackupManifest.backgroundEntry(e.name).substringAfter('.')
                        val dest = storage.newBgFile(newId, ext)
                        dest.outputStream().use { zip.copyTo(it) }
                        projectDao.updateChromaBgPath(newId, dest.absolutePath)
                    } else if (fi != null && !frameDone[fi]) {
                        val meta = parsed.frames[fi]
                        val photo = storage.newFrameFile(newId)
                        photo.outputStream().use { zip.copyTo(it) }
                        val thumb = File(storage.thumbsDir(newId), photo.name)
                        if (!storage.createThumbnail(photo, thumb)) throw IOException("Foto frame ${fi + 1} rusak")
                        frameDao.insert(
                            FrameEntity(
                                projectId = newId,
                                position = fi,
                                filePath = photo.absolutePath,
                                thumbPath = thumb.absolutePath,
                                holdCount = meta.hold,
                                createdAt = if (meta.createdAt > 0) meta.createdAt else now,
                                eraseData = meta.erase
                            )
                        )
                        frameDone[fi] = true
                        onProgress(++done / total.toFloat())
                    } else if (am != null) {
                        val ext = File(am.file).extension.ifEmpty { "bin" }
                        val dest = storage.newAudioFile(newId, ext)
                        dest.outputStream().use { zip.copyTo(it) }
                        audioDao.insert(
                            AudioTrackEntity(
                                projectId = newId, name = am.name, filePath = dest.absolutePath, kind = am.kind,
                                durationMs = am.durationMs, startMs = am.startMs, trimStartMs = am.trimStartMs,
                                trimEndMs = am.trimEndMs, volume = am.volume, fadeInMs = am.fadeInMs,
                                fadeOutMs = am.fadeOutMs, muted = am.muted,
                                createdAt = if (am.createdAt > 0) am.createdAt else now
                            )
                        )
                        onProgress(++done / total.toFloat())
                    } else if (lm != null) {
                        val ext = File(lm.file).extension.ifEmpty { "bin" }
                        val dest = storage.newLayerFile(newId, ext)
                        dest.outputStream().use { zip.copyTo(it) }
                        layerDao.insert(
                            lm.layer.copy(
                                projectId = newId,
                                filePath = dest.absolutePath,
                                createdAt = if (lm.layer.createdAt > 0) lm.layer.createdAt else now
                            )
                        )
                        onProgress(++done / total.toFloat())
                    }
                }
                if (frameDone.any { !it }) throw IOException("File cadangan tidak lengkap")
                db.withTransaction {
                    projectDao.touch(newId, now, frameDao.getFrames(newId).lastOrNull()?.thumbPath)
                }
                newId
            } catch (e: Throwable) {
                projectDao.delete(newId)
                storage.deleteProject(newId)
                throw e
            }
        }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IOException("File cadangan tidak valid")
        }
        return out.toByteArray()
    }
}
