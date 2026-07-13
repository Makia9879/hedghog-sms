package com.makia.hedgehogsms.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeSmsSourceTest {
    @Test fun `keyset is stable for equal dates and page size is capped`() = runBlocking {
        val source = FakeSmsSource((1L..30L).map { SmsRecord(it, 100, 1, null, "fixture-$it", null) })
        val first = source.page(null, 100, null)
        assertEquals(25, first.size)
        assertEquals(30, first.first().id)
        val second = source.page(SmsKeyset(first.last().dateMillis, first.last().id), 25, null)
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), second.map { it.id })
    }

    @Test fun `keyset scans ten thousand equal-date records without gaps`() = runBlocking {
        val source = FakeSmsSource((1L..10_000L).map { SmsRecord(it, 100, 1, null, "", null) })
        val seen = mutableListOf<Long>()
        var cursor: SmsKeyset? = null
        val fence = SmsFence(100, 10_000)
        do {
            val page = source.page(cursor, 25, fence)
            seen += page.map { it.id }
            cursor = page.lastOrNull()?.let { SmsKeyset(it.dateMillis, it.id) }
        } while (page.isNotEmpty())
        assertEquals(10_000, seen.size)
        assertEquals(10_000, seen.distinct().size)
        assertEquals((10_000L downTo 1L).toList(), seen)
    }

    @Test fun `keyset boundaries zero twenty-four twenty-five and twenty-six are exact`() = runBlocking {
        listOf(0, 24, 25, 26).forEach { count ->
            val records = (1L..count.toLong()).map { id ->
                SmsRecord(id, id / 3, 1, null, "", null)
            }
            val source = FakeSmsSource(records.shuffled(kotlin.random.Random(count)))
            val seen = mutableListOf<Long>()
            var cursor: SmsKeyset? = null
            val fence = source.page(null, 1, null).firstOrNull()?.let { SmsFence(it.dateMillis, it.id) }
            if (fence != null) {
                do {
                    val page = source.page(cursor, 25, fence)
                    seen += page.map(SmsRecord::id)
                    cursor = page.lastOrNull()?.let { SmsKeyset(it.dateMillis, it.id) }
                } while (page.isNotEmpty())
            }
            val expected = records.sortedWith(compareByDescending<SmsRecord> { it.dateMillis }.thenByDescending { it.id }).map { it.id }
            assertEquals("count=$count", expected, seen)
            assertEquals("count=$count", count, seen.distinct().size)
        }
    }

    @Test fun `metadata estimate respects frozen fence`() = runBlocking {
        val source = FakeSmsSource(listOf(
            SmsRecord(3, 20, 1, null, "", null),
            SmsRecord(2, 10, 1, null, "", null),
            SmsRecord(1, 10, 1, null, "", null),
        ))
        assertEquals(3, source.estimateCount())
        assertEquals(2, source.estimateCount(SmsFence(10, 2)))
    }
}
