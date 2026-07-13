package com.makia.hedgehogsms.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.makia.hedgehogsms.HedgehogApplication
import com.makia.hedgehogsms.data.SlotResolver
import com.makia.hedgehogsms.data.SmsKeyset
import com.makia.hedgehogsms.data.SmsPermissionUnavailableException
import com.makia.hedgehogsms.data.toIndex
import com.makia.hedgehogsms.classification.PlatformClassificationService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object SmsSyncScheduler {
    const val INCREMENTAL_WORK = "hedgehog.incremental.v1"
    const val RECONCILE_WORK = "hedgehog.reconcile.v1"

    suspend fun request(context: Context) {
        val app = context.applicationContext as HedgehogApplication
        val dao = app.container.database.syncStateDao()
        val now = System.currentTimeMillis()
        app.container.database.withTransaction {
            dao.insertIfMissing(SyncState(updatedAt = now))
            dao.request(now)
        }
        enqueueIncremental(context)
    }

    fun enqueueIncremental(context: Context) {
        val request = OneTimeWorkRequestBuilder<IncrementalSmsWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            INCREMENTAL_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    fun scheduleReconcile(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReconcileSmsWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            RECONCILE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

class SmsReceivedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != android.provider.Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Only a generation bump and enqueue. Provider rows, PDUs and
                // message text are deliberately not inspected in the receiver.
                SmsSyncScheduler.request(context)
            } finally {
                pending.finish()
            }
        }
    }
}

class IncrementalSmsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as HedgehogApplication
        val db = app.container.database
        val dao = db.syncStateDao()
        val state = dao.get() ?: return Result.success()
        if (state.requestedGeneration <= state.completedGeneration) return Result.success()
        val expected = state.requestedGeneration
        val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24)
        return try {
            val page = app.container.smsSource.page(
                state.cursorDate?.let { SmsKeyset(it, state.cursorId!!) },
                25,
                null,
            )
            val eligible = page.filter { it.dateMillis >= cutoff || it.id > state.baselineHighWaterId }
            if (eligible.isEmpty()) {
                if (state.cursorDate == null) {
                    // A broadcast can precede the Provider row. Retry at
                    // 10/20/40 seconds and deliberately leave requested ahead
                    // of completed after the final empty observation.
                    if (runAttemptCount < 3) Result.retry() else Result.success()
                } else {
                    // An empty/old page after at least one committed page is a
                    // proven end of this generation, not Provider latency.
                    dao.checkpoint(expected, expected, state.highWaterId, null, null, state.highWaterId, System.currentTimeMillis())
                    Result.success()
                }
            } else {
                // Durably own the next slice before a checkpoint can advance.
                // If this process dies after the Room transaction, the chain
                // still continues. Empty Provider retries intentionally do not
                // create an endless successor chain.
                SmsSyncScheduler.enqueueIncremental(applicationContext)
                val snapshot = app.container.subscriptionSource.snapshot()
                val resolver = SlotResolver()
                val now = System.currentTimeMillis()
                val indexes = eligible.map { it.toIndex(resolver.resolve(it.subscriptionId, snapshot.activeSlots, snapshot.logicalSlotCount), now) }
                val classifier = PlatformClassificationService(app.container)
                val classifications = eligible.map { classifier.classify(it, now) }
                var committed = false
                var completed = false
                db.withTransaction {
                    val latest = dao.get() ?: return@withTransaction
                    if (latest.requestedGeneration != expected) return@withTransaction
                    db.messageIndexDao().upsertAll(indexes)
                    db.classificationDao().upsertAutomated(classifications)
                    val last = page.lastOrNull()
                    completed = page.size < 25 || page.last().let { it.dateMillis < cutoff && it.id <= latest.baselineHighWaterId }
                    dao.checkpoint(
                        generation = expected,
                        completedGeneration = if (completed) expected else latest.completedGeneration,
                        highWaterId = maxOf(latest.highWaterId, indexes.maxOf { it.sourceMessageId }),
                        cursorDate = if (completed) null else last?.dateMillis,
                        cursorId = if (completed) null else last?.id,
                        baselineHighWaterId = if (completed) maxOf(latest.highWaterId, indexes.maxOf { it.sourceMessageId }) else latest.baselineHighWaterId,
                        now = now,
                    )
                    committed = true
                }
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SmsPermissionUnavailableException) {
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

class ReconcileSmsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as HedgehogApplication
        return try {
            // First request the same 24-hour/id-watermark compensation used on
            // foreground. Then remove only Provider references proven absent.
            SmsSyncScheduler.request(applicationContext)
            val dao = app.container.database.messageIndexDao()
            dao.all().chunked(25).forEach { batch ->
                batch.forEach { index ->
                    if (app.container.smsSource.byId(index.sourceMessageId) == null) {
                        app.container.trainingRepository.deleteSamplesForMissingMessageWithoutForgettingModel(index.sourceMessageId)
                        app.container.database.withTransaction {
                            app.container.database.classificationDao().deleteByMessageId(index.sourceMessageId)
                            dao.deleteById(index.sourceMessageId)
                        }
                    }
                }
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SmsPermissionUnavailableException) {
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
