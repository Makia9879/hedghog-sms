package com.makia.hedgehogsms.classification

import com.makia.hedgehogsms.data.AppDatabase
import com.makia.hedgehogsms.data.SmsSource
import kotlinx.coroutines.CancellationException

class PendingLabelTrainingQueueRepository(
    private val database: AppDatabase,
    private val smsSource: SmsSource,
    private val featureExtractor: TextFeatureExtractor,
    private val trainingRepository: TrainingRepository,
) {
    suspend fun enqueue(
        messageId: Long,
        labelId: Long,
        platformKey: String,
        displayName: String,
        now: Long,
    ) {
        database.pendingLabelTrainingQueueDao().upsert(
            PendingLabelTrainingJob(
                messageId = messageId,
                labelId = labelId,
                platformKey = platformKey,
                displayName = displayName,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun processNext(now: Long): Boolean {
        val job = database.pendingLabelTrainingQueueDao().nextRunnable() ?: return false
        if (database.pendingLabelTrainingQueueDao().markProcessing(job.messageId, now) == 0) return true
        try {
            val message = checkNotNull(smsSource.byId(job.messageId)) { "queued SMS is no longer readable" }
            val features = featureExtractor.extract(message.body, message.sender)
            trainingRepository.confirmHumanClassification(
                sampleId = job.messageId,
                sourceMessageId = job.messageId,
                labelId = job.labelId,
                platformKey = job.platformKey,
                displayName = job.displayName,
                features = features,
                now = now,
            )
            database.pendingLabelTrainingQueueDao().delete(job.messageId)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            database.pendingLabelTrainingQueueDao().markFailed(job.messageId, System.currentTimeMillis())
        }
        return true
    }

    suspend fun drain(maxJobs: Int = 25) {
        val now = System.currentTimeMillis()
        database.pendingLabelTrainingQueueDao().resetStaleProcessing(now - PROCESSING_LEASE_MILLIS, now)
        repeat(maxJobs.coerceAtLeast(0)) {
            if (!processNext(System.currentTimeMillis())) return
        }
    }

    private companion object {
        const val PROCESSING_LEASE_MILLIS = 10 * 60 * 1000L
    }
}
