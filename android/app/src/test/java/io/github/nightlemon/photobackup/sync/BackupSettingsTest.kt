package io.github.nightlemon.photobackup.sync

import io.github.nightlemon.photobackup.data.BackupRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSettingsTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test fun recentMediaIsExcludedFromCleanup() {
        val settings = BackupSettings(cleanupRetentionDays = 30)

        assertFalse(record(capturedAt = now - 29 * day).isOutsideCleanupRetention(settings, now))
        assertTrue(record(capturedAt = now - 30 * day).isOutsideCleanupRetention(settings, now))
    }

    @Test fun modifiedTimeIsFallbackAndUnknownTimeIsRetained() {
        val settings = BackupSettings(cleanupRetentionDays = 30)

        assertTrue(record(modifiedAt = now - 31 * day).isOutsideCleanupRetention(settings, now))
        assertFalse(record().isOutsideCleanupRetention(settings, now))
    }

    @Test fun zeroRetentionAllowsEveryVerifiedBackup() {
        assertTrue(record().isOutsideCleanupRetention(BackupSettings(cleanupRetentionDays = 0), now))
    }

    @Test fun parallelUploadsOnlyAcceptsSupportedValues() {
        for (value in BackupSettingsStore.PARALLEL_UPLOAD_OPTIONS) assertTrue(normalizeParallelUploads(value) == value)
        assertTrue(normalizeParallelUploads(0) == BackupSettingsStore.DEFAULT_PARALLEL_UPLOADS)
        assertTrue(normalizeParallelUploads(3) == BackupSettingsStore.DEFAULT_PARALLEL_UPLOADS)
        assertTrue(normalizeParallelUploads(10) == BackupSettingsStore.DEFAULT_PARALLEL_UPLOADS)
    }

    private fun record(capturedAt: Long = 0, modifiedAt: Long = 0) = BackupRecord(
        mediaKey = "external:1",
        contentUri = "content://media/external/file/1",
        displayName = "photo.jpg",
        relativePath = "DCIM/Camera/",
        mimeType = "image/jpeg",
        byteLength = 1,
        modifiedAt = modifiedAt,
        capturedAt = capturedAt,
        serverId = "server",
        assetId = "asset",
        sha256 = "hash",
        completedAt = now,
    )
}