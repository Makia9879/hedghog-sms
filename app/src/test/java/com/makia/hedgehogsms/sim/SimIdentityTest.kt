package com.makia.hedgehogsms.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimIdentityTest {
    private val key = "test-only-hmac-key".toByteArray()

    @Test fun `B2 no phone number is usable when stable system evidence exists`() {
        val candidate = HmacSimFingerprintCandidate(key)
        val withoutNumber = candidate.candidate(SimEvidence(1, 0, stableSystemToken = "icc-hash-source", phoneNumber = null, carrierId = 7))
        val sameAgain = candidate.candidate(SimEvidence(99, 1, stableSystemToken = "icc-hash-source", phoneNumber = null, carrierId = 7))
        assertEquals(withoutNumber, sameAgain)
    }

    @Test fun `B2 weak carrier evidence alone requires user decision`() {
        val resolver = resolver()
        val result = resolver.resolve(SimEvidence(1, 0, carrierId = 7, mccMnc = "46000"))
        assertTrue(result is SimResolution.NeedsUserDecision)
    }

    @Test fun `B3 user can confirm old or new collection after insufficient evidence`() {
        val repository = InMemorySimCollectionRepository()
        val ids = ArrayDeque(listOf("old-decision", "old-id", "new-decision", "new-id"))
        val resolver = SimCollectionResolver(repository, HmacSimFingerprintCandidate(key)) { ids.removeFirst() }

        val first = resolver.resolve(SimEvidence(1, 0)) as SimResolution.NeedsUserDecision
        val old = resolver.confirm(UserSimDecision.ThisIsNew(first.decisionId, "工作卡"))
        assertEquals("工作卡", old.displayName)
        assertNull(old.fingerprint)

        val second = resolver.resolve(SimEvidence(2, 1)) as SimResolution.NeedsUserDecision
        assertEquals(listOf(old.id), second.possibleExisting.map { it.id })
        val restored = resolver.confirm(UserSimDecision.ThisIsOld(second.decisionId, old.id))
        assertEquals(old.id, restored.id)
        assertEquals(1, restored.currentSlotIndex)
    }

    @Test fun `B4 same SIM restores across subscription and slot changes without rewriting history`() {
        val repository = InMemorySimCollectionRepository()
        val ids = ArrayDeque(listOf("decision", "collection"))
        val resolver = SimCollectionResolver(repository, HmacSimFingerprintCandidate(key)) { ids.removeFirst() }
        val evidence = SimEvidence(10, 0, stableSystemToken = "same-card", carrierId = 7)
        val pending = resolver.resolve(evidence) as SimResolution.NeedsUserDecision
        val created = resolver.confirm(UserSimDecision.ThisIsNew(pending.decisionId))
        val history = HistoricalSlotSnapshot(42, created.id, slotIndexAtScan = 0)

        val moved = resolver.resolve(evidence.copy(subscriptionId = 88, slotIndex = 1)) as SimResolution.Resolved
        assertEquals(created.id, moved.collection.id)
        assertEquals(88L, moved.collection.currentSubscriptionId)
        assertEquals(1, moved.collection.currentSlotIndex)
        assertEquals(0, history.slotIndexAtScan)
    }

    @Test fun `different stable system evidence yields different local fingerprints`() {
        val candidate = HmacSimFingerprintCandidate(key)
        assertNotEquals(
            candidate.candidate(SimEvidence(1, 0, stableSystemToken = "card-a")),
            candidate.candidate(SimEvidence(2, 1, stableSystemToken = "card-b")),
        )
    }

    private fun resolver() = SimCollectionResolver(
        InMemorySimCollectionRepository(),
        HmacSimFingerprintCandidate(key),
        idFactory = { "decision-${System.nanoTime()}" },
    )
}
