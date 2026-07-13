package com.makia.hedgehogsms.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "message_index")
data class MessageIndex(
    @PrimaryKey val sourceMessageId: Long,
    val messageDate: Long,
    val messageType: Int,
    val rawSubscriptionId: Long?,
    val slotSnapshotIndex: Int?,
    val slotMappingStatus: SlotMappingStatus,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
)

data class SlotCountRow(
    val slotSnapshotIndex: Int?,
    val slotMappingStatus: SlotMappingStatus,
    val count: Int,
)

data class IndexSummary(
    val total: Int,
    val slot1: Int,
    val slot2: Int,
    val unknown: Int,
)

fun SmsRecord.toIndex(resolution: SlotResolution, observedAt: Long): MessageIndex = MessageIndex(
    sourceMessageId = id,
    messageDate = dateMillis,
    messageType = type,
    rawSubscriptionId = subscriptionId,
    slotSnapshotIndex = resolution.slotIndex,
    slotMappingStatus = resolution.status,
    firstSeenAt = observedAt,
    lastSeenAt = observedAt,
)
