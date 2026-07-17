package com.makia.hedgehogsms.scan

data class HistoryScanSliceMetrics(
    val processedMessages: Int,
    val elapsedMillis: Long,
    val hitTimeBudget: Boolean = false,
)

class HistoryScanPacing(
    val pageSize: Int = 25,
    val maxPagesPerSlice: Int = 18,
    val sliceBudgetMillis: Long = 8_000,
    private val fastDelaySeconds: Long = 2,
    private val normalDelaySeconds: Long = 5,
    private val slowDelaySeconds: Long = 12,
) {
    init {
        require(pageSize > 0) { "pageSize must be positive" }
        require(maxPagesPerSlice > 0) { "maxPagesPerSlice must be positive" }
        require(sliceBudgetMillis > 0) { "sliceBudgetMillis must be positive" }
        require(fastDelaySeconds >= 0) { "fastDelaySeconds must be non-negative" }
        require(normalDelaySeconds >= fastDelaySeconds) { "normalDelaySeconds must be >= fastDelaySeconds" }
        require(slowDelaySeconds >= normalDelaySeconds) { "slowDelaySeconds must be >= normalDelaySeconds" }
    }

    fun shouldContinue(pagesProcessed: Int, startedNanos: Long, nowNanos: Long): Boolean =
        pagesProcessed < maxPagesPerSlice && elapsedMillis(startedNanos, nowNanos) < sliceBudgetMillis

    fun completedByPageSize(pageCount: Int): Boolean = pageCount < pageSize

    fun continuationDelaySeconds(metrics: HistoryScanSliceMetrics): Long {
        if (metrics.hitTimeBudget || metrics.elapsedMillis >= sliceBudgetMillis) return slowDelaySeconds
        if (metrics.processedMessages <= 0 || metrics.elapsedMillis <= 0) return fastDelaySeconds

        val messagesPerSecond = metrics.processedMessages * 1_000.0 / metrics.elapsedMillis
        return when {
            messagesPerSecond >= 50.0 -> fastDelaySeconds
            messagesPerSecond >= 15.0 -> normalDelaySeconds
            else -> slowDelaySeconds
        }
    }

    private fun elapsedMillis(startedNanos: Long, nowNanos: Long): Long =
        ((nowNanos - startedNanos).coerceAtLeast(0L)) / 1_000_000
}
