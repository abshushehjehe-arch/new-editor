package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "clip_projects")
data class ClipProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val videoUriString: String,
    val videoDisplayName: String,
    val videoDurationMs: Long,
    val videoFileSizeBytes: Long,
    val promptText: String,
    val parsedClipCount: Int,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "exported_clips")
data class ExportedClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val outputUriString: String,
    val displayName: String,
    val clipTitle: String,
    val sourceVideoName: String,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val folderLabel: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ClipProjectDao {
    @Query("SELECT * FROM clip_projects ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<ClipProjectEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(project: ClipProjectEntity): Long

    @Query("DELETE FROM clip_projects WHERE id = :id")
    suspend fun deleteProjectById(id: Long)
}

@Dao
interface ExportedClipDao {
    @Query("SELECT * FROM exported_clips ORDER BY createdAt DESC")
    fun observeExportedClips(): Flow<List<ExportedClipEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExportedClip(clip: ExportedClipEntity): Long

    @Query("DELETE FROM exported_clips WHERE id = :id")
    suspend fun deleteExportedClipById(id: Long)

    @Query("DELETE FROM exported_clips")
    suspend fun clearAllExportedClips()
}

@Database(
    entities = [ClipProjectEntity::class, ExportedClipEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ClipSliceDatabase : RoomDatabase() {
    abstract fun projectDao(): ClipProjectDao
    abstract fun exportedClipDao(): ExportedClipDao

    companion object {
        @Volatile
        private var INSTANCE: ClipSliceDatabase? = null

        fun getInstance(context: Context): ClipSliceDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ClipSliceDatabase::class.java,
                    "clipslice_local.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
