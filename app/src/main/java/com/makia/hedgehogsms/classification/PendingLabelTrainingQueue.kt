package com.makia.hedgehogsms.classification

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object PendingLabelTrainingQueueStatus {
    const val PENDING = "PENDING"
    const val PROCESSING = "PROCESSING"
    const val FAILED = "FAILED"
}

@Entity(
    tableName = "pending_label_training_queue",
    indices = [Index(value = ["status", "createdAt"])],
)
data class PendingLabelTrainingJob(
    @PrimaryKey val messageId: Long,
    val labelId: Long,
    val platformKey: String,
    val displayName: String,
    val status: String = PendingLabelTrainingQueueStatus.PENDING,
    val attempts: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)
