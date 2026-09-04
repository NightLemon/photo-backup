package io.github.nightlemon.photobackup

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.nightlemon.photobackup.sync.BackupWorker
import io.github.nightlemon.photobackup.sync.ManualSyncScope
import io.github.nightlemon.photobackup.sync.SyncStateStore
import java.util.concurrent.TimeUnit

class PhotoBackupApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        schedulePeriodicBackup()
    }

    fun enqueueImmediate(replace: Boolean = false) {
        val state = SyncStateStore(this)
        if (replace || state.read().state !in setOf("starting", "running")) {
            state.set("queued", "正在等待系统启动同步")
        }
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(immediateNetworkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        val workManager = WorkManager.getInstance(this)
        workManager.cancelUniqueWork(LEGACY_IMMEDIATE_WORK)
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun enqueueManual(scope: ManualSyncScope) {
        SyncStateStore(this).set("queued", "手动同步正在等待启动")
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(scope.toWorkData())
            .setConstraints(immediateNetworkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        val workManager = WorkManager.getInstance(this)
        workManager.cancelUniqueWork(LEGACY_IMMEDIATE_WORK)
        workManager.enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private fun schedulePeriodicBackup() {
        val request = PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES)
            .setConstraints(periodicNetworkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun immediateNetworkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private fun periodicNetworkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .build()

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(SYNC_CHANNEL, getString(R.string.sync_channel), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CLEANUP_CHANNEL, getString(R.string.cleanup_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        const val SYNC_CHANNEL = "photo-backup-sync"
        const val CLEANUP_CHANNEL = "photo-backup-cleanup"
        private const val PERIODIC_WORK = "periodic-photo-backup"
        private const val LEGACY_IMMEDIATE_WORK = "immediate-photo-backup"
        private const val IMMEDIATE_WORK = "immediate-photo-backup-v2"
    }
}

