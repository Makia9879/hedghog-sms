package com.makia.hedgehogsms.ui.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformUiContractTest {
    private val bankLabel = LabelChoiceUi(
        labelId = 100,
        platformKey = "bank",
        displayName = "Bank",
    )

    @Test fun `platform summary card model only exposes approved facts`() {
        val fieldNames = PlatformSummaryUi::class.java.declaredFields.map { it.name }.toSet()

        assertTrue(fieldNames.containsAll(setOf("id", "name", "verificationCodeCount", "latestAtText")))
        assertFalse(fieldNames.contains("simAndSlotsText"))
        assertFalse(fieldNames.contains("confidenceText"))
    }

    @Test fun `pending candidate keeps search separate from explicit submit state`() {
        val candidate = PendingCandidateUi(
            messageId = 1,
            suggestedPlatform = null,
            explanation = "redacted body",
            existingLabels = listOf(bankLabel),
            labelSearchText = "bank",
            selectedLabel = bankLabel,
            submitInProgress = false,
        )

        assertTrue(candidate.labelSearchText == "bank")
        assertFalse(candidate.submitInProgress)
        assertTrue(candidate.canCreateLabel)
        assertTrue(candidate.canSubmit)
    }

    @Test fun `pending candidate disables actions while submitting`() {
        val candidate = PendingCandidateUi(
            messageId = 1,
            suggestedPlatform = null,
            explanation = "redacted body",
            existingLabels = listOf(bankLabel),
            labelSearchText = "bank",
            selectedLabel = bankLabel,
            submitInProgress = true,
        )

        assertFalse(candidate.canCreateLabel)
        assertFalse(candidate.canSubmit)
    }

    @Test fun `pending candidate failure keeps search and selection`() {
        val candidate = PendingCandidateUi(
            messageId = 1,
            suggestedPlatform = null,
            explanation = "redacted body",
            existingLabels = listOf(bankLabel),
            labelSearchText = "bank",
            selectedLabel = bankLabel,
            submitError = "绑定训练失败，请重试",
        )

        assertEquals("bank", candidate.labelSearchText)
        assertEquals(bankLabel, candidate.selectedLabel)
        assertEquals("绑定训练失败，请重试", candidate.submitError)
        assertTrue(candidate.canSubmit)
    }

    @Test fun `label choices carry binding identity triad`() {
        assertEquals(100L, bankLabel.labelId)
        assertEquals("bank", bankLabel.platformKey)
        assertEquals("Bank", bankLabel.displayName)
    }

    @Test fun `pending labels are independent from filtered platform overview`() {
        val state = PlatformScreensUiState(
            platforms = emptyList(),
            pendingCandidate = PendingCandidateUi(
                messageId = 1,
                suggestedPlatform = null,
                explanation = "redacted body",
                existingLabels = listOf(bankLabel),
            ),
        )

        assertTrue(state.platforms.isEmpty())
        assertEquals(listOf(bankLabel), state.pendingCandidate?.existingLabels)
    }

    @Test fun `platform evidence permission state carries no evidence messages`() {
        val state = PlatformScreensUiState(
            navigation = PlatformNavigationState(
                destination = PrimaryDestination.PLATFORMS,
                selectedPlatformId = "bank",
            ),
            selectedPlatformName = "Bank",
            selectedPlatformEvidence = emptyList(),
            platformEvidencePermissionUnavailable = true,
        )

        assertTrue(state.platformEvidencePermissionUnavailable)
        assertTrue(state.selectedPlatformEvidence.isEmpty())
    }

    @Test fun `platform evidence loading state is not treated as empty evidence`() {
        val state = PlatformScreensUiState(
            navigation = PlatformNavigationState(
                destination = PrimaryDestination.PLATFORMS,
                selectedPlatformId = "bank",
            ),
            selectedPlatformName = "Bank",
            selectedPlatformEvidence = emptyList(),
            platformEvidenceLoading = true,
        )

        assertTrue(state.platformEvidenceLoading)
        assertFalse(state.platformEvidencePermissionUnavailable)
        assertEquals(null, state.platformEvidenceErrorText)
        assertTrue(state.selectedPlatformEvidence.isEmpty())
    }

    @Test fun `platform evidence error state carries no evidence messages`() {
        val state = PlatformScreensUiState(
            navigation = PlatformNavigationState(
                destination = PrimaryDestination.PLATFORMS,
                selectedPlatformId = "bank",
            ),
            selectedPlatformName = "Bank",
            selectedPlatformEvidence = emptyList(),
            platformEvidenceErrorText = "证据短信读取失败，请重试",
        )

        assertEquals("证据短信读取失败，请重试", state.platformEvidenceErrorText)
        assertTrue(state.selectedPlatformEvidence.isEmpty())
    }

    @Test fun `slot detail can expose indexed messages without platform summaries`() {
        val message = EvidenceMessageUi(
            messageId = 10,
            senderText = "Bank",
            receivedAtText = "2026-07-17 10:00",
            simAndSlotText = "卡槽 1",
        )
        val state = PlatformScreensUiState(
            navigation = PlatformNavigationState(
                destination = PrimaryDestination.SLOTS,
                selectedSlot = com.makia.hedgehogsms.classification.PlatformSlotFilter.SLOT_1,
            ),
            platforms = emptyList(),
            selectedSlotMessages = listOf(message),
            slots = listOf(
                SlotCardUi(
                    com.makia.hedgehogsms.classification.PlatformSlotFilter.SLOT_1,
                    "卡槽 1",
                    1,
                ),
            ),
        )

        assertTrue(state.platforms.isEmpty())
        assertEquals(listOf(message), state.selectedSlotMessages)
        assertEquals(1, state.slots.single().smsCount)
    }

    @Test fun `slot detail permission state carries no message bodies`() {
        val state = PlatformScreensUiState(
            navigation = PlatformNavigationState(
                destination = PrimaryDestination.SLOTS,
                selectedSlot = com.makia.hedgehogsms.classification.PlatformSlotFilter.UNKNOWN,
            ),
            selectedSlotMessages = emptyList(),
            slotDetailPermissionUnavailable = true,
        )

        assertTrue(state.slotDetailPermissionUnavailable)
        assertTrue(state.selectedSlotMessages.isEmpty())
    }

    @Test fun `pending permission state carries no pending message body`() {
        val state = PlatformScreensUiState(
            navigation = PlatformNavigationState(destination = PrimaryDestination.PLATFORMS, pendingOpen = true),
            pendingCandidate = null,
            pendingPermissionUnavailable = true,
        )

        assertTrue(state.pendingPermissionUnavailable)
        assertEquals(null, state.pendingCandidate)
    }
}
