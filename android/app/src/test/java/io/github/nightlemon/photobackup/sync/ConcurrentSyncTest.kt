package io.github.nightlemon.photobackup.sync

import io.github.nightlemon.photobackup.data.LocalMedia
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ConcurrentSyncTest {
    @Test fun boundedWorkersNeverExceedParallelism() = runBlocking {
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        val firstWaveStarted = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()

        val run = async {
            runBoundedWorkers(itemCount = 8, parallelism = 4) {
                val current = active.incrementAndGet()
                maximum.updateAndGet { previous -> maxOf(previous, current) }
                if (firstWaveStarted.incrementAndGet() == 4) release.complete(Unit)
                release.await()
                active.decrementAndGet()
            }
        }
        release.await()
        run.await()

        assertEquals(4, maximum.get())
        assertEquals(0, active.get())
    }

    @Test fun aggregateProgressIsMonotonicAcrossInterleavedItems() = runBlocking {
        val snapshots = mutableListOf<SyncProgress>()
        val tracker = SyncProgressTracker(total = 2, parallelUploads = 2) { snapshots += it }
        val first = media("first", 100)
        val second = media("second", 20)

        tracker.started(first)
        tracker.sent(first, 10)
        tracker.started(second)
        tracker.sent(second, 5)
        tracker.succeeded(second)
        tracker.skipped(first, "skipped")
        val result = tracker.finish()

        assertTrue(snapshots.zipWithNext().all { (before, after) -> after.bytes >= before.bytes })
        assertTrue(snapshots.all { it.current <= it.total })
        assertEquals(1, result.uploadedCount)
        assertEquals(20, result.uploadedBytes)
        assertEquals(1, result.failedCount)
    }

    private fun media(key: String, size: Long) = LocalMedia(
        mediaKey = key,
        contentUri = "content://media/$key",
        displayName = "$key.jpg",
        relativePath = "DCIM/Camera/",
        mimeType = "image/jpeg",
        byteLength = size,
        modifiedAt = 1,
        capturedAt = 1,
    )
}