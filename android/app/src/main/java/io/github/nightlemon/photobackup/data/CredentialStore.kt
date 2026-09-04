package io.github.nightlemon.photobackup.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("server-credential", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(): ServerCredential? {
        val encrypted = preferences.getString(CREDENTIAL, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
            val ivLength = bytes.first().toInt() and 0xff
            require(ivLength in 12..16 && bytes.size > ivLength + 1)
            val iv = bytes.copyOfRange(1, ivLength + 1)
            val ciphertext = bytes.copyOfRange(ivLength + 1, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            json.decodeFromString<ServerCredential>(cipher.doFinal(ciphertext).decodeToString())
        }.getOrElse {
            clear()
            null
        }
    }

    @Synchronized
    fun save(credential: ServerCredential) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(json.encodeToString(credential).encodeToByteArray())
        val encoded = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + ciphertext
        preferences.edit().putString(CREDENTIAL, Base64.encodeToString(encoded, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    fun updateLastHost(host: String) {
        load()?.let { save(it.copy(lastHost = host)) }
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "home-photo-backup-device-token-v1"
        const val CREDENTIAL = "credential"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

