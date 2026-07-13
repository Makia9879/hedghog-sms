package com.makia.hedgehogsms.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.content.Context
import com.makia.hedgehogsms.classification.FeatureHmacKey
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import java.util.UUID

class AndroidFeatureHmacKey(private val context: Context) : FeatureHmacKey {
    override val keyId: String by lazy {
        val marker = context.noBackupFilesDir.resolve(KEY_ID_FILE)
        if (marker.exists()) marker.readText().trim() else UUID.randomUUID().toString().also(marker::writeText)
    }
    private val alias get() = "$ALIAS_PREFIX.$keyId"
    override fun sign(bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(key())
        doFinal(bytes)
    }

    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256).build())
            generateKey()
        }
    }

    fun clearAll() {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            aliases().toList().filter { it.startsWith(ALIAS_PREFIX) }.forEach(::deleteEntry)
        }
        context.noBackupFilesDir.resolve(KEY_ID_FILE).delete()
    }

    private companion object {
        const val ALIAS_PREFIX = "hedgehog.feature.hmac.v1"
        const val KEY_ID_FILE = "hedgehog-feature-key-id"
    }
}
