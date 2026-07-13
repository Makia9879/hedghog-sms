package com.makia.hedgehogsms.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SlotResolverTest {
    private val resolver = SlotResolver()

    @Test fun `missing and negative subscription ids stay unknown`() {
        assertEquals(SlotMappingStatus.UNKNOWN_NO_SUB_ID, resolver.resolve(null, emptyMap(), 2).status)
        assertEquals(SlotMappingStatus.UNKNOWN_NO_SUB_ID, resolver.resolve(-1, emptyMap(), 2).status)
    }

    @Test fun `active subscription maps only to a valid logical slot`() {
        assertEquals(SlotResolution(1, SlotMappingStatus.RESOLVED), resolver.resolve(42, mapOf(42L to 1), 2))
        assertEquals(SlotMappingStatus.UNKNOWN_INVALID_SLOT, resolver.resolve(42, mapOf(42L to 2), 2).status)
    }

    @Test fun `missing phone permission and inactive subscriptions have distinct reasons`() {
        assertEquals(SlotMappingStatus.UNKNOWN_PERMISSION, resolver.resolve(42, null, 2).status)
        assertEquals(SlotMappingStatus.UNKNOWN_INACTIVE_SUBSCRIPTION, resolver.resolve(42, emptyMap(), 2).status)
    }
}
