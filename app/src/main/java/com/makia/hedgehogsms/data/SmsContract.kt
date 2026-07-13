package com.makia.hedgehogsms.data

data class SmsRecord(
    val id: Long,
    val dateMillis: Long,
    val type: Int,
    val sender: String?,
    val body: String,
    val subscriptionId: Long?,
)

data class SmsKeyset(val dateMillis: Long, val id: Long)
data class SmsFence(val dateMillis: Long, val id: Long)

interface SmsSource {
    suspend fun page(keyset: SmsKeyset?, limit: Int, fence: SmsFence?): List<SmsRecord>
    suspend fun byId(id: Long): SmsRecord?
    suspend fun estimateCount(fence: SmsFence? = null): Int
}

class FakeSmsSource(private val records: List<SmsRecord>) : SmsSource {
    override suspend fun page(keyset: SmsKeyset?, limit: Int, fence: SmsFence?): List<SmsRecord> =
        records.asSequence()
            .filter { fence == null || it.dateMillis < fence.dateMillis || (it.dateMillis == fence.dateMillis && it.id <= fence.id) }
            .filter { keyset == null || it.dateMillis < keyset.dateMillis || (it.dateMillis == keyset.dateMillis && it.id < keyset.id) }
            .sortedWith(compareByDescending<SmsRecord> { it.dateMillis }.thenByDescending { it.id })
            .take(limit.coerceIn(1, 25))
            .toList()

    override suspend fun byId(id: Long): SmsRecord? = records.firstOrNull { it.id == id }
    override suspend fun estimateCount(fence: SmsFence?): Int = records.count {
        fence == null || it.dateMillis < fence.dateMillis || (it.dateMillis == fence.dateMillis && it.id <= fence.id)
    }
}
