package com.makia.hedgehogsms.ui.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyStateTest {
    @Test fun `screenshot protection needs explicit risk confirmation`() {
        val pending = PrivacyUiState().reduce(PrivacyEvent.RequestDisableScreenshots)
        assertTrue(pending.protectSensitiveScreens)
        assertTrue(pending.screenshotRiskPending)
        val disabled = pending.reduce(PrivacyEvent.ConfirmDisableScreenshots)
        assertFalse(disabled.protectSensitiveScreens)
        assertFalse(disabled.screenshotRiskPending)
    }

    @Test fun `clear needs two confirmations before interface may run`() {
        val first = PrivacyUiState().reduce(PrivacyEvent.RequestClear)
        assertEquals(1, first.clearConfirmationStep)
        val second = first.reduce(PrivacyEvent.ConfirmClear)
        assertEquals(2, second.clearConfirmationStep)
        val finished = second.reduce(PrivacyEvent.ClearFinished(ClearResult.CLEARED))
        assertEquals(0, finished.clearConfirmationStep)
        assertEquals(ClearResult.CLEARED, finished.clearResult)
    }

    @Test fun `clear confirmation cannot skip first step`() {
        assertEquals(0, PrivacyUiState().reduce(PrivacyEvent.ConfirmClear).clearConfirmationStep)
    }
}
