package io.github.nightlemon.photobackup.sync

import androidx.work.Data
import io.github.nightlemon.photobackup.data.LocalMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ManualSyncScopeTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val start = LocalDate.of(2026, 9, 1)
    private val end = LocalDate.of(2026, 9, 3)

    @Test fun filtersTypesAndIncludesBothDateBoundaries() {
        val scope = ManualSyncScope(
            includeScreenshots = true,
            includeOtherImages = false,
            includeVideos = true,
            startEpochDay = start.toEpochDay(),
            endEpochDay = end.toEpochDay(),
        )

        assertTrue(scope.accepts(media("shot", "image/png", "Pictures/Screenshots/", instant(start, LocalTime.MIN)), zone))
        assertTrue(scope.accepts(media("video", "video/mp4", "Movies/", instant(end, LocalTime.MAX)), zone))
        assertFalse(scope.accepts(media("photo", "image/jpeg", "DCIM/Camera/", instant(start.plusDays(1), LocalTime.NOON)), zone))
        assertFalse(scope.accepts(media("before", "video/mp4", "Movies/", instant(start.minusDays(1), LocalTime.NOON)), zone))
        assertFalse(scope.accepts(media("after", "video/mp4", "Movies/", instant(end.plusDays(1), LocalTime.NOON)), zone))
    }

    @Test fun fallsBackToModifiedTimeAndExcludesUnknownTimeWhenBounded() {
        val scope = ManualSyncScope(true, true, true, start.toEpochDay(), end.toEpochDay())

        assertTrue(scope.accepts(media("fallback", "image/jpeg", "DCIM/", capturedAt = 0, modifiedAt = instant(start, LocalTime.NOON)), zone))
        assertFalse(scope.accepts(media("unknown", "image/jpeg", "DCIM/", capturedAt = 0, modifiedAt = 0), zone))
    }

    @Test fun workDataRoundTripPreservesOneTimeScope() {
        val scope = ManualSyncScope(true, false, true, start.toEpochDay(), end.toEpochDay())

        assertEquals(scope, ManualSyncScope.fromWorkData(scope.toWorkData()))
        assertNull(ManualSyncScope.fromWorkData(Data.EMPTY))
    }

    @Test fun rejectsEmptyTypesAndReversedDates() {
        assertThrows(IllegalArgumentException::class.java) { ManualSyncScope(false, false, false) }
        assertThrows(IllegalArgumentException::class.java) { ManualSyncScope(true, false, false, end.toEpochDay(), start.toEpochDay()) }
    }

    @Test fun checkpointKeyIsStableAndRangeSpecific() {
        val first = ManualSyncScope(true, false, false, start.toEpochDay(), end.toEpochDay())
        val same = first.copy()
        val different = first.copy(endEpochDay = end.plusDays(1).toEpochDay())

        assertEquals(first.checkpointKey(), same.checkpointKey())
        assertNotEquals(first.checkpointKey(), different.checkpointKey())
        assertNotEquals(ManualSyncScope.AUTOMATIC_CHECKPOINT, first.checkpointKey())
    }

    private fun instant(date: LocalDate, time: LocalTime): Long = date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    private fun media(
        key: String,
        mimeType: String,
        path: String,
        capturedAt: Long,
        modifiedAt: Long = capturedAt,
    ) = LocalMedia(
        mediaKey = key,
        contentUri = "content://media/$key",
        displayName = "$key.jpg",
        relativePath = path,
        mimeType = mimeType,
        byteLength = 1,
        modifiedAt = modifiedAt,
        capturedAt = capturedAt,
    )
}