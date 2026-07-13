package com.makia.hedgehogsms.sim

import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class SimEvidence(
    val subscriptionId: Long,
    val slotIndex: Int,
    val stableSystemToken: String? = null,
    val phoneNumber: String? = null,
    val carrierId: Int? = null,
    val mccMnc: String? = null,
)

@JvmInline value class SimFingerprint(val value: String)

data class SimCollection(
    val id: String,
    val displayName: String,
    val fingerprint: SimFingerprint?,
    val currentSubscriptionId: Long?,
    val currentSlotIndex: Int?,
)

data class HistoricalSlotSnapshot(
    val messageId: Long,
    val simCollectionId: String?,
    val slotIndexAtScan: Int?,
)

sealed interface SimResolution {
    data class Resolved(val collection: SimCollection) : SimResolution
    data class NeedsUserDecision(
        val decisionId: String,
        val suggestedName: String,
        val possibleExisting: List<SimCollection>,
    ) : SimResolution
}

sealed interface UserSimDecision {
    val decisionId: String
    data class ThisIsOld(override val decisionId: String, val collectionId: String) : UserSimDecision
    data class ThisIsNew(override val decisionId: String, val displayName: String? = null) : UserSimDecision
}

fun interface SimFingerprintCandidate {
    fun candidate(evidence: SimEvidence): SimFingerprint?
}

class HmacSimFingerprintCandidate(private val key: ByteArray) : SimFingerprintCandidate {
    override fun candidate(evidence: SimEvidence): SimFingerprint? {
        val stable = evidence.stableSystemToken?.trim()?.takeIf(String::isNotEmpty)
        val number = evidence.phoneNumber?.filter(Char::isDigit)?.takeIf(String::isNotEmpty)
        val identity = when {
            stable != null -> "system:$stable"
            number != null -> "phone:$number"
            else -> return null
        }
        val context = listOfNotNull(evidence.carrierId?.let { "carrier:$it" }, evidence.mccMnc?.let { "mccmnc:$it" })
            .joinToString("|")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.copyOf(), "HmacSHA256"))
        val bytes = mac.doFinal("$identity|$context".toByteArray(StandardCharsets.UTF_8))
        return SimFingerprint(bytes.joinToString("") { "%02x".format(it) })
    }
}

interface SimCollectionRepository {
    fun all(): List<SimCollection>
    fun findById(id: String): SimCollection?
    fun findByFingerprint(fingerprint: SimFingerprint): SimCollection?
    fun save(collection: SimCollection)
}

class InMemorySimCollectionRepository : SimCollectionRepository {
    private val values = linkedMapOf<String, SimCollection>()
    override fun all(): List<SimCollection> = values.values.toList()
    override fun findById(id: String): SimCollection? = values[id]
    override fun findByFingerprint(fingerprint: SimFingerprint): SimCollection? = values.values.firstOrNull { it.fingerprint == fingerprint }
    override fun save(collection: SimCollection) { values[collection.id] = collection }
}

class SimCollectionResolver(
    private val repository: SimCollectionRepository,
    private val fingerprints: SimFingerprintCandidate,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val pending = mutableMapOf<String, PendingEvidence>()

    fun resolve(evidence: SimEvidence): SimResolution {
        val fingerprint = fingerprints.candidate(evidence)
        val existing = fingerprint?.let(repository::findByFingerprint)
        if (existing != null) return SimResolution.Resolved(updateCurrentPosition(existing, evidence))

        val decisionId = idFactory()
        pending[decisionId] = PendingEvidence(evidence, fingerprint)
        return SimResolution.NeedsUserDecision(
            decisionId = decisionId,
            suggestedName = "卡槽 ${evidence.slotIndex + 1}",
            possibleExisting = repository.all(),
        )
    }

    fun confirm(decision: UserSimDecision): SimCollection {
        val pendingEvidence = pending.remove(decision.decisionId) ?: error("Unknown or already completed SIM decision")
        return when (decision) {
            is UserSimDecision.ThisIsOld -> {
                val existing = repository.findById(decision.collectionId) ?: error("Unknown SIM collection")
                val withFingerprint = if (existing.fingerprint == null && pendingEvidence.fingerprint != null) {
                    existing.copy(fingerprint = pendingEvidence.fingerprint)
                } else existing
                updateCurrentPosition(withFingerprint, pendingEvidence.evidence)
            }
            is UserSimDecision.ThisIsNew -> {
                val created = SimCollection(
                    id = idFactory(),
                    displayName = decision.displayName?.trim()?.takeIf(String::isNotEmpty)
                        ?: "卡槽 ${pendingEvidence.evidence.slotIndex + 1}",
                    fingerprint = pendingEvidence.fingerprint,
                    currentSubscriptionId = pendingEvidence.evidence.subscriptionId,
                    currentSlotIndex = pendingEvidence.evidence.slotIndex,
                )
                repository.save(created)
                created
            }
        }
    }

    private fun updateCurrentPosition(collection: SimCollection, evidence: SimEvidence): SimCollection =
        collection.copy(currentSubscriptionId = evidence.subscriptionId, currentSlotIndex = evidence.slotIndex)
            .also(repository::save)

    private data class PendingEvidence(val evidence: SimEvidence, val fingerprint: SimFingerprint?)
}
