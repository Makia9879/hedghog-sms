package com.makia.hedgehogsms.classification

interface FeatureHmacKey {
    val keyId: String
    fun sign(bytes: ByteArray): ByteArray
}

class ByteArrayFeatureHmacKey(secret: ByteArray, override val keyId: String = "test-v1") : FeatureHmacKey {
    private val key = secret.copyOf()
    init { require(key.isNotEmpty()) }
    override fun sign(bytes: ByteArray): ByteArray = javax.crypto.Mac.getInstance("HmacSHA256").run {
        init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        doFinal(bytes)
    }
}
