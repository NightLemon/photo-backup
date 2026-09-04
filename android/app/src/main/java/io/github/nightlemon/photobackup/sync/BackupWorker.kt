package io.github.nightlemon.photobackup.sync

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.nightlemon.photobackup.MainActivity
import io.github.nightlemon.photobackup.PhotoBackupApplication
import io.github.nightlemon.photobackup.R
import io.github.nightlemon.photobackup.data.CredentialStore
import io.github.nightlemon.photobackup.media.MediaPermissions
import io.github.nightlemon.photobackup.network.ApiException
import kotlinx.coroutines.CancellationException

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val state = SyncStateStore(context)

    override suspend fun doWork(): Result {
        val manualScope = try {
            ManualSyncScope.fromWorkData(inputData)
        } catch (error: IllegalArgumentException) {
            state.set("error", error.message ?: "手动同步范围无效")
            return Result.failure()
        }
        if (CredentialStore(applicationContext).load() == null) return Result.success()
        if (!MediaPermissions.hasFullAccess(applicationContext)) {
            state.set("permission", "需要完整的照片和视频权限")
            return Result.success()
        }
        state.set("starting", "正在启动备份")
        return try {
            setForeground(foreground("准备备份…", 0, 0))
            state.set("running", "正在准备备份")
            var lastProgressUpdate = 0L
            val result = SyncEngine(applicationContext).sync(manualScope) { progress ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastProgressUpdate >= PROGRESS_UPDATE_INTERVAL_MS || progress.current == progress.total) {
                    state.set("running", progress.message)
                    setForeground(foreground(progress.message, progress.current, progress.total))
                    lastProgressUpdate = now
                }
            }
            val prefix = if (manualScope == null) "" else "手动同步："
            val message = prefix + when {
                result.failedCount > 0 && result.uploadedCount > 0 -> "已备份 ${result.uploadedCount} 个，跳过 ${result.failedCount} 个"
                result.failedCount > 0 -> "跳过 ${result.failedCount} 个无法备份的文件"
                result.uploadedCount > 0 -> "已备份 ${result.uploadedCount} 个文件"
                else -> "没有新文件"
            }
            state.set("success", message)
            if (result.uploadedCount > 0) notifyCleanup(result)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            state.set("error", error.message ?: "备份暂时失败")
            if (error is ApiException && error.status in listOf(401, 403)) Result.failure() else Result.retry()
        }
    }

    private fun foreground(message: String, current: Int, total: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, PhotoBackupApplication.SYNC_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("家庭照片备份")
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total, current, total == 0)
            .setContentIntent(mainPendingIntent())
            .build()
        return ForegroundInfo(ONGOING_NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notifyCleanup(result: SyncResult) {
        val notification: Notification = NotificationCompat.Builder(applicationContext, PhotoBackupApplication.CLEANUP_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("备份完成，可以释放空间")
            .setContentText("${result.uploadedCount} 个文件，共 ${formatSize(result.uploadedBytes)}")
            .setAutoCancel(true)
            .setContentIntent(mainPendingIntent(cleanup = true))
            .build()
        applicationContext.getSystemService(NotificationManager::class.java).notify(CLEANUP_NOTIFICATION, notification)
    }

    private fun mainPendingIntent(cleanup: Boolean = false): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).putExtra("openCleanup", cleanup)
        return PendingIntent.getActivity(
            applicationContext,
            if (cleanup) 2 else 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
        else -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
    }

    private companion object {
        const val ONGOING_NOTIFICATION = 1001
        const val CLEANUP_NOTIFICATION = 1002
        const val PROGRESS_UPDATE_INTERVAL_MS = 500L
    }
}
