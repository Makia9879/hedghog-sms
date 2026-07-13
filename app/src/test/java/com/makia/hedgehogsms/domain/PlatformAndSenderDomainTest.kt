package com.makia.hedgehogsms.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformAndSenderDomainTest {
    @Test fun `rename and merge preserve aliases aggregate history and never forget statistics`() {
        val labels = PlatformLabelGovernance()
        val canonical = labels.create(1, "虚构甲平台")
        val duplicate = labels.create(2, "虚构甲旧称")
        labels.recordHumanSample(10, canonical.id, featureCount = 4)
        labels.recordHumanSample(11, duplicate.id, featureCount = 7)
        labels.rename(canonical.id, "虚构甲")
        labels.merge(duplicate.id, canonical.id)

        assertEquals("虚构甲", labels.label(canonical.id).displayName)
        assertEquals(canonical.id, labels.canonicalId(duplicate.id))
        assertEquals("虚构甲旧称", labels.aliases(canonical.id).single().displayName)
        assertEquals(setOf(10L, 11L), labels.samplesFor(canonical.id))
        assertEquals(ModelSufficientStats(2, 11), labels.modelStats(canonical.id))
    }

    @Test fun `split is only a sequence of explicit per sample human relabels`() {
        val labels = PlatformLabelGovernance()
        labels.create(1, "虚构合并平台")
        labels.create(2, "虚构拆分平台")
        labels.recordHumanSample(10, 1, 3)
        labels.recordHumanSample(11, 1, 5)
        assertThrows(IllegalStateException::class.java) { labels.automaticSplit(1, 2) }
        labels.relabelByHuman(10, 1, 2)
        assertEquals(setOf(11L), labels.samplesFor(1))
        assertEquals(setOf(10L), labels.samplesFor(2))
        assertEquals(ModelSufficientStats(1, 5), labels.modelStats(1))
        assertEquals(ModelSufficientStats(1, 3), labels.modelStats(2))
    }

    @Test fun `sender evidence follows two three five and conflict thresholds for every sender kind`() {
        SenderKind.entries.forEach { kind ->
            val evidence = SenderEvidence(kind)
            evidence.confirm(1, LocalDate.of(2030, 1, 1))
            assertEquals(SenderStrength.NONE, evidence.assess().strength)
            evidence.confirm(1, LocalDate.of(2030, 1, 1))
            assertEquals(SenderStrength.WEAK, evidence.assess().strength)
            evidence.confirm(1, LocalDate.of(2030, 1, 2))
            assertEquals(SenderStrength.CANDIDATE, evidence.assess().strength)
            evidence.confirm(1, LocalDate.of(2030, 1, 3))
            evidence.confirm(1, LocalDate.of(2030, 1, 3))
            assertEquals(SenderStrength.STRONG_SUPPORT, evidence.assess().strength)
            assertFalse(evidence.assess().canAutoConfirm)
            evidence.confirm(2, LocalDate.of(2030, 1, 4))
            assertEquals(SenderStrength.CONFLICT, evidence.assess().strength)
        }
    }

    @Test fun `claim kinds constrain platform choice and never authorize training`() {
        assertFalse(SenderClaim(ClaimKind.CARRIER, "虚构运营商").eligibleForPlatformChoice)
        assertFalse(SenderClaim(ClaimKind.CHANNEL_OWNER, "虚构通道").eligibleForPlatformChoice)
        val candidate = SenderClaim(ClaimKind.PLATFORM_CANDIDATE, "虚构候选")
        assertTrue(candidate.eligibleForPlatformChoice)
        assertFalse(candidate.canAutoConfirmOrTrain)
    }
}
