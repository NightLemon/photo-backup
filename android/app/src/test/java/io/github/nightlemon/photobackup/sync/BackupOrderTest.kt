package io.github.nightlemon.photobackup.sync

import io.github.nightlemon.photobackup.data.LocalMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupOrderTest {
    @Test fun interruptedMediaComesBeforeScreenshotsAndPhotos() {
        val items = listOf(
            media("photo", "image/jpeg", capturedAt = 1),
            media("screenshot", "image/png", path = "Pictures/Screenshots/", capturedAt = 2),
            media("video", "video/mp4", capturedAt = 3),
        )

        assertEquals(
            listOf("video", "screenshot", "photo"),
            items.selectedAndOrderedForBackup(setOf("video"), BackupSettings()).map { it.mediaKey },
        )
    }

    @Test fun screenshotsThenPhotosThenVideosAreOldestFirst() {
        val items = listOf(
            media("video-old", "video/mp4", capturedAt = 1),
            media("photo-new", "image/jpeg", capturedAt = 40),
            media("shot-new", "image/png", name = "Screenshot_2026.png", capturedAt = 30),
            media("photo-old", "image/jpeg", capturedAt = 10),
            media("shot-old", "image/png", path = "DCIM/屏幕截图/", capturedAt = 20),
        )

        assertEquals(
            listOf("shot-old", "shot-new", "photo-old", "photo-new", "video-old"),
            items.selectedAndOrderedForBackup(emptySet(), BackupSettings()).map { it.mediaKey },
        )
    }

    @Test fun modifiedTimeIsUsedWhenCaptureTimeIsMissing() {
        val items = listOf(
            media("unknown", "image/jpeg"),
            media("new", "image/jpeg", modifiedAt = 20),
            media("old", "image/jpeg", modifiedAt = 10),
        )

        assertEquals(
            listOf("old", "new", "unknown"),
            items.selectedAndOrderedForBackup(emptySet(), BackupSettings()).map { it.mediaKey },
        )
    }

    @Test fun videoInScreenshotsFolderIsNotClassifiedAsScreenshot() {
        assertTrue(media("image", "image/png", path = "Pictures/Screen Captures/").isScreenshot())
        assertFalse(media("video", "video/mp4", path = "Pictures/Screenshots/").isScreenshot())
    }

    @Test fun selectedMediaTypesAreTheOnlyItemsReturned() {
        val items = listOf(
            media("shot", "image/png", path = "Pictures/Screenshots/"),
            media("photo", "image/jpeg"),
            media("video", "video/mp4"),
        )

        assertEquals(
            listOf("video"),
            items.selectedAndOrderedForBackup(
                interruptedMediaKeys = setOf("photo"),
                settings = BackupSettings(includeScreenshots = false, includeOtherImages = false, includeVideos = true),
            ).map { it.mediaKey },
        )
    }

    @Test fun globalTimeAndSizeOrdersIgnoreMediaTypePriority() {
        val items = listOf(
            media("shot", "image/png", path = "Screenshots/", capturedAt = 20, size = 300),
            media("photo", "image/jpeg", capturedAt = 30, size = 100),
            media("video", "video/mp4", capturedAt = 10, size = 200),
        )

        assertEquals(
            listOf("video", "shot", "photo"),
            items.selectedAndOrderedForBackup(emptySet(), BackupSettings(sortOrder = BackupSortOrder.OLDEST_FIRST)).map { it.mediaKey },
        )
        assertEquals(
            listOf("photo", "shot", "video"),
            items.selectedAndOrderedForBackup(emptySet(), BackupSettings(sortOrder = BackupSortOrder.NEWEST_FIRST)).map { it.mediaKey },
        )
        assertEquals(
            listOf("photo", "video", "shot"),
            items.selectedAndOrderedForBackup(emptySet(), BackupSettings(sortOrder = BackupSortOrder.SMALLEST_FIRST)).map { it.mediaKey },
        )
        assertEquals(
            listOf("shot", "video", "photo"),
            items.selectedAndOrderedForBackup(emptySet(), BackupSettings(sortOrder = BackupSortOrder.LARGEST_FIRST)).map { it.mediaKey },
        )
    }

    private fun media(
        key: String,
        mimeType: String,
        name: String = "$key.jpg",
        path: String = "DCIM/Camera/",
        modifiedAt: Long = 0,
        capturedAt: Long = 0,
        size: Long = 100,
    ) = LocalMedia(
        mediaKey = key,
        contentUri = "content://media/$key",
        displayName = name,
        relativePath = path,
        mimeType = mimeType,
        byteLength = size,
        modifiedAt = modifiedAt,
        capturedAt = capturedAt,
    )
}