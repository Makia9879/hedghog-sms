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

    @Test fun `slot detail exposes platform summaries instead of indexed messages`() {
        val platform = PlatformSummaryUi(
            id = "bank",
            name = "Bank",
            verificationCodeCount = 3,
            latestAtText = "2026-07-17 10:00",
        )
        val state = PlatformScreensUiState(
            navigation = PlatformNavigationState(
                destination = PrimaryDestination.SLOTS,
                selectedSlot = com.makia.hedgehogsms.classification.PlatformSlotFilter.SLOT_1,
            ),
            platforms = emptyList(),
            selectedSlotPlatforms = listOf(platform),
            slots = listOf(
                SlotCardUi(
                    com.makia.hedgehogsms.classification.PlatformSlotFilter.SLOT_1,
                    "卡槽 1",
                    3,
                ),
            ),
        )

        assertTrue(state.platforms.isEmpty())
        assertEquals(listOf(platform), state.selectedSlotPlatforms)
        assertEquals(3, state.slots.single().smsCount)
    }

    @Test fun `slot detail permission state carries no message bodies or platform evidence`() {
        val state = PlatformScreensUiState(
            navigation = PlatformNavigationState(
                destination = PrimaryDestination.SLOTS,
                selectedSlot = com.makia.hedgehogsms.classification.PlatformSlotFilter.UNKNOWN,
            ),
            selectedSlotPlatforms = emptyList(),
            slotDetailPermissionUnavailable = true,
        )

        assertTrue(state.slotDetailPermissionUnavailable)
        assertTrue(state.selectedSlotPlatforms.isEmpty())
    }

    @Test fun `platform summary keyword filter matches display name and platform key`() {
        val bank = PlatformSummaryUi("bank-key", "Bank", 1, "now")
        val shop = PlatformSummaryUi("shop-key", "Shop", 1, "now")

        assertEquals(listOf(bank, shop), filterPlatformSummaries(listOf(bank, shop), ""))
        assertEquals(listOf(bank), filterPlatformSummaries(listOf(bank, shop), "ban"))
        assertEquals(listOf(shop), filterPlatformSummaries(listOf(bank, shop), "SHOP-KEY"))
        assertEquals(emptyList<PlatformSummaryUi>(), filterPlatformSummaries(listOf(bank, shop), "missing"))
    }

    @Test fun `managed label keyword filter matches label name and stable id`() {
        val bank = ManagedLabelUi("bank-key", "Bank", "", "旧名：Bank Old")
        val shop = ManagedLabelUi("shop-key", "Shop", "", "样本 3 条")

        assertEquals(listOf(bank, shop), filterManagedLabels(listOf(bank, shop), ""))
        assertEquals(listOf(bank), filterManagedLabels(listOf(bank, shop), "ban"))
        assertEquals(listOf(shop), filterManagedLabels(listOf(bank, shop), "SHOP-KEY"))
        assertEquals(emptyList<ManagedLabelUi>(), filterManagedLabels(listOf(bank, shop), "missing"))
    }

    @Test fun `label list item text only contains display name`() {
        val label = ManagedLabelUi("bank-key", "Bank", "", "旧名：Bank Old · 样本 3 条")

        assertEquals("Bank", labelListItemText(label))
    }

    @Test fun `bottom navigation height is ten percent of screen height`() {
        assertEquals(84f, bottomNavigationHeightDp(840), 0.001f)
        assertEquals(72.8f, bottomNavigationHeightDp(728), 0.001f)
    }

    @Test fun `bottom navigation is hidden while input method is visible`() {
        val rootState = PlatformNavigationState()
        val detailState = PlatformNavigationState(selectedPlatformId = "bank")

        assertTrue(shouldShowBottomNavigation(rootState, imeVisible = false))
        assertFalse(shouldShowBottomNavigation(rootState, imeVisible = true))
        assertFalse(shouldShowBottomNavigation(detailState, imeVisible = false))
    }

    @Test fun `search field requests keyboard only when focused and enabled`() {
        assertTrue(shouldRequestKeyboardOnFocus(hasFocus = true, enabled = true))
        assertFalse(shouldRequestKeyboardOnFocus(hasFocus = false, enabled = true))
        assertFalse(shouldRequestKeyboardOnFocus(hasFocus = true, enabled = false))
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
