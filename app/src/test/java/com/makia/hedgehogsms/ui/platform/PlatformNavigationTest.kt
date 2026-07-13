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
            .reduce(PlatformNavigationEvent.SelectDestination(PrimaryDestination.PENDING))
        assertEquals(PrimaryDestination.PENDING, state.destination)
        assertNull(state.selectedPlatformId)
    }

    @Test fun `back from evidence keeps platform primary destination`() {
        val state = PlatformNavigationState(PrimaryDestination.PLATFORMS, "bank")
            .reduce(PlatformNavigationEvent.ClosePlatform)
        assertEquals(PrimaryDestination.PLATFORMS, state.destination)
        assertNull(state.selectedPlatformId)
    }
}
