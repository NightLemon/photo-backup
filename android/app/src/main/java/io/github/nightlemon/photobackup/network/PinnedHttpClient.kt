package io.github.nightlemon.photobackup.network

import android.annotation.SuppressLint
import android.util.Base64
import io.github.nightlemon.photobackup.data.PairingPayload
import io.github.nightlemon.photobackup.data.ServerCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Inet6Address
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class PinnedHttpClient(
    private val host: String,
    private val port: Int,
    expectedPin: String,
    private val token: String? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val client: OkHttpClient
    private val baseUrl: String

    init {
        val pin = expectedPin.removePrefix("sha256/").let { Base64.decode(it, Base64.DEFAULT) }
        require(pin.size == 32) { "服务器公钥指纹格式无效" }
        val trustManager = PublicKeyTrustManager(pin)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustManager), null)
        val dispatcher = Dispatcher().apply {
            maxRequests = 16
            maxRequestsPerHost = 8
        }
        client = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .sslSocketFactory(context.socketFactory, trustManager)
            // Host may be a DHCP address. The trust manager authenticates the pinned public key.
            .hostnameVerifier { _, session ->
                runCatching { trustManager.verify(session.peerCertificates.first() as X509Certificate) }.isSuccess
            }
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .writeTimeout(2, TimeUnit.MINUTES)
            .callTimeout(3, TimeUnit.MINUTES)
            .build()
        val urlHost = if (host.contains(":") && runCatching { java.net.InetAddress.getByName(host) is Inet6Address }.getOrDefault(false)) "[$host]" else host
        baseUrl = "https://$urlHost:$port"
    }

    suspend fun health(): HealthResponse = get("/api/v1/health")

    suspend fun pair(payload: PairingPayload, deviceName: String): PairResponse =
        post("/api/v1/pair", PairRequest(payload.pairSecret, deviceName), authenticated = false)

    suspend fun prepare(request: PrepareRequest): PrepareResponse = post("/api/v1/uploads/prepare", request)

    suspend fun putChunk(uploadId: String, index: Int, bytes: ByteArray, sha256: String) = withContext(Dispatchers.IO) {
        val request = requestBuilder("/api/v1/uploads/$uploadId/chunks/$index")
            .header("X-Chunk-SHA256", sha256)
            .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, response.body?.string())
        }
    }

    suspend fun finalize(uploadId: String): BackupReceipt = post("/api/v1/uploads/$uploadId/finalize", Unit)

    private suspend inline fun <reified T> get(path: String): T = withContext(Dispatchers.IO) {
        client.newCall(requestBuilder(path).get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, body)
            json.decodeFromString<T>(body)
        }
    }

    private suspend inline fun <reified RequestType, reified ResponseType> post(
        path: String,
        value: RequestType,
        authenticated: Boolean = true,
    ): ResponseType = withContext(Dispatchers.IO) {
        val body = if (value is Unit) ByteArray(0).toRequestBody(null) else
            json.encodeToString(value).toRequestBody("application/json".toMediaType())
        val builder = Request.Builder().url(baseUrl + path).post(body)
        if (authenticated) token?.let { builder.header("Authorization", "Bearer $it") }
        client.newCall(builder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, responseBody)
            json.decodeFromString<ResponseType>(responseBody)
        }
    }

    private fun requestBuilder(path: String): Request.Builder = Request.Builder().url(baseUrl + path).apply {
        token?.let { header("Authorization", "Bearer $it") }
    }
}

class ApiException(val status: Int, detail: String?) : Exception("服务器返回 $status${detail?.let { ": $it" }.orEmpty()}")

@SuppressLint("CustomX509TrustManager")
private class PublicKeyTrustManager(private val expectedPin: ByteArray) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client certificates are not supported")

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull() ?: throw CertificateException("server certificate missing")
        verify(certificate)
    }

    fun verify(certificate: X509Certificate) {
        certificate.checkValidity()
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        if (!MessageDigest.isEqual(expectedPin, actual)) throw CertificateException("server public key changed")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
