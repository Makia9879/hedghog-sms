package com.makia.hedgehogsms.classification

import kotlin.math.exp
import kotlin.math.ln

enum class ConfidenceMode(val posterior: Double, val margin: Double, val minimumSamples: Int) {
    CONSERVATIVE(0.95, 0.50, 3),
    BALANCED(0.85, 0.30, 2),
    AUTOMATIC(0.70, 0.15, 2),
}

data class LabelCandidate(val labelId: Long, val posterior: Double, val confirmedSamples: Int)
data class Prediction(val candidates: List<LabelCandidate>, val autoAccepted: Boolean) {
    val best: LabelCandidate? get() = candidates.firstOrNull()
}
data class ClassSnapshot(val documentCount: Int, val featureCount: Int, val buckets: Map<Int, Int>)
data class ModelSnapshot(val schemaVersion: Int, val classes: Map<Long, ClassSnapshot>)

class OnlineMultinomialNaiveBayes(
    private val alpha: Double = 1.0,
    private val schemaVersion: Int = 1,
) {
    private data class MutableClass(var documents: Int = 0, var total: Int = 0, val buckets: MutableMap<Int, Int> = mutableMapOf())
    private data class Sample(val labelId: Long, val features: SparseFeatures)
    private val classes = mutableMapOf<Long, MutableClass>()
    private val samples = mutableMapOf<Long, Sample>()

    fun learn(sampleId: Long, labelId: Long, features: SparseFeatures) {
        require(sampleId !in samples) { "sample already learned" }
        add(labelId, features)
        samples[sampleId] = Sample(labelId, features)
    }

    fun correct(sampleId: Long, oldLabelId: Long, newLabelId: Long) {
        val sample = requireNotNull(samples[sampleId]) { "unknown sample" }
        require(sample.labelId == oldLabelId) { "sample label mismatch" }
        subtract(oldLabelId, sample.features)
        add(newLabelId, sample.features)
        samples[sampleId] = sample.copy(labelId = newLabelId)
    }

    fun predict(features: SparseFeatures, mode: ConfidenceMode = ConfidenceMode.BALANCED): Prediction {
        return predict(snapshot(), features, mode, alpha)
    }

    companion object {
    private const val BUCKET_COUNT = 16_384.0
    fun predict(snapshot: ModelSnapshot, features: SparseFeatures, mode: ConfidenceMode = ConfidenceMode.BALANCED, alpha: Double = 1.0): Prediction {
        val active = snapshot.classes.filterValues { it.documentCount > 0 }
        if (active.isEmpty()) return Prediction(emptyList(), false)
        val documents = active.values.sumOf { it.documentCount }.toDouble()
        val logs = active.map { (label, stats) ->
            var score = ln(stats.documentCount / documents)
            val denominator = stats.featureCount + alpha * BUCKET_COUNT
            features.buckets.forEach { (bucket, count) ->
                score += count * ln(((stats.buckets[bucket] ?: 0) + alpha) / denominator)
            }
            Triple(label, score, stats.documentCount)
        }
        val max = logs.maxOf { it.second }
        val normalizer = logs.sumOf { exp(it.second - max) }
        val ranked = logs.map { LabelCandidate(it.first, exp(it.second - max) / normalizer, it.third) }
            .sortedByDescending { it.posterior }
        val best = ranked.first()
        val margin = best.posterior - (ranked.getOrNull(1)?.posterior ?: 0.0)
        val accepted = ranked.size >= 2 && best.confirmedSamples >= mode.minimumSamples &&
            best.posterior >= mode.posterior && margin >= mode.margin
        return Prediction(ranked, accepted)
    }
    }

    fun snapshot() = ModelSnapshot(schemaVersion, classes.mapValues { (_, value) ->
        ClassSnapshot(value.documents, value.total, value.buckets.toMap())
    })

    private fun add(labelId: Long, features: SparseFeatures) {
        val stats = classes.getOrPut(labelId) { MutableClass() }
        stats.documents++
        features.buckets.forEach { (bucket, count) ->
            require(count >= 0)
            stats.buckets[bucket] = (stats.buckets[bucket] ?: 0) + count
            stats.total += count
        }
    }

    private fun subtract(labelId: Long, features: SparseFeatures) {
        val stats = requireNotNull(classes[labelId])
        require(stats.documents > 0)
        stats.documents--
        features.buckets.forEach { (bucket, count) ->
            val remaining = (stats.buckets[bucket] ?: 0) - count
            require(remaining >= 0) { "feature count would become negative" }
            if (remaining == 0) stats.buckets.remove(bucket) else stats.buckets[bucket] = remaining
            stats.total -= count
        }
        check(stats.total >= 0)
    }

}
