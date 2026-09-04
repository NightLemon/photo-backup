package io.github.nightlemon.photobackup.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupDao {
    @Query("SELECT * FROM backups WHERE serverId = :serverId")
    suspend fun forServer(serverId: String): List<BackupRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(record: BackupRecord)

    @Query("SELECT * FROM backups WHERE cleanedAt IS NULL ORDER BY capturedAt DESC, completedAt DESC")
    fun observeCleanupCandidates(): Flow<List<BackupRecord>>

    @Query("SELECT * FROM backups WHERE cleanedAt IS NULL ORDER BY capturedAt DESC, completedAt DESC")
    suspend fun cleanupCandidates(): List<BackupRecord>

    @Query("UPDATE backups SET cleanedAt = :cleanedAt WHERE serverId = :serverId AND mediaKey IN (:mediaKeys) AND cleanedAt IS NULL")
    suspend fun markCleaned(serverId: String, mediaKeys: List<String>, cleanedAt: Long)
}

@Database(entities = [BackupRecord::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun backups(): BackupDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "photo-backup.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS backups_new (
                        mediaKey TEXT NOT NULL,
                        contentUri TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        relativePath TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        byteLength INTEGER NOT NULL,
                        modifiedAt INTEGER NOT NULL,
                        capturedAt INTEGER NOT NULL,
                        serverId TEXT NOT NULL,
                        assetId TEXT NOT NULL,
                        sha256 TEXT NOT NULL,
                        completedAt INTEGER NOT NULL,
                        cleanedAt INTEGER,
                        PRIMARY KEY(serverId, mediaKey)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO backups_new (
                        mediaKey, contentUri, displayName, relativePath, mimeType, byteLength,
                        modifiedAt, capturedAt, serverId, assetId, sha256, completedAt, cleanedAt
                    )
                    SELECT mediaKey, contentUri, displayName, relativePath, mimeType, byteLength,
                        modifiedAt, capturedAt, serverId, assetId, sha256, completedAt, cleanedAt
                    FROM backups
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE backups")
                db.execSQL("ALTER TABLE backups_new RENAME TO backups")
            }
        }
    }
}
