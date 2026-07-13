package com.makia.hedgehogsms.domain

data class PlatformLabel(val id: Long, val displayName: String, val active: Boolean = true)
data class PlatformAlias(val sourceLabelId: Long, val canonicalLabelId: Long, val displayName: String)
data class ModelSufficientStats(val documentCount: Int, val featureCount: Int)

class PlatformLabelGovernance {
    private data class Sample(var labelId: Long, val featureCount: Int)
    private val labels = mutableMapOf<Long, PlatformLabel>()
    private val aliasBySource = mutableMapOf<Long, PlatformAlias>()
    private val samples = mutableMapOf<Long, Sample>()

    fun create(id: Long, displayName: String): PlatformLabel {
        require(id !in labels && displayName.isNotBlank())
        return PlatformLabel(id, displayName.trim()).also { labels[id] = it }
    }

    fun rename(id: Long, displayName: String) {
        require(displayName.isNotBlank())
        val canonical = canonicalId(id)
        labels[canonical] = label(canonical).copy(displayName = displayName.trim())
    }

    fun merge(sourceId: Long, targetId: Long) {
        val source = canonicalId(sourceId)
        val target = canonicalId(targetId)
        require(source != target)
        val sourceLabel = label(source)
        require(label(target).active)
        val sourceSamples = samples.values.filter { canonicalId(it.labelId) == source }
        val inheritedAliases = aliasBySource.values.filter { it.canonicalLabelId == source }.map { it.sourceLabelId }
        aliasBySource[source] = PlatformAlias(source, target, sourceLabel.displayName)
        labels[source] = sourceLabel.copy(active = false)
        sourceSamples.forEach { it.labelId = target }
        inheritedAliases.forEach { old ->
            aliasBySource[old] = aliasBySource.getValue(old).copy(canonicalLabelId = target)
        }
    }

    fun recordHumanSample(sampleId: Long, labelId: Long, featureCount: Int) {
        require(sampleId !in samples && featureCount >= 0)
        samples[sampleId] = Sample(canonicalId(labelId), featureCount)
    }

    fun relabelByHuman(sampleId: Long, expectedOldLabelId: Long, newLabelId: Long) {
        val sample = requireNotNull(samples[sampleId]) { "unknown sample" }
        check(canonicalId(sample.labelId) == canonicalId(expectedOldLabelId)) { "sample label mismatch" }
        sample.labelId = canonicalId(newLabelId)
    }

    fun automaticSplit(sourceId: Long, targetId: Long): Nothing {
        require(sourceId != targetId)
        throw IllegalStateException("label splits require explicit human relabeling for each sample")
    }

    fun canonicalId(id: Long): Long {
        require(id in labels) { "unknown label" }
        var current = id
        val visited = mutableSetOf<Long>()
        while (current in aliasBySource) {
            check(visited.add(current)) { "alias cycle" }
            current = aliasBySource.getValue(current).canonicalLabelId
        }
        return current
    }

    fun label(id: Long): PlatformLabel = requireNotNull(labels[id]) { "unknown label" }

    fun aliases(canonicalId: Long): List<PlatformAlias> {
        val canonical = canonicalId(canonicalId)
        return aliasBySource.values.filter { canonicalId(it.sourceLabelId) == canonical }.sortedBy { it.sourceLabelId }
    }

    fun samplesFor(labelId: Long): Set<Long> {
        val canonical = canonicalId(labelId)
        return samples.filterValues { canonicalId(it.labelId) == canonical }.keys
    }

    fun modelStats(labelId: Long): ModelSufficientStats {
        val relevant = samplesFor(labelId).map { samples.getValue(it) }
        return ModelSufficientStats(relevant.size, relevant.sumOf { it.featureCount })
    }
}
