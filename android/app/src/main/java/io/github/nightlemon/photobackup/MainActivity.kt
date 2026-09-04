package io.github.nightlemon.photobackup

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.nightlemon.photobackup.data.BackupRecord
import io.github.nightlemon.photobackup.media.MediaPermissions
import io.github.nightlemon.photobackup.ui.PhotoBackupRoot

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private val mediaAccessState = mutableStateOf(false)
    private var pendingDeletion: List<BackupRecord> = emptyList()
    private val androidRQueue = ArrayDeque<List<BackupRecord>>()
    private var androidRCurrent: List<BackupRecord> = emptyList()
    private val androidQQueue = ArrayDeque<BackupRecord>()
    private var androidQCurrent: BackupRecord? = null

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(viewModel::pair)
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        mediaAccessState.value = hasFullMediaAccess()
        if (hasFullMediaAccess()) (application as PhotoBackupApplication).enqueueImmediate()
    }
    private val deletePermission = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            viewModel.reconcileDeletion(androidRCurrent)
            if (result.resultCode == Activity.RESULT_OK) {
                deleteNextOnAndroidR()
            } else {
                androidRQueue.clear()
                androidRCurrent = emptyList()
                pendingDeletion = emptyList()
            }
        } else if (androidQCurrent != null) {
            if (result.resultCode == Activity.RESULT_OK) runCatching {
                contentResolver.delete(viewModel.deletionUri(androidQCurrent!!), null, null)
            }
            deleteNextOnAndroidQ()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaAccessState.value = hasFullMediaAccess()
        requestRequiredPermissions()
        if (hasFullMediaAccess() && viewModel.credential.value != null) {
            (application as PhotoBackupApplication).enqueueImmediate()
        }
        setContent {
            PhotoBackupRoot(
                viewModel = viewModel,
                openCleanupInitially = intent.getBooleanExtra("openCleanup", false),
                hasFullMediaAccess = mediaAccessState.value,
                onRequestPermissions = ::requestRequiredPermissions,
                onScan = {
                    scanner.launch(
                        ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("扫描电脑管理页上的配对二维码")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false),
                    )
                },
                onDelete = ::requestDeletion,
                onBatterySettings = {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val hadAccess = mediaAccessState.value
        mediaAccessState.value = hasFullMediaAccess()
        if (!hadAccess && mediaAccessState.value && viewModel.credential.value != null) {
            (application as PhotoBackupApplication).enqueueImmediate()
        }
        viewModel.refreshSyncMessage()
    }

    private fun requestDeletion(records: List<BackupRecord>) {
        if (records.isEmpty()) return
        pendingDeletion = records
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            androidRQueue.clear()
            records.chunked(500).forEach(androidRQueue::addLast)
            deleteNextOnAndroidR()
        } else {
            androidQQueue.clear()
            androidQQueue.addAll(records)
            deleteNextOnAndroidQ()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun deleteNextOnAndroidR() {
        if (androidRQueue.isEmpty()) {
            androidRCurrent = emptyList()
            pendingDeletion = emptyList()
            return
        }
        androidRCurrent = androidRQueue.removeFirst()
        val request = MediaStore.createDeleteRequest(contentResolver, androidRCurrent.map(viewModel::deletionUri))
        launchDeletePermission(request.intentSender)
    }

    private fun deleteNextOnAndroidQ() {
        while (androidQQueue.isNotEmpty()) {
            val record = androidQQueue.removeFirst()
            androidQCurrent = record
            try {
                contentResolver.delete(viewModel.deletionUri(record), null, null)
            } catch (security: RecoverableSecurityException) {
                launchDeletePermission(security.userAction.actionIntent.intentSender)
                return
            } catch (_: SecurityException) {
                // Keep the source and its cleanup record when permission is denied.
            }
        }
        androidQCurrent = null
        viewModel.reconcileDeletion(pendingDeletion)
        pendingDeletion = emptyList()
    }

    private fun launchDeletePermission(sender: IntentSender) {
        deletePermission.launch(IntentSenderRequest.Builder(sender).build())
    }

    private fun requestRequiredPermissions() {
        val requested = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.POST_NOTIFICATIONS)
                if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= 36) add("android.permission.ACCESS_LOCAL_NETWORK")
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (requested.isNotEmpty()) permissions.launch(requested.toTypedArray())
    }

    private fun hasFullMediaAccess(): Boolean = MediaPermissions.hasFullAccess(this)
}
