package com.makia.hedgehogsms.scan

import android.content.Context
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.makia.hedgehogsms.HedgehogApplication
import com.makia.hedgehogsms.data.ScanRun
import com.makia.hedgehogsms.data.ScanStatus
import com.makia.hedgehogsms.data.SlotResolver
import com.makia.hedgehogsms.data.SmsFence
import com.makia.hedgehogsms.data.SmsKeyset
import com.makia.hedgehogsms.data.SmsPermissionUnavailableException
import com.makia.hedgehogsms.data.toIndex
import com.makia.hedgehogsms.classification.PlatformClassificationService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class HistoryScanCoordinator(private val context: Context) {
    private val app get() = context.applicationContext as HedgehogApplication
    private val work get() = WorkManager.getInstance(context)

    suspend fun startOrResume() {
        val dao = app.container.database.scanRunDao()
        val now = System.currentTimeMillis()
        app.container.database.withTransaction {
            dao.insertIfMissing(ScanRun(updatedAt = now))
            dao.bumpGeneration(ScanStatus.RUNNING, now)
        }
        enqueue(ExistingWorkPolicy.REPLACE, 0)
    }

    suspend fun pause() {
        val dao = app.container.database.scanRunDao()
        dao.bumpGeneration(ScanStatus.PAUSED, System.currentTimeMillis())
        work.cancelUniqueWork(WORK_NAME)
    }

    suspend fun restart() {
        work.cancelUniqueWork(WORK_NAME)
        val database = app.container.database
        val dao = database.scanRunDao()
        val now = System.currentTimeMillis()
        database.withTransaction {
            val nextGeneration = (dao.get()?.generation ?: 0) + 1
            database.messageIndexDao().deleteAll()
            dao.upsert(
                ScanRun(
                    generation = nextGeneration,
                    status = ScanStatus.RUNNING,
                    startedAt = now,
                    updatedAt = now,
                ),
            )
        }
        enqueue(ExistingWorkPolicy.REPLACE, 0)
    }

    fun enqueue(policy: ExistingWorkPolicy, delaySeconds: Long) {
        val request = OneTimeWorkRequestBuilder<HistoryScanWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(WORK_NAME, policy, request)
    }

    companion object { const val WORK_NAME = "hedgehog.history.v1" }
}

class HistoryScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as HedgehogApplication
        val container = app.container
        val runDao = container.database.scanRunDao()
        var run = runDao.get() ?: return Result.success()
        if (run.status !in setOf(ScanStatus.RUNNING, ScanStatus.WAITING_BATTERY, ScanStatus.WAITING_THERMAL)) return Result.success()
        val expectedGeneration = run.generation
        val deviceStatus = HistoryDeviceGate.status(run.status, container.historyDeviceState.snapshot())
        if (runDao.setDeviceStatusIfGeneration(expectedGeneration, deviceStatus, System.currentTimeMillis()) == 0) return Result.success()
        if (deviceStatus != ScanStatus.RUNNING) return Result.retry()
        // Append the next slice before touching the provider. If this process is
        // killed after committing a page, the durable WorkManager chain still
        // owns a continuation. A successor that observes COMPLETED is a no-op.
        HistoryScanCoordinator(applicationContext).enqueue(ExistingWorkPolicy.APPEND_OR_REPLACE, 15)
        val resolver = SlotResolver()
        val classifier = PlatformClassificationService(container)
        val snapshot = container.subscriptionSource.snapshot()
        val started = System.nanoTime()

        try {
            if (run.upperDate == null || run.upperId == null) {
                val first = container.smsSource.page(null, 1, null).firstOrNull()
                if (first == null) {
                    val now = System.currentTimeMillis()
                    runDao.finishEmptyIfRunning(expectedGeneration, now)
                    return Result.success()
                }
                val fence = SmsFence(first.dateMillis, first.id)
                val estimated = container.smsSource.estimateCount(fence)
                if (runDao.setFenceIfRunning(expectedGeneration, first.dateMillis, first.id, estimated, System.currentTimeMillis()) == 0) return Result.success()
                run = runDao.get() ?: return Result.success()
            }
            if (run.estimated == null && run.upperDate != null && run.upperId != null) {
                runDao.setEstimateIfMissing(
                    expectedGeneration,
                    container.smsSource.estimateCount(SmsFence(requireNotNull(run.upperDate), requireNotNull(run.upperId))),
                    System.currentTimeMillis(),
                )
                run = runDao.get() ?: return Result.success()
            }

            var pages = 0
            while (pages < 4 && (System.nanoTime() - started) / 1_000_000_000 < 5) {
                val latest = runDao.get() ?: return Result.success()
                if (latest.generation != expectedGeneration || latest.status != ScanStatus.RUNNING) return Result.success()
                val page = container.smsSource.page(
                    latest.cursorDate?.let { SmsKeyset(it, latest.cursorId!!) },
                    25,
                    SmsFence(latest.upperDate!!, latest.upperId!!),
                )
                val now = System.currentTimeMillis()
                val indexes = page.map { sms -> sms.toIndex(resolver.resolve(sms.subscriptionId, snapshot.activeSlots, snapshot.logicalSlotCount), now) }
                val classifications = page.map { classifier.classify(it, now) }
                var committed = false
                var completed = false
                container.database.withTransaction {
                    val fenced = runDao.get() ?: return@withTransaction
                    if (fenced.generation != expectedGeneration || fenced.status != ScanStatus.RUNNING) return@withTransaction
                    container.database.messageIndexDao().upsertAll(indexes)
                    container.database.classificationDao().upsertAutomated(classifications)
                    val last = page.lastOrNull()
                    runDao.upsert(fenced.copy(
                        cursorDate = last?.dateMillis ?: fenced.cursorDate,
                        cursorId = last?.id ?: fenced.cursorId,
                        processed = fenced.processed + page.size,
                        status = if (page.size < 25) ScanStatus.COMPLETED else ScanStatus.RUNNING,
                        updatedAt = now,
                        completedAt = if (page.size < 25) now else null,
                    ))
                    committed = true
                    completed = page.size < 25
                }
                if (!committed || completed) return Result.success()
                pages++
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SmsPermissionUnavailableException) {
            val now = System.currentTimeMillis()
            runDao.finishIfRunning(expectedGeneration, ScanStatus.WAITING_PERMISSION, null, null, now)
            return Result.success()
        } catch (error: Exception) {
            if (runAttemptCount < 3) return Result.retry()
            val now = System.currentTimeMillis()
            runDao.finishIfRunning(expectedGeneration, ScanStatus.FAILED, error.javaClass.simpleName, null, now)
            return Result.failure()
        }
        return Result.success()
    }
}
