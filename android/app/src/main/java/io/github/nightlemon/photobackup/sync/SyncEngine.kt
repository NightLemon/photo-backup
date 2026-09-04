package io.github.nightlemon.photobackup.sync

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.github.nightlemon.photobackup.data.AppDatabase
import io.github.nightlemon.photobackup.data.BackupRecord
import io.github.nightlemon.photobackup.data.CredentialStore
import io.github.nightlemon.photobackup.data.LocalMedia
import io.github.nightlemon.photobackup.data.matches
import io.github.nightlemon.photobackup.media.MediaRepository
import io.github.nightlemon.photobackup.network.ApiException
import io.github.nightlemon.photobackup.network.BackupReceipt
import io.github.nightlemon.photobackup.network.PinnedHttpClient
import io.github.nightlemon.photobackup.network.PrepareRequest
import io.github.nightlemon.photobackup.network.ServerConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

class SyncEngine(context: Context) {
    private val appContext = context.applicationContext
    private val credentials = CredentialStore(appContext)
    private val database = AppDatabase.get(appContext)
    private val media = MediaRepository(appContext)
    private val connector = ServerConnector(appContext, credentials)
    private val resolver: ContentResolver = appContext.contentResolver
    private val state = SyncStateStore(appContext)
    private val settingsStore = BackupSettingsStore(appContext)

    suspend fun sync(
        manualScope: ManualSyncScope? = null,
        progress: suspend (SyncProgress) -> Unit,
    ): SyncResult = processMutex.withLock {
        withContext(Dispatchers.IO) {
            val credential = credentials.load() ?: return@withContext SyncResult()
            progress(SyncProgress("正在查找家庭服务器", 0, 0, 0))
            val server = connector.connect(credential)
            val checkpoint = manualScope?.checkpointKey() ?: ManualSyncScope.AUTOMATIC_CHECKPOINT
            val interruptedMediaKeys = state.interruptedMediaKeys(credential.serverId, checkpoint)
            val settings = settingsStore.read()
            val items = media.scan().selectedAndOrderedForBackup(interruptedMediaKeys, settings, manualScope)
            val existingRecords = database.backups().forServer(credential.serverId).associateBy { it.mediaKey }
            val pending = items.filterNot { item ->
                existingRecords[item.mediaKey]?.matches(item, credential.serverId) == true
            }
            state.retainCurrentMedia(credential.serverId, checkpoint, pending.mapTo(mutableSetOf()) { it.mediaKey })
            val parallelUploads = minOf(normalizeParallelUploads(settings.parallelUploads), pending.size)
            val tracker = SyncProgressTracker(pending.size, parallelUploads.coerceAtLeast(1), progress)
            runBoundedWorkers(pending.size, parallelUploads) { index ->
                processItem(pending[index], credential.serverId, checkpoint, server.api, tracker)
            }
            state.clearCurrentMedia(credential.serverId, checkpoint)
            tracker.finish()
        }
    }

    private suspend fun processItem(
        item: LocalMedia,
        serverId: String,
        checkpoint: String,
        api: PinnedHttpClient,
        tracker: SyncProgressTracker,
    ) {
        state.markCurrentMedia(serverId, checkpoint, item.mediaKey)
        try {
            tracker.started(item)
            val prepared = try {
                api.prepare(item.prepareRequest())
            } catch (error: ApiException) {
                if (error.status == 400) throw MediaRejectedException("服务器拒绝了 ${item.displayName}", error)
                throw error
            }
            val receipt = if (prepared.status == "complete") {
                requireNotNull(prepared.receipt) { "服务器完成响应缺少备份凭据" }
            } else {
                val uploadId = requireNotNull(prepared.uploadId) { "服务器未返回上传会话" }
                val chunkSize = requireNotNull(prepared.chunkSize) { "服务器未返回分块大小" }
                require(chunkSize in 1..Int.MAX_VALUE) { "服务器分块大小无效" }
                uploadMissingChunks(
                    item = item,
                    uploadId = uploadId,
                    chunkSize = chunkSize,
                    received = prepared.receivedChunks.toSet(),
                    api = api,
                ) { sent -> tracker.sent(item, sent) }
                api.finalize(uploadId)
            }
            val sourceHash = hashContent(item)
            check(sourceHash.equals(receipt.sha256, ignoreCase = true)) { "服务器备份校验结果不一致" }
            if (!media.isUnchanged(item)) throw MediaAccessException("${item.displayName} 在备份期间发生变化")
            database.backups().save(item.toRecord(serverId, receipt))
            state.clearCurrentMedia(serverId, checkpoint, item.mediaKey)
            tracker.succeeded(item)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: MediaAccessException) {
            state.clearCurrentMedia(serverId, checkpoint, item.mediaKey)
            tracker.skipped(item, "已跳过无法读取的 ${item.displayName}")
        } catch (error: MediaRejectedException) {
            state.clearCurrentMedia(serverId, checkpoint, item.mediaKey)
            tracker.skipped(item, "已跳过服务器拒绝的 ${item.displayName}")
        }
    }

    private fun hashContent(item: LocalMedia): String = mediaAccess(item) {
        val descriptor = resolver.openFileDescriptor(Uri.parse(item.contentUri), "r")
            ?: throw FileNotFoundException("无法复核 ${item.displayName}")
        descriptor.use {
            FileInputStream(it.fileDescriptor).use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            }
        }
    }

    private suspend fun uploadMissingChunks(
        item: LocalMedia,
        uploadId: String,
        chunkSize: Long,
        received: Set<Int>,
        api: PinnedHttpClient,
        progress: suspend (Long) -> Unit,
    ) {
        val descriptor = mediaAccess(item) {
            resolver.openFileDescriptor(Uri.parse(item.contentUri), "r")
                ?: throw FileNotFoundException("无法读取 ${item.displayName}")
        }
        descriptor.use {
            val stream = mediaAccess(item) { FileInputStream(it.fileDescriptor) }
            stream.use {
                var offset = 0L
                var index = 0
                var sent = 0L
                while (offset < item.byteLength) {
                    val expected = minOf(chunkSize, item.byteLength - offset).toInt()
                    if (index !in received) {
                        val bytes = mediaAccess(item) {
                            stream.channel.position(offset)
                            ByteArray(expected).also { buffer ->
                                var read = 0
                                while (read < expected) {
                                    val count = stream.read(buffer, read, expected - read)
                                    if (count < 0) throw IOException("读取源文件时提前结束")
                                    read += count
                                }
                            }
                        }
                        api.putChunk(uploadId, index, bytes, sha256(bytes))
                        sent += expected
                        progress(sent)
                    }
                    offset += expected
                    index++
                }
            }
        }
    }

    private fun LocalMedia.prepareRequest() = PrepareRequest(
        mediaKey, displayName, relativePath, mimeType, byteLength, modifiedAt, capturedAt,
    )

    private fun LocalMedia.toRecord(serverId: String, receipt: BackupReceipt) = BackupRecord(
        mediaKey = mediaKey,
        contentUri = contentUri,
        displayName = displayName,
        relativePath = relativePath,
        mimeType = mimeType,
        byteLength = byteLength,
        modifiedAt = modifiedAt,
        capturedAt = capturedAt,
        serverId = serverId,
        assetId = receipt.assetId,
        sha256 = receipt.sha256,
        completedAt = receipt.completedAt,
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private inline fun <T> mediaAccess(item: LocalMedia, action: () -> T): T = try {
        action()
    } catch (error: IOException) {
        throw MediaAccessException("无法读取 ${item.displayName}", error)
    } catch (error: SecurityException) {
        throw MediaAccessException("没有权限读取 ${item.displayName}", error)
    }

    private companion object {
        val processMutex = Mutex()
    }
}

data class SyncProgress(val message: String, val current: Int, val total: Int, val bytes: Long)
data class SyncResult(val uploadedCount: Int = 0, val uploadedBytes: Long = 0, val failedCount: Int = 0)

internal class MediaAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)
internal class MediaRejectedException(message: String, cause: Throwable? = null) : Exception(message, cause)
