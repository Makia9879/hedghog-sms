package com.makia.hedgehogsms.ui.platform

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
}
