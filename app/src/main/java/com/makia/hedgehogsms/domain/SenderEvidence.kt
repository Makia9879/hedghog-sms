package com.makia.hedgehogsms.domain

import java.time.LocalDate

enum class SenderKind { NORMAL_NUMBER, SHORT_CODE_106, ALPHANUMERIC_ID }
enum class SenderStrength { NONE, WEAK, CANDIDATE, STRONG_SUPPORT, CONFLICT }

data class SenderAssessment(
    val strength: SenderStrength,
    val candidateLabelId: Long?,
    val confirmedCount: Int,
    val distinctDays: Int,
    val canAutoConfirm: Boolean = false,
)

class SenderEvidence(val kind: SenderKind) {
    private data class Confirmation(val labelId: Long, val day: LocalDate)
    private val confirmations = mutableListOf<Confirmation>()

    fun confirm(canonicalLabelId: Long, day: LocalDate) {
        confirmations += Confirmation(canonicalLabelId, day)
    }

    fun assess(): SenderAssessment {
        if (confirmations.isEmpty()) return SenderAssessment(SenderStrength.NONE, null, 0, 0)
        val counts = confirmations.groupingBy { it.labelId }.eachCount()
        val dominant = counts.maxBy { it.value }
        val days = confirmations.map { it.day }.toSet().size
        if (counts.size > 1) return SenderAssessment(SenderStrength.CONFLICT, null, confirmations.size, days)
        val ratio = dominant.value.toDouble() / confirmations.size
        val strength = when {
            confirmations.size >= 5 && days >= 3 && ratio >= 0.95 -> SenderStrength.STRONG_SUPPORT
            confirmations.size >= 3 && days >= 2 -> SenderStrength.CANDIDATE
            confirmations.size >= 2 -> SenderStrength.WEAK
            else -> SenderStrength.NONE
        }
        return SenderAssessment(strength, dominant.key.takeIf { strength != SenderStrength.NONE }, confirmations.size, days)
    }
}

enum class ClaimKind { CARRIER, CHANNEL_OWNER, PLATFORM_CANDIDATE }

data class SenderClaim(val kind: ClaimKind, val displayName: String) {
    init { require(displayName.isNotBlank()) }
    val eligibleForPlatformChoice: Boolean get() = kind == ClaimKind.PLATFORM_CANDIDATE
    val canAutoConfirmOrTrain: Boolean get() = false
}
