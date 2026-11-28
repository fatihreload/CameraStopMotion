package com.motionstudio.pro.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable enum class ClipType { VIDEO, IMAGE }

@Serializable
data class Clip(
    val id: String,
    val uri: String,
    val type: ClipType,
    val name: String,
    val sourceDurationMs: Long,
    val inMs: Long,
    val outMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
) {
    val durationMs: Long get() = outMs - inMs
}

@Serializable
data class Project(
    val id: String,
    val name: String,
    val clips: List<Clip> = emptyList(),
    val updatedAt: Long = 0L,
) {
    val durationMs: Long get() = clips.sumOf { it.durationMs }
}

data class ProjectSummary(
    val id: String, val name: String, val updatedAt: Long,
    val thumb: Clip?, val clipCount: Int, val durationMs: Long,
)

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val json: String,
    val updatedAt: Long,
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC") fun all(): Flow<List<ProjectEntity>>
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun get(id: String): ProjectEntity?
    @Upsert suspend fun upsert(e: ProjectEntity)
    @Query("DELETE FROM projects WHERE id = :id") suspend fun delete(id: String)
}

@Database(entities = [ProjectEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() { abstract fun projects(): ProjectDao }
