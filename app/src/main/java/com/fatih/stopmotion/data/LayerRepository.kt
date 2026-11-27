package com.fatih.stopmotion.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.fatih.stopmotion.fx.ChromaMode
import com.fatih.stopmotion.layers.LayerFiles
import com.fatih.stopmotion.layers.LayerKind
import com.fatih.stopmotion.layers.LayerLimits
import com.fatih.stopmotion.layers.LayerMedia
import com.fatih.stopmotion.layers.LayerTiming
import com.fatih.stopmotion.layers.sanitized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Layer overlay (video atau gambar) di atas foto. File media disalin ke folder proyek (layers/)
 * karena izin akses file dari pemilih file tidak bertahan lama.
 */
class LayerRepository(
    private val context: Context,
    db: AppDatabase,
    private val storage: ProjectStorage
) {
    private val dao = db.layerDao()

    fun observe(projectId: Long): Flow<List<LayerEntity>> = dao.observe(projectId)

    suspend fun getAll(projectId: Long): List<LayerEntity> = withContext(Dispatchers.IO) { dao.getAll(projectId) }

    /**
     * Menambah layer dari file pilihan pengguna. Layer baru berada paling atas, mulai di [startTick].
     * Video: panjang mengikuti durasi klip. Gambar: sampai akhir film.
     */
    suspend fun addFromUri(projectId: Long, uri: Uri, startTick: Int, fps: Int, totalTicks: Int): LayerEntity =
        withContext(Dispatchers.IO) {
            if (!storage.hasEnoughSpace()) throw IOException("Penyimpanan hampir penuh")
            if (dao.getAll(projectId).size >= LayerLimits.MAX_LAYERS) {
                throw IOException("Maksimum ${LayerLimits.MAX_LAYERS} layer per proyek")
            }
            val display = displayName(uri)
            val mime = context.contentResolver.getType(uri)
            val kind = LayerFiles.kindOf(mime, display) ?: throw IOException("Pilih file video atau gambar")
            val isVideo = kind == LayerKind.VIDEO
            val fallbackExt = if (isVideo) "mp4" else "jpg"
            val dest = storage.newLayerFile(projectId, LayerFiles.safeExtension(display, fallbackExt))
            try {
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("File tidak dapat dibuka")
                input.use { src -> dest.outputStream().use { src.copyTo(it) } }
                val probe = LayerMedia.probe(dest, isVideo)
                    ?: throw IOException(if (isVideo) "Video tidak dapat dibaca" else "Gambar tidak dapat dibaca")

                val start = startTick.coerceIn(0, LayerLimits.MAX_TICKS)
                val length = if (isVideo) {
                    LayerTiming.videoLengthTicks(probe.durationMs, 0L, fps)
                } else {
                    (totalTicks - start).coerceAtLeast(1)
                }
                val top = (dao.getAll(projectId).maxOfOrNull { it.zIndex } ?: 0) + 1
                val layer = LayerEntity(
                    projectId = projectId,
                    name = (display ?: "Layer").substringBeforeLast('.').ifBlank { "Layer" },
                    filePath = dest.absolutePath,
                    kind = kind,
                    durationMs = probe.durationMs,
                    aspect = probe.aspect,
                    hasAudio = probe.hasAudio,
                    zIndex = top,
                    startTick = start,
                    lengthTicks = length,
                    trimStartMs = 0L,
                    loop = false,
                    centerX = 0.5f,
                    centerY = 0.5f,
                    scale = if (isVideo) 1f else 0.5f,
                    rotation = 0f,
                    opacity = 1f,
                    visible = true,
                    chromaMode = ChromaMode.OFF,
                    chromaTolerance = 0.4f,
                    chromaSoftness = 0.3f,
                    createdAt = System.currentTimeMillis()
                ).sanitized()
                layer.copy(id = dao.insert(layer))
            } catch (e: Exception) {
                dest.delete()
                throw e
            }
        }

    suspend fun update(layer: LayerEntity) = withContext(Dispatchers.IO) { dao.update(layer.sanitized()) }

    suspend fun delete(layer: LayerEntity) = withContext(Dispatchers.IO) {
        dao.delete(layer.id)
        File(layer.filePath).delete()
    }

    /** Menyalin layer (termasuk file media) tepat di atas layer asal. */
    suspend fun duplicate(layer: LayerEntity): LayerEntity = withContext(Dispatchers.IO) {
        if (!storage.hasEnoughSpace()) throw IOException("Penyimpanan hampir penuh")
        val src = File(layer.filePath)
        if (!src.exists()) throw IOException("File layer tidak ditemukan")
        val dest = storage.newLayerFile(layer.projectId, LayerFiles.safeExtension(src.name, "bin"))
        try {
            src.copyTo(dest, overwrite = false)
            val all = dao.getAll(layer.projectId)
            val copy = layer.copy(
                id = 0,
                name = (layer.name.take(31) + " (salinan)").take(40),
                filePath = dest.absolutePath,
                zIndex = (all.maxOfOrNull { it.zIndex } ?: 0) + 1,
                createdAt = System.currentTimeMillis()
            ).sanitized()
            copy.copy(id = dao.insert(copy))
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }

    /** Menaikkan atau menurunkan layer satu tingkat di tumpukan, lalu merapikan nomor urut. */
    suspend fun moveZ(projectId: Long, layerId: Long, up: Boolean) = withContext(Dispatchers.IO) {
        val list = dao.getAll(projectId).toMutableList()
        val i = list.indexOfFirst { it.id == layerId }
        val j = if (up) i + 1 else i - 1
        if (i < 0 || j < 0 || j >= list.size) return@withContext
        val t = list[i]; list[i] = list[j]; list[j] = t
        val changed = list.mapIndexedNotNull { idx, l -> if (l.zIndex != idx) l.copy(zIndex = idx) else null }
        if (changed.isNotEmpty()) dao.updateAll(changed)
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
