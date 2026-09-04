package io.github.nightlemon.photobackup.data

import androidx.room.Entity
import kotlinx.serialization.Serializable

@Serializable
data class PairingPayload(
    val version: Int,
    val serverId: String,
    val serverName: String,
    val port: Int,
    val addresses: List<String>,
    val tlsSpkiSha256: String,
    val pairSecret: String,
    val expiresAt: Long,
)

@Serializable
data class ServerCredential(
    val serverId: String,
    val serverName: String,
    val port: Int,
    val addresses: List<String>,
    val tlsSpkiSha256: String,
    val deviceId: String,
    val deviceName: String,
    val token: String,
    val lastHost: String? = null,
)

@Entity(tableName = "backups", primaryKeys = ["serverId", "mediaKey"])
data class BackupRecord(
    val mediaKey: String,
    val contentUri: String,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val byteLength: Long,
    val modifiedAt: Long,
    val capturedAt: Long,
    val serverId: String,
    val assetId: String,
    val sha256: String,
    val completedAt: Long,
    val cleanedAt: Long? = null,
)

data class LocalMedia(
    val mediaKey: String,
    val contentUri: String,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val byteLength: Long,
    val modifiedAt: Long,
    val capturedAt: Long,
)

fun BackupRecord.matches(media: LocalMedia, activeServerId: String): Boolean =
    serverId == activeServerId && mediaKey == media.mediaKey && byteLength == media.byteLength &&
        modifiedAt == media.modifiedAt && cleanedAt == null

