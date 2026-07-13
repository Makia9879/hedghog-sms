package com.makia.hedgehogsms.llm

import java.net.URI
import java.time.Instant
import java.util.UUID

data class ProviderConfig(val baseUrl: String, val model: String) {
    val host: String
    init {
        val uri = runCatching { URI(baseUrl) }.getOrElse { throw IllegalArgumentException("invalid provider URL") }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) { "provider URL must use HTTPS" }
        require(uri.userInfo == null && uri.fragment == null) { "provider URL must not contain credentials or fragments" }
        require(model.isNotBlank()) { "model must not be blank" }
        host = uri.host
    }
}

interface AssistedLabelProvider {
    suspend fun suggest(config: ProviderConfig, batch: ApprovedRedactedBatch, apiKeyHandle: ApiKeyHandle): List<Annotation>
}

interface ApiKeyVault {
    fun store(providerHost: String, secret: CharArray): ApiKeyHandle
    suspend fun <T> withSecret(handle: ApiKeyHandle, expectedProviderHost: String, block: suspend (CharArray) -> T): T
    fun delete(handle: ApiKeyHandle)
    fun clearAll()
}

@JvmInline value class ApiKeyHandle(val opaqueId: String)

data class RedactedSampleDraft(val text: String) {
    init {
        require(text.isNotBlank())
        require(!FORBIDDEN_PHONE.containsMatchIn(text)) { "payload contains a phone-like value" }
        require(!FORBIDDEN_CODE.containsMatchIn(text)) { "payload contains an unredacted code-like value" }
        require(!FORBIDDEN_URL_QUERY.containsMatchIn(text)) { "payload contains URL parameters" }
    }
    private companion object {
        val FORBIDDEN_PHONE = Regex("(?<!\\d)(?:\\+?86[- ]?)?1\\d{10}(?!\\d)")
        val FORBIDDEN_CODE = Regex("(?<![A-Za-z0-9])(?=[A-Za-z0-9]{4,8}(?![A-Za-z0-9]))(?=[A-Za-z0-9]*\\d)[A-Za-z0-9]+")
        val FORBIDDEN_URL_QUERY = Regex("https?://\\S+\\?[^\\s>]+", RegexOption.IGNORE_CASE)
    }
}

data class RedactedSample(val opaqueId: String, val text: String)
data class ApprovedRedactedBatch(val opaqueBatchId: String, val providerHost: String, val model: String, val samples: List<RedactedSample>)

sealed interface LabelSuggestion {
    data class Existing(val labelId: Long) : LabelSuggestion
    data class New(val displayName: String) : LabelSuggestion { init { require(displayName.isNotBlank()) } }
    data object Unknown : LabelSuggestion
}

data class Annotation(val sampleId: String, val suggestion: LabelSuggestion, val confidence: Double) {
    init { require(confidence in 0.0..1.0) }
}

enum class ConfirmationPolicy { MANUAL, AUTOMATIC }
enum class BatchState { DRAFT, PREVIEWED, USER_APPROVED, SENT, ANSWERED, PENDING_CONFIRMATION, AUTO_ACCEPTED, USER_ACCEPTED, TRAINED, CANCELED }
data class HarnessQuota(val maxSamples: Int = 100, val maxAnnotations: Int = 100, val maxToolCalls: Int = 12)
data class TrainingCommand(val sampleId: String, val suggestion: LabelSuggestion)
class HarnessDenied(message: String) : IllegalStateException(message)

class RestrictedLlmHarness(
    private val quota: HarnessQuota = HarnessQuota(),
    private val clock: () -> Instant = Instant::now,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    data class BatchRef(val id: String)
    private data class Batch(
        val id: String,
        val provider: ProviderConfig,
        val samples: List<RedactedSample>,
        val policy: ConfirmationPolicy,
        val expiresAt: Instant,
        var state: BatchState = BatchState.DRAFT,
        var toolCalls: Int = 0,
        var annotations: List<Annotation> = emptyList(),
        var acceptedIds: Set<String> = emptySet(),
    )
    private val batches = mutableMapOf<String, Batch>()

    fun createBatch(provider: ProviderConfig, drafts: List<RedactedSampleDraft>, policy: ConfirmationPolicy, expiresAt: Instant): BatchRef {
        require(drafts.isNotEmpty() && drafts.size <= quota.maxSamples)
        require(expiresAt.isAfter(clock()))
        val id = idGenerator()
        check(id !in batches)
        val samples = drafts.map { RedactedSample(idGenerator(), it.text) }
        require(samples.map { it.opaqueId }.toSet().size == samples.size)
        batches[id] = Batch(id, provider, samples, policy, expiresAt)
        return BatchRef(id)
    }

    fun preview(batchId: String) = transition(batchId, BatchState.DRAFT, BatchState.PREVIEWED)
    fun approve(batchId: String) = transition(batchId, BatchState.PREVIEWED, BatchState.USER_APPROVED)
    fun markSent(batchId: String) = transition(batchId, BatchState.USER_APPROVED, BatchState.SENT)
    fun cancel(batchId: String) {
        val batch = batch(batchId)
        if (batch.state !in setOf(BatchState.DRAFT, BatchState.PREVIEWED, BatchState.USER_APPROVED)) throw HarnessDenied("batch cannot be canceled")
        batch.state = BatchState.CANCELED
    }

    fun getSamples(batchId: String): List<RedactedSample> {
        val batch = approved(batchId)
        consume(batch)
        return batch.samples.map { it.copy() }
    }

    fun submitAnswer(batchId: String, annotations: List<Annotation>) {
        val batch = approved(batchId)
        if (batch.state !in setOf(BatchState.USER_APPROVED, BatchState.SENT)) throw HarnessDenied("answers not allowed in current state")
        if (annotations.isEmpty() || annotations.size > quota.maxAnnotations) throw HarnessDenied("annotation quota exceeded")
        val ids = annotations.map { it.sampleId }
        if (ids.size != ids.toSet().size || !batch.samples.map { it.opaqueId }.toSet().containsAll(ids)) throw HarnessDenied("sample outside approved batch")
        consume(batch)
        batch.annotations = annotations.toList()
        batch.state = BatchState.ANSWERED
        if (batch.policy == ConfirmationPolicy.MANUAL) {
            batch.state = BatchState.PENDING_CONFIRMATION
        } else {
            batch.acceptedIds = annotations.filter { it.confidence >= AUTO_CONFIDENCE }.map { it.sampleId }.toSet()
            batch.state = BatchState.AUTO_ACCEPTED
        }
    }

    fun userAccept(batchId: String, sampleIds: Set<String>) {
        val batch = batch(batchId)
        ensureLive(batch)
        if (batch.state != BatchState.PENDING_CONFIRMATION) throw HarnessDenied("batch is not awaiting confirmation")
        if (!batch.annotations.map { it.sampleId }.toSet().containsAll(sampleIds)) throw HarnessDenied("sample outside answered batch")
        batch.acceptedIds = sampleIds.toSet()
        batch.state = BatchState.USER_ACCEPTED
    }

    fun trainLocalModel(batchId: String): List<TrainingCommand> {
        val batch = batch(batchId)
        ensureLive(batch)
        if (batch.state !in setOf(BatchState.USER_ACCEPTED, BatchState.AUTO_ACCEPTED)) throw HarnessDenied("batch is not accepted for training")
        consume(batch)
        val commands = batch.annotations.filter { it.sampleId in batch.acceptedIds && it.suggestion !is LabelSuggestion.Unknown }
            .map { TrainingCommand(it.sampleId, it.suggestion) }
        batch.state = BatchState.TRAINED
        return commands
    }

    fun state(batchId: String): BatchState = batch(batchId).state

    private fun approved(batchId: String): Batch {
        val batch = batch(batchId)
        ensureLive(batch)
        if (batch.state !in setOf(BatchState.USER_APPROVED, BatchState.SENT, BatchState.ANSWERED, BatchState.PENDING_CONFIRMATION, BatchState.AUTO_ACCEPTED, BatchState.USER_ACCEPTED)) {
            throw HarnessDenied("batch is not user approved")
        }
        return batch
    }

    private fun transition(batchId: String, from: BatchState, to: BatchState) {
        val batch = batch(batchId)
        ensureLive(batch)
        if (batch.state != from) throw HarnessDenied("invalid batch transition")
        batch.state = to
    }

    private fun consume(batch: Batch) {
        if (batch.toolCalls >= quota.maxToolCalls) throw HarnessDenied("tool quota exceeded")
        batch.toolCalls++
    }

    private fun ensureLive(batch: Batch) {
        if (!clock().isBefore(batch.expiresAt)) {
            batch.state = BatchState.CANCELED
            throw HarnessDenied("batch expired")
        }
        if (batch.state == BatchState.CANCELED) throw HarnessDenied("batch canceled")
    }

    private fun batch(id: String) = batches[id] ?: throw HarnessDenied("unknown opaque batch")
    private companion object { const val AUTO_CONFIDENCE = 0.85 }
}
