package io.github.nightlemon.photobackup.sync

import android.content.Context
import io.github.nightlemon.photobackup.data.BackupRecord

enum class BackupSortOrder {
    TYPE_PRIORITY,
    OLDEST_FIRST,
    NEWEST_FIRST,
    SMALLEST_FIRST,
    LARGEST_FIRST,
}

data class BackupSettings(
    val includeScreenshots: Boolean = true,
    val includeOtherImages: Boolean = true,
    val includeVideos: Boolean = true,
    val sortOrder: BackupSortOrder = BackupSortOrder.TYPE_PRIORITY,
    val parallelUploads: Int = BackupSettingsStore.DEFAULT_PARALLEL_UPLOADS,
    val cleanupRetentionDays: Int = 30,
)

class BackupSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("backup-settings", Context.MODE_PRIVATE)

    fun read(): BackupSettings = BackupSettings(
        includeScreenshots = preferences.getBoolean("includeScreenshots", true),
        includeOtherImages = preferences.getBoolean("includeOtherImages", true),
        includeVideos = preferences.getBoolean("includeVideos", true),
        sortOrder = runCatching {
            BackupSortOrder.valueOf(preferences.getString("sortOrder", null).orEmpty())
        }.getOrDefault(BackupSortOrder.TYPE_PRIORITY),
        parallelUploads = normalizeParallelUploads(preferences.getInt("parallelUploads", DEFAULT_PARALLEL_UPLOADS)),
        cleanupRetentionDays = preferences.getInt("cleanupRetentionDays", DEFAULT_RETENTION_DAYS)
            .coerceIn(0, MAX_RETENTION_DAYS),
    )

    fun save(settings: BackupSettings) {
        preferences.edit()
            .putBoolean("includeScreenshots", settings.includeScreenshots)
            .putBoolean("includeOtherImages", settings.includeOtherImages)
            .putBoolean("includeVideos", settings.includeVideos)
            .putString("sortOrder", settings.sortOrder.name)
            .putInt("parallelUploads", normalizeParallelUploads(settings.parallelUploads))
            .putInt("cleanupRetentionDays", settings.cleanupRetentionDays.coerceIn(0, MAX_RETENTION_DAYS))
            .apply()
    }

    companion object {
        const val DEFAULT_RETENTION_DAYS = 30
        const val DEFAULT_PARALLEL_UPLOADS = 4
        const val MAX_RETENTION_DAYS = 36_500
        val PARALLEL_UPLOAD_OPTIONS = listOf(1, 2, 4, 6)
    }
}

internal fun normalizeParallelUploads(value: Int): Int =
    value.takeIf { it in BackupSettingsStore.PARALLEL_UPLOAD_OPTIONS } ?: BackupSettingsStore.DEFAULT_PARALLEL_UPLOADS

internal fun BackupRecord.isOutsideCleanupRetention(settings: BackupSettings, nowMillis: Long): Boolean {
    if (settings.cleanupRetentionDays == 0) return true
    val mediaTime = when {
        capturedAt > 0 -> capturedAt
        modifiedAt > 0 -> modifiedAt
        else -> return false
    }
    val retentionMillis = settings.cleanupRetentionDays.toLong() * MILLIS_PER_DAY
    return mediaTime <= nowMillis - retentionMillis
}

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
