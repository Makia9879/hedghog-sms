package com.makia.hedgehogsms.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryScanPacingTest {
    @Test fun `default slice size matches sms source cap while preserving slice throughput`() {
        val pacing = HistoryScanPacing()

        assertEquals(25, pacing.pageSize)
        assertEquals(18, pacing.maxPagesPerSlice)
    }

    @Test fun `slice stops at max pages or time budget`() {
        val pacing = HistoryScanPacing(maxPagesPerSlice = 3, sliceBudgetMillis = 1_000)

        assertTrue(pacing.shouldContinue(pagesProcessed = 2, startedNanos = 0, nowNanos = 999_000_000))
        assertFalse(pacing.shouldContinue(pagesProcessed = 3, startedNanos = 0, nowNanos = 999_000_000))
        assertFalse(pacing.shouldContinue(pagesProcessed = 2, startedNanos = 0, nowNanos = 1_000_000_000))
    }

    @Test fun `page smaller than requested size completes the scan`() {
        val pacing = HistoryScanPacing(pageSize = 25)

        assertFalse(pacing.completedByPageSize(25))
        assertTrue(pacing.completedByPageSize(24))
        assertTrue(pacing.completedByPageSize(0))
    }

    @Test fun `continuation delay favors quick slices and backs off slow slices`() {
        val pacing = HistoryScanPacing(
            sliceBudgetMillis = 8_000,
            fastDelaySeconds = 2,
            normalDelaySeconds = 5,
            slowDelaySeconds = 12,
        )

        assertEquals(2, pacing.continuationDelaySeconds(HistoryScanSliceMetrics(0, 0)))
        assertEquals(2, pacing.continuationDelaySeconds(HistoryScanSliceMetrics(300, 4_000)))
        assertEquals(5, pacing.continuationDelaySeconds(HistoryScanSliceMetrics(100, 4_000)))
        assertEquals(12, pacing.continuationDelaySeconds(HistoryScanSliceMetrics(20, 4_000)))
        assertEquals(12, pacing.continuationDelaySeconds(HistoryScanSliceMetrics(300, 8_000)))
        assertEquals(12, pacing.continuationDelaySeconds(HistoryScanSliceMetrics(300, 4_000, hitTimeBudget = true)))
    }
}
