package com.makia.hedgehogsms.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageIndexPrivacyTest {
    @Test fun `persistent index exposes no message content or sender fields`() {
        val names = MessageIndex::class.java.declaredFields.map { it.name.lowercase() }
        listOf("body", "address", "sender", "preview", "code", "otp").forEach { forbidden ->
            assertFalse("MessageIndex must not persist $forbidden", names.any { forbidden in it })
        }
    }

    @Test fun `mapping to index drops body and sender`() {
        val record = SmsRecord(7, 100, 1, "private-sender", "private-body-123456", null)
        val index = record.toIndex(SlotResolver().resolve(null, emptyMap(), 2), 200)
        assertTrue(index.sourceMessageId == 7L)
        assertNull(index.rawSubscriptionId)
    }
}
