package com.makia.hedgehogsms.ui.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageDetailUiTest {
    @Test fun `message detail state includes body only after successful read`() {
        val detail = messageDetailUi(
            navigation = MessageDetailNavigation(7, MessageDetailSource.Scan),
            senderText = "sender",
            body = "body",
            receivedAtText = "time",
            statusText = null,
        )!!

        assertEquals("sender", detail.senderText)
        assertEquals("body", detail.body)
        assertEquals("time", detail.receivedAtText)
        assertEquals("来自扫描页", detail.sourceText)
        assertNull(detail.statusText)
        assertEquals(false, detail.canRequestPermission)
    }

    @Test fun `deleted detail state has no body`() {
        val detail = messageDetailUi(
            navigation = MessageDetailNavigation(7, MessageDetailSource.PlatformEvidence("bank")),
            senderText = "sender",
            body = "body",
            receivedAtText = "time",
            statusText = "系统短信已删除",
        )!!

        assertEquals("", detail.senderText)
        assertNull(detail.body)
        assertEquals("", detail.receivedAtText)
        assertEquals("来自平台详情", detail.sourceText)
        assertEquals("系统短信已删除", detail.statusText)
        assertEquals(false, detail.canRequestPermission)
    }

    @Test fun `permission unavailable detail state has no sender or body`() {
        val detail = messageDetailUi(
            navigation = MessageDetailNavigation(7, MessageDetailSource.Scan),
            senderText = "sender",
            body = "body",
            receivedAtText = "time",
            statusText = MESSAGE_DETAIL_PERMISSION_UNAVAILABLE,
        )!!

        assertEquals("", detail.senderText)
        assertNull(detail.body)
        assertEquals("", detail.receivedAtText)
        assertEquals("来自扫描页", detail.sourceText)
        assertEquals(MESSAGE_DETAIL_PERMISSION_UNAVAILABLE, detail.statusText)
        assertEquals(true, detail.canRequestPermission)
    }
}
