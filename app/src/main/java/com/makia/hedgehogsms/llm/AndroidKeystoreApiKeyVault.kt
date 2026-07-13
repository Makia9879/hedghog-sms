package com.makia.hedgehogsms.llm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreApiKeyVault(context: Context) : ApiKeyVault {
    private val preferences = context.getSharedPreferences(NON_BACKUP_PREFERENCES_FILE, Context.MODE_PRIVATE)

    override fun store(providerHost: String, secret: CharArray): ApiKeyHandle {
        require(providerHost.isNotBlank() && secret.isNotEmpty())
        val handle = ApiKeyHandle(UUID.randomUUID().toString())
        val clear = secret.concatToString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(clear)
            val record = listOf(providerHost, encode(cipher.iv), encode(encrypted)).joinToString(SEPARATOR)
            check(preferences.edit().putString(handle.opaqueId, record).commit()) { "failed to persist encrypted API key" }
            return handle
        } finally {
            clear.fill(0)
            secret.fill('\u0000')
        }
    }

    override suspend fun <T> withSecret(handle: ApiKeyHandle, expectedProviderHost: String, block: suspend (CharArray) -> T): T {
        val record = preferences.getString(handle.opaqueId, null) ?: throw HarnessDenied("unknown API key handle")
        val parts = record.split(SEPARATOR)
        if (parts.size != 3) throw HarnessDenied("invalid encrypted API key record")
        if (parts[0] != expectedProviderHost) throw HarnessDenied("API key handle belongs to another provider")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(parts[1])))
        val clear = try { cipher.doFinal(decode(parts[2])) } catch (_: Exception) { throw HarnessDenied("API key cannot be decrypted") }
        val chars = clear.toString(Charsets.UTF_8).toCharArray()
        return try { block(chars) } finally { clear.fill(0); chars.fill('\u0000') }
    }

    override fun delete(handle: ApiKeyHandle) {
        preferences.edit().remove(handle.opaqueId).commit()
    }

    override fun clearAll() {
        preferences.edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    private fun encode(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)

    companion object {
        const val NON_BACKUP_PREFERENCES_FILE = "no_backup_hedgehog_llm_api_keys"
        const val KEY_ALIAS = "hedgehog.llm.api-key.aes-gcm.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val SEPARATOR = "|"
    }
}
