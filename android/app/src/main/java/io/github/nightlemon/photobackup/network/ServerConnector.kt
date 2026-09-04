package io.github.nightlemon.photobackup.network

import android.content.Context
import io.github.nightlemon.photobackup.data.CredentialStore
import io.github.nightlemon.photobackup.data.PairingPayload
import io.github.nightlemon.photobackup.data.ServerCredential
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

class ServerConnector(
    context: Context,
    private val credentialStore: CredentialStore,
) {
    private val discovery = ServerDiscovery(context)

    suspend fun pair(payload: PairingPayload, deviceName: String): ServerCredential {
        require(payload.version == 1) { "不支持的配对协议版本" }
        require(payload.expiresAt > System.currentTimeMillis()) { "二维码已经过期，请重新生成" }
        val directCandidates = payload.addresses.map { DiscoveredServer(it, payload.port) }
        val discovered = withTimeoutOrNull(2_000) { discovery.find(payload.serverId, 2_000) }
        val candidates = (directCandidates + listOfNotNull(discovered)).distinctBy { it.host to it.port }
        var lastError: Throwable? = null
        for (candidate in candidates) {
            try {
                val client = PinnedHttpClient(candidate.host, candidate.port, payload.tlsSpkiSha256)
                val health = client.health()
                require(health.serverId == payload.serverId && health.apiVersion == 1) { "发现了错误的服务器" }
                val response = client.pair(payload, deviceName)
                require(response.serverId == payload.serverId) { "配对响应来自错误的服务器" }
                return ServerCredential(
                    serverId = payload.serverId,
                    serverName = payload.serverName,
                    port = candidate.port,
                    addresses = payload.addresses,
                    tlsSpkiSha256 = payload.tlsSpkiSha256,
                    deviceId = response.deviceId,
                    deviceName = response.deviceName,
                    token = response.token,
                    lastHost = candidate.host,
                ).also(credentialStore::save)
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException("无法连接二维码中的服务器", lastError)
    }

    suspend fun connect(credential: ServerCredential): ConnectedServer = coroutineScope {
        val candidates = mutableListOf<DiscoveredServer>()
        credential.lastHost?.let { candidates += DiscoveredServer(it, credential.port) }
        val discoveryDeferred = async { discovery.find(credential.serverId) }
        credential.addresses.forEach { candidates += DiscoveredServer(it, credential.port) }

        var lastError: Throwable? = null
        suspend fun tryCandidates(items: List<DiscoveredServer>): ConnectedServer? {
            for (candidate in items.distinctBy { it.host to it.port }) {
                try {
                    val client = PinnedHttpClient(
                        candidate.host,
                        candidate.port,
                        credential.tlsSpkiSha256,
                        credential.token,
                    )
                    val health = client.health()
                    if (health.serverId == credential.serverId && health.apiVersion == 1) {
                        credentialStore.updateLastHost(candidate.host)
                        return ConnectedServer(candidate.host, client)
                    }
                } catch (error: Throwable) {
                    lastError = error
                }
            }
            return null
        }

        tryCandidates(candidates)?.let { discoveryDeferred.cancel(); return@coroutineScope it }
        discoveryDeferred.await()?.let { tryCandidates(listOf(it)) }?.let { return@coroutineScope it }
        throw IllegalStateException("家庭照片服务器当前不可达", lastError)
    }
}

data class ConnectedServer(val host: String, val api: PinnedHttpClient)
