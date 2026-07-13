package com.makia.hedgehogsms.classification

import java.nio.ByteBuffer
import java.text.Normalizer
import java.util.Locale

data class SparseFeatures(
    val buckets: Map<Int, Int>,
    val schemaVersion: Int = 1,
    val keyId: String = "local-v1",
)

class TextFeatureExtractor(
    private val hmacKey: FeatureHmacKey,
) {
    constructor(secretKey: ByteArray, keyId: String = "local-v1") : this(ByteArrayFeatureHmacKey(secretKey, keyId))

    fun extract(text: CharSequence, sender: String? = null): SparseFeatures {
        val normalized = redact(text.toString())
        val tokens = buildList {
            val chars = normalized.toCharArray()
            for (size in 1..3) for (start in 0..chars.size - size) add("body:${String(chars, start, size)}")
            add("structure:length:${lengthBand(normalized.length)}")
            if (OtpDetector.isLikelyOtp(text)) add("structure:otp")
            // The raw sender exists only long enough to feed HMAC. Redacting it
            // to <phone> first would collapse every numeric sender into the
            // same weak feature and make conflict detection impossible.
            sender?.takeIf { it.isNotBlank() }?.let { add("sender:${normalize(it)}") }
        }
        val counts = mutableMapOf<Int, Int>()
        tokens.forEach { token ->
            val bucket = bucket(token)
            counts[bucket] = ((counts[bucket] ?: 0) + 1).coerceAtMost(3)
        }
        return SparseFeatures(counts, keyId = hmacKey.keyId)
    }

    fun redact(raw: String): String {
        var value = normalize(raw)
        value = URL.replace(value) { match -> match.value.substringBefore('?') + "?<url_param>" }
        value = DATE.replace(value, "<date>")
        value = MONEY.replace(value, "<amount>")
        value = PHONE.replace(value, "<phone>")
        value = CODE.replace(value, "<code>")
        value = NUMBER.replace(value, "<number>")
        return value.replace(Regex("\\s+"), " ").trim()
    }

    private fun normalize(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ").trim()

    private fun bucket(token: String): Int {
        val firstInt = ByteBuffer.wrap(hmacKey.sign(token.toByteArray(Charsets.UTF_8))).int
        return (firstInt.toLong() and 0xffff_ffffL).rem(BUCKETS).toInt()
    }

    private fun lengthBand(length: Int) = when {
        length < 40 -> "short"
        length < 120 -> "medium"
        else -> "long"
    }

    private companion object {
        const val BUCKETS = 16_384L
        val URL = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
        val DATE = Regex("\\b(?:19|20)\\d{2}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?\\b")
        val MONEY = Regex("(?:[¥￥$]\\s*\\d+(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)?\\s*(?:元|美元|usd|cny))", RegexOption.IGNORE_CASE)
        val PHONE = Regex("(?<!\\d)(?:\\+?86[- ]?)?1\\d{10}(?!\\d)")
        val CODE = Regex("(?<![A-Za-z0-9])(?=[A-Za-z0-9]{4,8}(?![A-Za-z0-9]))(?=[A-Za-z0-9]*\\d)[A-Za-z0-9]+")
        val NUMBER = Regex("\\d{2,}")
    }
}
