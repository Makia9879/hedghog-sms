package com.makia.hedgehogsms.ui.platform

import com.makia.hedgehogsms.classification.PlatformSlotFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlatformNavigationTest {
    @Test fun `opening platform selects platform tab and detail`() {
        val state = PlatformNavigationState().reduce(PlatformNavigationEvent.OpenPlatform("bank"))
        assertEquals(PrimaryDestination.PLATFORMS, state.destination)
        assertEquals("bank", state.selectedPlatformId)
    }

    @Test fun `switching primary tab closes nested platform detail`() {
        val state = PlatformNavigationState(PrimaryDestination.PLATFORMS, "bank")
            .reduce(PlatformNavigationEvent.SelectDestination(PrimaryDestination.SLOTS))
        assertEquals(PrimaryDestination.SLOTS, state.destination)
        assertNull(state.selectedPlatformId)
    }

    @Test fun `opening pending labels hides bottom navigation without adding a primary tab`() {
        val state = PlatformNavigationState()
            .reduce(PlatformNavigationEvent.OpenPendingLabels)

        assertEquals(PrimaryDestination.PLATFORMS, state.destination)
        assertEquals(true, state.pendingOpen)
        assertEquals(true, state.hidesBottomBar)
    }

    @Test fun `back from evidence keeps platform primary destination`() {
        val state = PlatformNavigationState(PrimaryDestination.PLATFORMS, "bank")
            .reduce(PlatformNavigationEvent.ClosePlatform)
        assertEquals(PrimaryDestination.PLATFORMS, state.destination)
        assertNull(state.selectedPlatformId)
    }

    @Test fun `opening platform from slot detail returns to selected slot`() {
        val state = PlatformNavigationState(destination = PrimaryDestination.SLOTS, selectedSlot = PlatformSlotFilter.SLOT_1)
            .reduce(PlatformNavigationEvent.OpenPlatform("bank"))
            .reduce(PlatformNavigationEvent.ClosePlatform)

        assertEquals(PrimaryDestination.PLATFORMS, state.destination)
        assertEquals(PlatformSlotFilter.SLOT_1, state.selectedSlot)
        assertNull(state.selectedPlatformId)
    }

    @Test fun `opening message from platform reached through slot returns to platform evidence`() {
        val state = PlatformNavigationState(destination = PrimaryDestination.SLOTS, selectedSlot = PlatformSlotFilter.SLOT_1)
            .reduce(PlatformNavigationEvent.OpenPlatform("bank"))
            .reduce(PlatformNavigationEvent.OpenMessageDetail(7, MessageDetailSource.PlatformEvidence("bank")))
            .reduce(PlatformNavigationEvent.CloseMessageDetail)

        assertEquals(PrimaryDestination.PLATFORMS, state.destination)
        assertEquals(PlatformSlotFilter.SLOT_1, state.selectedSlot)
        assertEquals("bank", state.selectedPlatformId)
        assertNull(state.detail)
    }
}
