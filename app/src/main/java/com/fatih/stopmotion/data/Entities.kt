package com.fatih.stopmotion.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val fps: Int = 12,
    val thumbnailPath: String? = null,
    @ColumnInfo(defaultValue = "1") val onionEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "0.5") val onionOpacity: Float = 0.5f,
    @ColumnInfo(defaultValue = "1") val onionFrameCount: Int = 1,
    /** 0 = warna asli, 1 = merah, 2 = biru, 3 = hijau */
    @ColumnInfo(defaultValue = "0") val onionColor: Int = 0,
    /** Tahap 4: efek tingkat proyek (filter, vignette, fade). */
    @ColumnInfo(defaultValue = "0") val fxFilter: Int = 0,
    @ColumnInfo(defaultValue = "1.0") val fxIntensity: Float = 1f,
    @ColumnInfo(defaultValue = "0.0") val fxVignette: Float = 0f,
    @ColumnInfo(defaultValue = "0") val fxFadeIn: Int = 0,
    @ColumnInfo(defaultValue = "0") val fxFadeOut: Int = 0,
    @ColumnInfo(defaultValue = "0") val fxFadeWhite: Boolean = false,
    /** Tahap 7: penghapus latar (0 = mati, 1 = green screen, 2 = blue screen). */
    @ColumnInfo(defaultValue = "0") val chromaMode: Int = 0,
    @ColumnInfo(defaultValue = "0.4") val chromaTolerance: Float = 0.4f,
    @ColumnInfo(defaultValue = "0.3") val chromaSoftness: Float = 0.3f,
    /** 0..3 warna polos, 4 = gambar (chromaBgPath). */
    @ColumnInfo(defaultValue = "0") val chromaBgMode: Int = 0,
    val chromaBgPath: String? = null
)

@Entity(
    tableName = "frames",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class FrameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val position: Int,
    val filePath: String,
    val thumbPath: String,
    /** Berapa kali frame ini ditahan (durasi frame). */
    val holdCount: Int = 1,
    val createdAt: Long,
    /** Hapus lunak: baris tetap ada agar undo/redo bisa memulihkannya. Dibersihkan saat aplikasi dibuka. */
    @ColumnInfo(defaultValue = "0") val isDeleted: Boolean = false,
    /** Coretan erase manual (lihat EraseCodec). null = tidak ada yang dihapus. */
    val eraseData: String? = null
)

object AudioKind {
    const val IMPORT = 0
    const val RECORD = 1
    const val SFX = 2
}

@Entity(
    tableName = "audio_tracks",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class AudioTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val name: String,
    val filePath: String,
    /** Lihat AudioKind. */
    val kind: Int,
    val durationMs: Long,
    /** Posisi mulai di timeline film. */
    val startMs: Long,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val volume: Float,
    val fadeInMs: Long,
    val fadeOutMs: Long,
    val muted: Boolean,
    val createdAt: Long
)

/**
 * Layer overlay di atas foto (video atau gambar), mis. klip efek peluru berlatar green screen.
 * Layer 1 adalah foto frame; layer ini menjadi layer 2, 3, dst. menurut zIndex (makin besar makin di atas).
 * Posisi dan ukuran dinormalisasi terhadap lebar/tinggi layar film, jadi sama di pratinjau dan ekspor.
 */
@Entity(
    tableName = "layers",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class LayerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val name: String,
    val filePath: String,
    /** Lihat LayerKind: 0 = gambar, 1 = video. */
    val kind: Int,
    val durationMs: Long,
    /** Lebar dibagi tinggi media. */
    val aspect: Float,
    val hasAudio: Boolean,
    val zIndex: Int,
    /** Mulai dan panjang di timeline film, dalam tick (1 tick = 1 / FPS detik). */
    val startTick: Int,
    val lengthTicks: Int,
    val trimStartMs: Long,
    val loop: Boolean,
    /** Pusat layer: 0..1 = di dalam layar film. */
    val centerX: Float,
    val centerY: Float,
    /** Lebar layer dibagi lebar layar film. */
    val scale: Float,
    val rotation: Float,
    val opacity: Float,
    val visible: Boolean,
    /** Chroma key khusus layer ini: 0 = mati, 1 = hijau, 2 = biru. */
    val chromaMode: Int,
    val chromaTolerance: Float,
    val chromaSoftness: Float,
    val createdAt: Long,
    /** Animasi keyframe (posisi/ukuran/putar/transparan) dalam format Keyframes.encode, atau null = tanpa animasi. */
    val keyframes: String? = null
)

data class ProjectSummary(
    @Embedded val project: ProjectEntity,
    val frameCount: Int
)
