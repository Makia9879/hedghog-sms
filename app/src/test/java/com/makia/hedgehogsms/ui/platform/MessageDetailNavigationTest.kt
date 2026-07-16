package com.makia.hedgehogsms.ui.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageDetailNavigationTest {
    @Test fun `closing detail from messages restores message source`() {
        val before = PlatformNavigationState(destination = PrimaryDestination.MESSAGES)
        val open = before.reduce(PlatformNavigationEvent.OpenMessageDetail(7, MessageDetailSource.Messages))

        val closed = open.reduce(PlatformNavigationEvent.CloseMessageDetail)

        assertNull(closed.detail)
        assertEquals(PrimaryDestination.MESSAGES, closed.destination)
        assertNull(closed.selectedPlatformId)
    }

    @Test fun `closing detail from platform evidence restores selected platform`() {
        val before = PlatformNavigationState(
            destination = PrimaryDestination.PLATFORMS,
            selectedPlatformId = "bank",
        )
        val open = before.reduce(PlatformNavigationEvent.OpenMessageDetail(7, MessageDetailSource.PlatformEvidence("bank")))

        val closed = open.reduce(PlatformNavigationEvent.CloseMessageDetail)

        assertNull(closed.detail)
        assertEquals(PrimaryDestination.PLATFORMS, closed.destination)
        assertEquals("bank", closed.selectedPlatformId)
    }

    @Test fun `only sensitive destinations request screenshot protection`() {
        assertEquals(false, PlatformNavigationState(destination = PrimaryDestination.MESSAGES).isSensitiveScreen())
        assertEquals(true, PlatformNavigationState(destination = PrimaryDestination.PENDING).isSensitiveScreen())
        assertEquals(true, PlatformNavigationState(destination = PrimaryDestination.PLATFORMS, selectedPlatformId = "bank").isSensitiveScreen())
        assertEquals(
            true,
            PlatformNavigationState(destination = PrimaryDestination.MESSAGES)
                .reduce(PlatformNavigationEvent.OpenMessageDetail(7, MessageDetailSource.Messages))
                .isSensitiveScreen(),
        )
    }

    @Test fun `closing platform evidence clears screenshot protection`() {
        val platformEvidence = PlatformNavigationState(
            destination = PrimaryDestination.PLATFORMS,
            selectedPlatformId = "bank",
        )

        val closed = platformEvidence.reduce(PlatformNavigationEvent.ClosePlatform)

        assertEquals(false, closed.isSensitiveScreen())
        assertNull(closed.selectedPlatformId)
    }

    @Test fun `closing detail back to platform evidence keeps screenshot protection`() {
        val detail = PlatformNavigationState(destination = PrimaryDestination.PLATFORMS)
            .reduce(PlatformNavigationEvent.OpenMessageDetail(7, MessageDetailSource.PlatformEvidence("bank")))

        val closed = detail.reduce(PlatformNavigationEvent.CloseMessageDetail)

        assertEquals(true, closed.isSensitiveScreen())
        assertEquals("bank", closed.selectedPlatformId)
    }

    @Test fun `system back closes detail before leaving activity`() {
        val detail = PlatformNavigationState(destination = PrimaryDestination.PLATFORMS)
            .reduce(PlatformNavigationEvent.OpenMessageDetail(7, MessageDetailSource.PlatformEvidence("bank")))

        assertEquals(PlatformNavigationEvent.CloseMessageDetail, detail.systemBackEvent())
    }

    @Test fun `system back closes platform evidence before leaving activity`() {
        val evidence = PlatformNavigationState(
            destination = PrimaryDestination.PLATFORMS,
            selectedPlatformId = "bank",
        )

        assertEquals(PlatformNavigationEvent.ClosePlatform, evidence.systemBackEvent())
    }

    @Test fun `system back is not intercepted on top level pages`() {
        assertNull(PlatformNavigationState(destination = PrimaryDestination.MESSAGES).systemBackEvent())
        assertNull(PlatformNavigationState(destination = PrimaryDestination.PLATFORMS).systemBackEvent())
        assertNull(PlatformNavigationState(destination = PrimaryDestination.PENDING).systemBackEvent())
    }
}
