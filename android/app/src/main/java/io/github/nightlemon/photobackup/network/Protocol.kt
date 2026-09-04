package io.github.nightlemon.photobackup.network

import kotlinx.serialization.Serializable

@Serializable
data class PairRequest(val secret: String, val deviceName: String)

@Serializable
data class PairResponse(
    val deviceId: String,
    val deviceName: String,
    val token: String,
    val serverId: String,
)

@Serializable
data class PrepareRequest(
    val mediaKey: String,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val byteLength: Long,
    val modifiedAt: Long,
    val capturedAt: Long,
)

@Serializable
data class PrepareResponse(
    val status: String,
    val uploadId: String? = null,
    val chunkSize: Long? = null,
    val receivedChunks: List<Int> = emptyList(),
    val receipt: BackupReceipt? = null,
)

@Serializable
data class BackupReceipt(
    val assetId: String,
    val mediaKey: String,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val byteLength: Long,
    val modifiedAt: Long,
    val capturedAt: Long,
    val sha256: String,
    val completedAt: Long,
)

@Serializable
data class HealthResponse(val ok: Boolean, val serverId: String, val apiVersion: Int)

