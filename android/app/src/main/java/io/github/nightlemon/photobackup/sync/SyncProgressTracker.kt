package io.github.nightlemon.photobackup.sync

import io.github.nightlemon.photobackup.data.LocalMedia
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SyncProgressTracker(
    private val total: Int,
    private val parallelUploads: Int,
    private val emit: suspend (SyncProgress) -> Unit,
) {
    private val mutex = Mutex()
    private val inFlightBytes = mutableMapOf<String, Long>()
    private var processedCount = 0
    private var uploadedCount = 0
    private var uploadedBytes = 0L
    private var failedCount = 0
    private var finishedProgressBytes = 0L

    suspend fun started(item: LocalMedia) = update("正在备份 ${item.displayName}${parallelLabel()}") {
        inFlightBytes.putIfAbsent(item.mediaKey, 0)
    }

    suspend fun sent(item: LocalMedia, bytes: Long) = update("正在备份 ${item.displayName}${parallelLabel()}") {
        inFlightBytes[item.mediaKey] = maxOf(inFlightBytes[item.mediaKey] ?: 0, bytes)
    }

    suspend fun succeeded(item: LocalMedia) = update("已备份 ${item.displayName}") {
        val sent = inFlightBytes.remove(item.mediaKey) ?: 0
        finishedProgressBytes += maxOf(sent, item.byteLength)
        uploadedCount++
        uploadedBytes += item.byteLength
        processedCount++
    }

    suspend fun skipped(item: LocalMedia, message: String) = update(message) {
        finishedProgressBytes += inFlightBytes.remove(item.mediaKey) ?: 0
        failedCount++
        processedCount++
    }

    suspend fun finish(): SyncResult = mutex.withLock {
        emit(SyncProgress("备份完成", processedCount, total, finishedProgressBytes + inFlightBytes.values.sum()))
        SyncResult(uploadedCount, uploadedBytes, failedCount)
    }

    private suspend fun update(message: String, mutation: () -> Unit) = mutex.withLock {
        mutation()
        emit(SyncProgress(message, processedCount, total, finishedProgressBytes + inFlightBytes.values.sum()))
    }

    private fun parallelLabel(): String = if (parallelUploads > 1) "（$parallelUploads 路并行）" else ""
}
