package com.fatih.stopmotion.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.fatih.stopmotion.audio.SoundFx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

class AudioRepository(
    private val context: Context,
    db: AppDatabase,
    private val storage: ProjectStorage
) {
    private val dao = db.audioDao()

    fun observe(projectId: Long): Flow<List<AudioTrackEntity>> = dao.observe(projectId)

    suspend fun importFromUri(projectId: Long, uri: Uri): AudioTrackEntity = withContext(Dispatchers.IO) {
        if (!storage.hasEnoughSpace()) throw IOException("Penyimpanan hampir penuh")
        val display = displayName(uri) ?: "Audio"
        val ext = display.substringAfterLast('.', "").lowercase(Locale.US)
            .takeIf { it.length in 2..4 } ?: "audio"
        val dest = storage.newAudioFile(projectId, ext)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("File tidak dapat dibuka")
            input.use { src -> dest.outputStream().use { src.copyTo(it) } }
            insert(projectId, dest, display.substringBeforeLast('.'), AudioKind.IMPORT)
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }

    suspend fun addRecording(projectId: Long, file: File, name: String): AudioTrackEntity =
        withContext(Dispatchers.IO) {
            try {
                insert(projectId, file, name, AudioKind.RECORD)
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }

    suspend fun addSoundFx(projectId: Long, presetId: String, label: String): AudioTrackEntity =
        withContext(Dispatchers.IO) {
            val dest = storage.newAudioFile(projectId, "wav")
            try {
                SoundFx.writeWav(dest, presetId)
                insert(projectId, dest, "Efek: $label", AudioKind.SFX)
            } catch (e: Exception) {
                dest.delete()
                throw e
            }
        }

    /**
     * Menyalin suara dari klip video layer menjadi track audio baru. Mulai di [startMs] pada timeline film,
     * diputar dari [trimStartMs] di dalam klip selama [playMs] (null = sampai klip habis).
     * File disalin karena menghapus track audio juga menghapus filenya.
     */
    suspend fun addFromClip(
        projectId: Long,
        clip: File,
        name: String,
        startMs: Long,
        trimStartMs: Long,
        playMs: Long?
    ): AudioTrackEntity = withContext(Dispatchers.IO) {
        if (!clip.exists()) throw IOException("File klip tidak ditemukan")
        if (!storage.hasEnoughSpace()) throw IOException("Penyimpanan hampir penuh")
        val ext = clip.extension.lowercase(Locale.US).takeIf { it.length in 1..5 && it.all { c -> c.isLetterOrDigit() } } ?: "mp4"
        val dest = storage.newAudioFile(projectId, ext)
        try {
            clip.copyTo(dest, overwrite = false)
            val duration = readDuration(dest)
            if (duration <= 1) throw IOException("Klip ini tidak punya suara yang dapat dibaca")
            val tin = trimStartMs.coerceIn(0L, duration - 1)
            val tout = if (playMs != null) (tin + playMs).coerceIn(tin + 1, duration) else duration
            val track = AudioTrackEntity(
                projectId = projectId,
                name = name.ifBlank { "Suara klip" },
                filePath = dest.absolutePath,
                kind = AudioKind.IMPORT,
                durationMs = duration,
                startMs = startMs.coerceAtLeast(0L),
                trimStartMs = tin,
                trimEndMs = tout,
                volume = 1f,
                fadeInMs = 0,
                fadeOutMs = 0,
                muted = false,
                createdAt = System.currentTimeMillis()
            )
            track.copy(id = dao.insert(track))
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }

    suspend fun update(track: AudioTrackEntity) = withContext(Dispatchers.IO) { dao.update(track) }

    suspend fun delete(track: AudioTrackEntity) = withContext(Dispatchers.IO) {
        dao.delete(track.id)
        File(track.filePath).delete()
    }

    private suspend fun insert(projectId: Long, file: File, name: String, kind: Int): AudioTrackEntity {
        val duration = readDuration(file)
        if (duration <= 0) throw IOException("File audio tidak dapat dibaca")
        val track = AudioTrackEntity(
            projectId = projectId,
            name = name.ifBlank { "Audio" },
            filePath = file.absolutePath,
            kind = kind,
            durationMs = duration,
            startMs = 0,
            trimStartMs = 0,
            trimEndMs = duration,
            volume = 1f,
            fadeInMs = 0,
            fadeOutMs = 0,
            muted = false,
            createdAt = System.currentTimeMillis()
        )
        return track.copy(id = dao.insert(track))
    }

    private fun readDuration(file: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { r.release() }
        }
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
