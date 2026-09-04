package io.github.nightlemon.photobackup

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nightlemon.photobackup.data.AppDatabase
import io.github.nightlemon.photobackup.data.BackupRecord
import io.github.nightlemon.photobackup.data.CredentialStore
import io.github.nightlemon.photobackup.data.PairingPayload
import io.github.nightlemon.photobackup.data.ServerCredential
import io.github.nightlemon.photobackup.media.MediaRepository
import io.github.nightlemon.photobackup.network.ServerConnector
import io.github.nightlemon.photobackup.sync.BackupSettings
import io.github.nightlemon.photobackup.sync.BackupSettingsStore
import io.github.nightlemon.photobackup.sync.ManualSyncScope
import io.github.nightlemon.photobackup.sync.SyncStateStore
import io.github.nightlemon.photobackup.sync.isOutsideCleanupRetention
import io.github.nightlemon.photobackup.sync.normalizeParallelUploads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val credentials = CredentialStore(application)
    private val database = AppDatabase.get(application)
    private val media = MediaRepository(application)
    private val connector = ServerConnector(application, credentials)
    private val json = Json { ignoreUnknownKeys = true }
    private val syncStateStore = SyncStateStore(application)
    private val settingsStore = BackupSettingsStore(application)

    private val _credential = MutableStateFlow(credentials.load())
    val credential: StateFlow<ServerCredential?> = _credential.asStateFlow()
    private val _message = MutableStateFlow(syncStateStore.read().message)
    val message: StateFlow<String> = _message.asStateFlow()
    private val _pairing = MutableStateFlow(false)
    val pairing: StateFlow<Boolean> = _pairing.asStateFlow()
    private val _cleanup = MutableStateFlow<List<BackupRecord>>(emptyList())
    val cleanup: StateFlow<List<BackupRecord>> = _cleanup.asStateFlow()
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()
    private val _settings = MutableStateFlow(settingsStore.read())
    val settings: StateFlow<BackupSettings> = _settings.asStateFlow()
    private val _cleanupClock = MutableStateFlow(System.currentTimeMillis())
    private val _checkingDeletion = MutableStateFlow(false)
    val checkingDeletion: StateFlow<Boolean> = _checkingDeletion.asStateFlow()
    private val _cleanupNotice = MutableStateFlow("")
    val cleanupNotice: StateFlow<String> = _cleanupNotice.asStateFlow()

    init {
        viewModelScope.launch {
            combine(database.backups().observeCleanupCandidates(), _settings, _credential, _cleanupClock) { records, settings, credential, now ->
                records.filter { record ->
                    credential != null && record.serverId == credential.serverId &&
                        record.isOutsideCleanupRetention(settings, now)
                }
            }.flowOn(Dispatchers.IO).collect { eligible ->
                _cleanup.value = eligible
                _selected.value = _selected.value.intersect(eligible.map { it.mediaKey }.toSet())
            }
        }
    }

    fun pair(scannedText: String) {
        viewModelScope.launch {
            _pairing.value = true
            _message.value = "正在连接家庭服务器"
            runCatching {
                val payload = json.decodeFromString<PairingPayload>(scannedText)
                connector.pair(payload, "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            }.onSuccess {
                _credential.value = it
                getApplication<PhotoBackupApplication>().enqueueImmediate()
                refreshSyncMessage()
            }.onFailure {
                _message.value = it.message ?: "配对失败"
            }
            _pairing.value = false
        }
    }

    fun syncNow() {
        getApplication<PhotoBackupApplication>().enqueueImmediate()
        refreshSyncMessage()
    }

    fun startManualSync(scope: ManualSyncScope) {
        getApplication<PhotoBackupApplication>().enqueueManual(scope)
        refreshSyncMessage()
    }

    fun refreshSyncMessage() {
        _message.value = syncStateStore.read().message
    }

    fun saveSettings(settings: BackupSettings) {
        val normalized = settings.copy(
            parallelUploads = normalizeParallelUploads(settings.parallelUploads),
            cleanupRetentionDays = settings.cleanupRetentionDays.coerceIn(0, BackupSettingsStore.MAX_RETENTION_DAYS),
        )
        settingsStore.save(normalized)
        _settings.value = normalized
        _cleanupClock.value = System.currentTimeMillis()
    }

    fun refreshCleanupClock() {
        _cleanupClock.value = System.currentTimeMillis()
    }

    fun prepareDeletion(records: List<BackupRecord>, onReady: (List<BackupRecord>) -> Unit) {
        if (records.isEmpty() || _checkingDeletion.value) return
        viewModelScope.launch {
            _checkingDeletion.value = true
            _cleanupNotice.value = "正在复核所选文件"
            val credential = _credential.value
            val settings = _settings.value
            val now = System.currentTimeMillis()
            val safe = withContext(Dispatchers.IO) {
                buildList {
                    records.forEachIndexed { index, record ->
                        _cleanupNotice.value = "正在复核 ${index + 1}/${records.size}"
                        if (credential != null && record.serverId == credential.serverId &&
                            record.isOutsideCleanupRetention(settings, now) && media.matchesBackupContent(record)
                        ) {
                            add(record)
                        }
                    }
                }
            }
            val omitted = records.size - safe.size
            _cleanupNotice.value = if (omitted > 0) "已排除 $omitted 个发生变化或仍在保留期内的文件" else "复核通过"
            _checkingDeletion.value = false
            if (safe.isNotEmpty()) onReady(safe)
        }
    }

    fun forgetServer() {
        credentials.clear()
        _credential.value = null
        _message.value = "请重新配对服务器"
    }

    fun toggle(mediaKey: String) {
        _selected.value = _selected.value.toMutableSet().apply {
            if (!add(mediaKey)) remove(mediaKey)
        }
    }

    fun selectAll(select: Boolean) {
        _selected.value = if (select) _cleanup.value.mapTo(mutableSetOf()) { it.mediaKey } else emptySet()
    }

    fun selectedRecords(): List<BackupRecord> = _cleanup.value.filter { it.mediaKey in _selected.value }

    fun deletionUri(record: BackupRecord): Uri = media.deletionUri(record)

    fun reconcileDeletion(records: List<BackupRecord>) {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = records.filterNot { media.exists(it.contentUri) }.map { it.mediaKey }
            val serverId = _credential.value?.serverId
            if (serverId != null && removed.isNotEmpty()) {
                database.backups().markCleaned(serverId, removed, System.currentTimeMillis())
            }
        }
    }
}

