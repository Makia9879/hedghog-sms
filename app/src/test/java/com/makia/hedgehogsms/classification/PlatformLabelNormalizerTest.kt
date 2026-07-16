package com.makia.hedgehogsms.classification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlatformLabelNormalizerTest {
    @Test fun `display name trims normalizes and collapses whitespace`() {
        assertEquals("Caf\u00e9 银行", PlatformLabelNormalizer.displayName("  Cafe\u0301   银行  "))
    }

    @Test fun `comparison key is case insensitive and unicode stable`() {
        val composed = PlatformLabelNormalizer.comparisonKey("Caf\u00e9 Bank")
        val decomposed = PlatformLabelNormalizer.comparisonKey(" cafe\u0301   BANK ")

        assertEquals(composed, decomposed)
    }

    @Test fun `blank names are rejected before key generation`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlatformLabelNormalizer.displayName(" \n\t ")
        }
    }

    @Test fun `stable platform identifiers use the same normalized name`() {
        assertEquals(
            PlatformRuleClassifier.stablePlatformKey("Caf\u00e9 Bank"),
            PlatformRuleClassifier.stablePlatformKey(" cafe\u0301   BANK "),
        )
        assertEquals(
            PlatformRuleClassifier.stablePlatformLabelId("Caf\u00e9 Bank"),
            PlatformRuleClassifier.stablePlatformLabelId(" cafe\u0301   BANK "),
        )
    }
}
