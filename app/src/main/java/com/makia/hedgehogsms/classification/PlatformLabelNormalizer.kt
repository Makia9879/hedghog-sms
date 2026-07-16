package com.makia.hedgehogsms.classification

import java.text.Normalizer
import java.util.Locale

object PlatformLabelNormalizer {
    private val whitespace = Regex("\\s+")

    fun displayName(raw: String): String {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
            .replace(whitespace, " ")
            .trim()
        require(normalized.isNotEmpty()) { "platform label is blank" }
        return normalized
    }

    fun comparisonKey(raw: String): String = displayName(raw).lowercase(Locale.ROOT)
}
