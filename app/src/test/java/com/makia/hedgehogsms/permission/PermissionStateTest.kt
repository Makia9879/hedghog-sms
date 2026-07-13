package com.makia.hedgehogsms.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionStateTest {
    @Test fun `permissions are requested once in the required order`() {
        var state = PermissionSnapshot()
        assertEquals(PermissionStep.EXPLANATION, state.nextStep)
        state = state.reduce(PermissionEvent.ExplanationAccepted)
        assertEquals(PermissionStep.READ_SMS, state.nextStep)
        state = state.reduce(PermissionEvent.Result(PermissionStep.READ_SMS, true))
        assertEquals(PermissionStep.READ_PHONE_STATE, state.nextStep)
        state = state.reduce(PermissionEvent.Result(PermissionStep.READ_PHONE_STATE, false))
        assertTrue(state.phoneStateDegraded)
        assertEquals(PermissionStep.RECEIVE_SMS, state.nextStep)
        state = state.reduce(PermissionEvent.Result(PermissionStep.RECEIVE_SMS, false))
        assertTrue(state.receiveSmsDegraded)
        assertEquals(PermissionStep.COMPLETE, state.nextStep)
    }

    @Test fun `read sms revocation blocks inbox without erasing progress`() {
        val ready = PermissionSnapshot(true, true, true, true, PermissionStep.entries.toSet())
        val revoked = ready.reduce(PermissionEvent.Refreshed(false, true, true))
        assertFalse(revoked.canReadInbox)
        assertEquals(ready.requested, revoked.requested)
    }
}
