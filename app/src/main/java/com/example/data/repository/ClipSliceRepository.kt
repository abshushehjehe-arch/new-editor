package com.example.data.repository

import com.example.data.local.ClipProjectDao
import com.example.data.local.ClipProjectEntity
import com.example.data.local.ExportedClipDao
import com.example.data.local.ExportedClipEntity
import kotlinx.coroutines.flow.Flow

class ClipSliceRepository(
    private val projectDao: ClipProjectDao,
    private val exportedClipDao: ExportedClipDao
) {
    val projects: Flow<List<ClipProjectEntity>> = projectDao.observeProjects()
    val exportedClips: Flow<List<ExportedClipEntity>> = exportedClipDao.observeExportedClips()

    suspend fun saveProject(project: ClipProjectEntity): Long =
        projectDao.upsertProject(project)

    suspend fun deleteProject(id: Long) =
        projectDao.deleteProjectById(id)

    suspend fun recordExportedClip(clip: ExportedClipEntity): Long =
        exportedClipDao.insertExportedClip(clip)

    suspend fun deleteExportedClip(id: Long) =
        exportedClipDao.deleteExportedClipById(id)
}
