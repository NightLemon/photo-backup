package io.github.nightlemon.photobackup.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRecordTest {
    private val media = LocalMedia(
        mediaKey = "external:42",
        contentUri = "content://media/external/file/42",
        displayName = "IMG_0042.jpg",
        relativePath = "DCIM/Camera/",
        mimeType = "image/jpeg",
        byteLength = 1234,
        modifiedAt = 1700000000000,
        capturedAt = 1699990000000,
    )

    private val record = BackupRecord(
        mediaKey = media.mediaKey,
        contentUri = media.contentUri,
        displayName = media.displayName,
        relativePath = media.relativePath,
        mimeType = media.mimeType,
        byteLength = media.byteLength,
        modifiedAt = media.modifiedAt,
        capturedAt = media.capturedAt,
        serverId = "server-1",
        assetId = "asset-1",
        sha256 = "abc",
        completedAt = 1700000010000,
    )

    @Test fun exactReceiptIsEligible() {
        assertTrue(record.matches(media, "server-1"))
    }

    @Test fun changedSourceIsNotEligible() {
        assertFalse(record.matches(media.copy(byteLength = media.byteLength + 1), "server-1"))
        assertFalse(record.matches(media.copy(modifiedAt = media.modifiedAt + 1000), "server-1"))
    }

    @Test fun receiptFromAnotherServerOrCleanedMediaIsNotEligible() {
        assertFalse(record.matches(media, "server-2"))
        assertFalse(record.copy(cleanedAt = 1).matches(media, "server-1"))
    }
}
