package com.makia.hedgehogsms.classification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FeatureHmacKeyTest {
    @Test fun extractorUsesNarrowSignerAndPersistsOnlyKeyIdentity() {
        val first = TextFeatureExtractor(ByteArrayFeatureHmacKey(ByteArray(32) { 1 }, "key-a"))
            .extract("synthetic message")
        val second = TextFeatureExtractor(ByteArrayFeatureHmacKey(ByteArray(32) { 2 }, "key-b"))
            .extract("synthetic message")

        assertEquals("key-a", first.keyId)
        assertEquals("key-b", second.keyId)
        assertNotEquals(first.buckets, second.buckets)
    }
}
