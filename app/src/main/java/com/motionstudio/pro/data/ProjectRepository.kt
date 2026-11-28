package com.motionstudio.pro.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProjectRepository(private val dao: ProjectDao) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun decode(s: String): Project? = try { json.decodeFromString<Project>(s) } catch (_: Exception) { null }

    fun observe(): Flow<List<ProjectSummary>> = dao.all().map { list ->
        list.map { e ->
            val p = decode(e.json)
            ProjectSummary(e.id, e.name, e.updatedAt, p?.clips?.firstOrNull(), p?.clips?.size ?: 0, p?.durationMs ?: 0)
        }
    }

    suspend fun get(id: String): Project? = dao.get(id)?.let { decode(it.json) }

    suspend fun save(p: Project) {
        val q = p.copy(updatedAt = System.currentTimeMillis())
        dao.upsert(ProjectEntity(q.id, q.name, json.encodeToString(q), q.updatedAt))
    }

    /** Dipakai untuk auto-save saat layar ditutup; tidak ikut dibatalkan bersama ViewModel. */
    fun saveAsync(p: Project) { scope.launch { save(p) } }

    suspend fun create(): String {
        val name = "Proyek " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())
        val p = Project(UUID.randomUUID().toString(), name)
        save(p); return p.id
    }

    suspend fun duplicate(id: String) {
        val p = get(id) ?: return
        save(p.copy(id = UUID.randomUUID().toString(), name = p.name + " (salinan)"))
    }

    suspend fun rename(id: String, name: String) { get(id)?.let { save(it.copy(name = name.ifBlank { it.name })) } }
    suspend fun delete(id: String) = dao.delete(id)
}
