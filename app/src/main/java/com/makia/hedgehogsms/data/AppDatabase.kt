package com.makia.hedgehogsms.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow
import com.makia.hedgehogsms.sync.SyncState
import com.makia.hedgehogsms.classification.MessageClassification
import com.makia.hedgehogsms.classification.PlatformSummary
import com.makia.hedgehogsms.classification.PlatformIdentity
import com.makia.hedgehogsms.classification.PlatformSlotFilter
import com.makia.hedgehogsms.classification.PendingLabelTrainingJob
import com.makia.hedgehogsms.classification.TrainingSample
import com.makia.hedgehogsms.classification.TrainingFeature
import com.makia.hedgehogsms.classification.ModelClassStat
import com.makia.hedgehogsms.classification.ModelFeatureStat

@Dao
interface MessageIndexDao {
    @Upsert suspend fun upsertAll(indexes: List<MessageIndex>)

    @Query("DELETE FROM message_index")
    suspend fun deleteAll()

    @Query("DELETE FROM message_index WHERE sourceMessageId=:sourceMessageId")
    suspend fun deleteById(sourceMessageId: Long): Int

    @Query("SELECT * FROM message_index ORDER BY messageDate DESC, sourceMessageId DESC")
    suspend fun all(): List<MessageIndex>

    @Query("SELECT COALESCE(MAX(sourceMessageId), 0) FROM message_index")
    suspend fun maxSourceMessageId(): Long

    @Query("""
        SELECT COUNT(*) AS total,
          COALESCE(SUM(CASE WHEN slotSnapshotIndex = 0 AND slotMappingStatus = 'RESOLVED' THEN 1 ELSE 0 END), 0) AS slot1,
          COALESCE(SUM(CASE WHEN slotSnapshotIndex = 1 AND slotMappingStatus = 'RESOLVED' THEN 1 ELSE 0 END), 0) AS slot2,
          COALESCE(SUM(CASE WHEN slotMappingStatus != 'RESOLVED' OR slotSnapshotIndex IS NULL OR slotSnapshotIndex NOT IN (0, 1) THEN 1 ELSE 0 END), 0) AS unknown
        FROM message_index
    """)
    fun observeSummary(): Flow<IndexSummary>

    @Query("SELECT * FROM message_index ORDER BY messageDate DESC, sourceMessageId DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<MessageIndex>

    suspend fun pageBySlotFilter(slotFilter: PlatformSlotFilter, limit: Int, offset: Int): List<MessageIndex> =
        pageBySlotFilter(slotFilter.name, limit, offset)

    @Query("""
        SELECT * FROM message_index
        WHERE (
          :slotFilter='ALL'
          OR (:slotFilter='SLOT_1' AND slotSnapshotIndex=0 AND slotMappingStatus='RESOLVED')
          OR (:slotFilter='SLOT_2' AND slotSnapshotIndex=1 AND slotMappingStatus='RESOLVED')
          OR (:slotFilter='UNKNOWN' AND (slotMappingStatus!='RESOLVED' OR slotSnapshotIndex IS NULL OR slotSnapshotIndex NOT IN (0,1)))
        )
        ORDER BY messageDate DESC, sourceMessageId DESC LIMIT :limit OFFSET :offset
    """)
    suspend fun pageBySlotFilter(slotFilter: String, limit: Int, offset: Int): List<MessageIndex>

}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id='incremental'") suspend fun get(): SyncState?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIfMissing(state: SyncState)
    @Query("""UPDATE sync_state SET requestedGeneration=requestedGeneration+1,
        cursorDate=NULL, cursorId=NULL, baselineHighWaterId=highWaterId, updatedAt=:now
        WHERE id='incremental'""")
    suspend fun request(now: Long): Int
    @Query("""UPDATE sync_state SET completedGeneration=:completedGeneration,
        highWaterId=:highWaterId, cursorDate=:cursorDate, cursorId=:cursorId,
        baselineHighWaterId=:baselineHighWaterId, updatedAt=:now
        WHERE id='incremental' AND requestedGeneration=:generation""")
    suspend fun checkpoint(generation: Long, completedGeneration: Long, highWaterId: Long,
        cursorDate: Long?, cursorId: Long?, baselineHighWaterId: Long, now: Long): Int

    @Query("""UPDATE sync_state SET highWaterId=MAX(highWaterId, :highWaterId),
        baselineHighWaterId=MAX(baselineHighWaterId, :highWaterId), updatedAt=:now
        WHERE id='incremental'""")
    suspend fun advanceHighWaterId(highWaterId: Long, now: Long): Int
}

@Dao
interface ClassificationDao {
    @Query("SELECT * FROM message_classification WHERE sourceMessageId=:sourceMessageId")
    suspend fun get(sourceMessageId: Long): MessageClassification?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAutomated(rows: List<MessageClassification>): List<Long>

    @Query("""UPDATE message_classification SET isOtp=:isOtp, platformKey=:platformKey,
        platformDisplayName=:displayName, status=:status, source=:source, updatedAt=:updatedAt
        WHERE sourceMessageId=:sourceMessageId AND isHumanConfirmed=0""")
    suspend fun updateAutomated(sourceMessageId: Long, isOtp: Boolean, platformKey: String?, displayName: String?, status: String, source: String?, updatedAt: Long): Int

    @androidx.room.Transaction
    suspend fun upsertAutomated(rows: List<MessageClassification>) {
        insertAutomated(rows)
        rows.forEach { row ->
            updateAutomated(row.sourceMessageId, row.isOtp, row.platformKey, row.platformDisplayName, row.status, row.source, row.updatedAt)
        }
    }

    @Query("DELETE FROM message_classification WHERE sourceMessageId=:sourceMessageId")
    suspend fun deleteByMessageId(sourceMessageId: Long): Int

    @Query("DELETE FROM message_classification")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM message_classification WHERE status='PENDING_LABEL'")
    fun observePendingCount(): Flow<Int>

    @Query("""
        SELECT c.platformKey AS platformKey, MAX(c.platformDisplayName) AS displayName,
          COUNT(*) AS otpCount, MAX(m.messageDate) AS latestMessageDate,
          COALESCE(SUM(CASE WHEN m.slotSnapshotIndex=0 AND m.slotMappingStatus='RESOLVED' THEN 1 ELSE 0 END),0) AS slot1Count,
          COALESCE(SUM(CASE WHEN m.slotSnapshotIndex=1 AND m.slotMappingStatus='RESOLVED' THEN 1 ELSE 0 END),0) AS slot2Count,
          COALESCE(SUM(CASE WHEN m.slotMappingStatus!='RESOLVED' OR m.slotSnapshotIndex IS NULL OR m.slotSnapshotIndex NOT IN (0,1) THEN 1 ELSE 0 END),0) AS unknownCount
        FROM message_classification c JOIN message_index m ON m.sourceMessageId=c.sourceMessageId
        WHERE c.isOtp=1 AND c.platformKey IS NOT NULL
        GROUP BY c.platformKey ORDER BY latestMessageDate DESC
    """)
    fun observeAllPlatformSummaries(): Flow<List<PlatformSummary>>

    fun observePlatformSummaries(): Flow<List<PlatformSummary>> = observePlatformSummaries(PlatformSlotFilter.ALL.name)

    @Query("""
        SELECT c.platformKey AS platformKey, MAX(c.platformDisplayName) AS displayName,
          COUNT(*) AS otpCount, MAX(m.messageDate) AS latestMessageDate,
          COALESCE(SUM(CASE WHEN m.slotSnapshotIndex=0 AND m.slotMappingStatus='RESOLVED' THEN 1 ELSE 0 END),0) AS slot1Count,
          COALESCE(SUM(CASE WHEN m.slotSnapshotIndex=1 AND m.slotMappingStatus='RESOLVED' THEN 1 ELSE 0 END),0) AS slot2Count,
          COALESCE(SUM(CASE WHEN m.slotMappingStatus!='RESOLVED' OR m.slotSnapshotIndex IS NULL OR m.slotSnapshotIndex NOT IN (0,1) THEN 1 ELSE 0 END),0) AS unknownCount
        FROM message_classification c JOIN message_index m ON m.sourceMessageId=c.sourceMessageId
        WHERE c.isOtp=1 AND c.platformKey IS NOT NULL AND (
          :slotFilter='ALL'
          OR (:slotFilter='SLOT_1' AND m.slotSnapshotIndex=0 AND m.slotMappingStatus='RESOLVED')
          OR (:slotFilter='SLOT_2' AND m.slotSnapshotIndex=1 AND m.slotMappingStatus='RESOLVED')
          OR (:slotFilter='UNKNOWN' AND (m.slotMappingStatus!='RESOLVED' OR m.slotSnapshotIndex IS NULL OR m.slotSnapshotIndex NOT IN (0,1)))
        )
        GROUP BY c.platformKey ORDER BY latestMessageDate DESC
    """)
    fun observePlatformSummaries(slotFilter: String): Flow<List<PlatformSummary>>

    @Query("""
        SELECT c.platformKey AS platformKey, MAX(c.platformDisplayName) AS displayName,
          COUNT(*) AS otpCount, MAX(m.messageDate) AS latestMessageDate,
          COALESCE(SUM(CASE WHEN m.slotSnapshotIndex=0 AND m.slotMappingStatus='RESOLVED' THEN 1 ELSE 0 END),0) AS slot1Count,
          COALESCE(SUM(CASE WHEN m.slotSnapshotIndex=1 AND m.slotMappingStatus='RESOLVED' THEN 1 ELSE 0 END),0) AS slot2Count,
          COALESCE(SUM(CASE WHEN m.slotMappingStatus!='RESOLVED' OR m.slotSnapshotIndex IS NULL OR m.slotSnapshotIndex NOT IN (0,1) THEN 1 ELSE 0 END),0) AS unknownCount
        FROM message_classification c JOIN message_index m ON m.sourceMessageId=c.sourceMessageId
        WHERE c.isOtp=1 AND c.platformKey IS NOT NULL AND (
          :slotFilter='ALL'
          OR (:slotFilter='SLOT_1' AND m.slotSnapshotIndex=0 AND m.slotMappingStatus='RESOLVED')
          OR (:slotFilter='SLOT_2' AND m.slotSnapshotIndex=1 AND m.slotMappingStatus='RESOLVED')
          OR (:slotFilter='UNKNOWN' AND (m.slotMappingStatus!='RESOLVED' OR m.slotSnapshotIndex IS NULL OR m.slotSnapshotIndex NOT IN (0,1)))
        )
        GROUP BY c.platformKey ORDER BY latestMessageDate DESC
    """)
    suspend fun platformSummaries(slotFilter: String): List<PlatformSummary>

    suspend fun messageIdsForPlatform(platformKey: String, limit: Int, offset: Int): List<Long> =
        messageIdsForPlatform(platformKey, PlatformSlotFilter.ALL.name, limit, offset)

    @Query("""
        SELECT c.sourceMessageId FROM message_classification c JOIN message_index m ON m.sourceMessageId=c.sourceMessageId
        WHERE c.isOtp=1 AND c.platformKey=:platformKey AND (
          :slotFilter='ALL'
          OR (:slotFilter='SLOT_1' AND m.slotSnapshotIndex=0 AND m.slotMappingStatus='RESOLVED')
          OR (:slotFilter='SLOT_2' AND m.slotSnapshotIndex=1 AND m.slotMappingStatus='RESOLVED')
          OR (:slotFilter='UNKNOWN' AND (m.slotMappingStatus!='RESOLVED' OR m.slotSnapshotIndex IS NULL OR m.slotSnapshotIndex NOT IN (0,1)))
        )
        ORDER BY c.sourceMessageId DESC LIMIT :limit OFFSET :offset
    """)
    suspend fun messageIdsForPlatform(platformKey: String, slotFilter: String, limit: Int, offset: Int): List<Long>

    @Query("""
        SELECT sourceMessageId FROM message_classification
        WHERE status='PENDING_LABEL'
          AND sourceMessageId NOT IN (
            SELECT messageId FROM pending_label_training_queue
            WHERE status IN ('PENDING', 'PROCESSING', 'FAILED')
          )
        ORDER BY sourceMessageId DESC LIMIT 1
    """)
    suspend fun nextPendingMessageId(): Long?

    @Query("""UPDATE message_classification SET platformKey=:platformKey, platformDisplayName=:displayName,
        status='LABELED', source='HUMAN', isHumanConfirmed=1, updatedAt=:now
        WHERE sourceMessageId=:sourceMessageId AND status='PENDING_LABEL'""")
    suspend fun confirmPendingHumanLabel(sourceMessageId: Long, platformKey: String, displayName: String, now: Long): Int

    @Query("SELECT DISTINCT platformKey, platformDisplayName AS displayName FROM message_classification WHERE platformKey IS NOT NULL AND platformDisplayName IS NOT NULL")
    suspend fun knownPlatforms(): List<PlatformIdentity>
}

@Dao
interface PendingLabelTrainingQueueDao {
    @Upsert suspend fun upsert(job: PendingLabelTrainingJob)

    @Query("""
        SELECT * FROM pending_label_training_queue
        WHERE status IN ('PENDING', 'FAILED')
        ORDER BY createdAt ASC, messageId ASC
        LIMIT 1
    """)
    suspend fun nextRunnable(): PendingLabelTrainingJob?

    @Query("""
        UPDATE pending_label_training_queue
        SET status='PROCESSING', attempts=attempts+1, updatedAt=:now
        WHERE messageId=:messageId AND status IN ('PENDING', 'FAILED')
    """)
    suspend fun markProcessing(messageId: Long, now: Long): Int

    @Query("UPDATE pending_label_training_queue SET status='FAILED', updatedAt=:now WHERE messageId=:messageId")
    suspend fun markFailed(messageId: Long, now: Long): Int

    @Query("UPDATE pending_label_training_queue SET status='PENDING', updatedAt=:now WHERE status='PROCESSING' AND updatedAt<:before")
    suspend fun resetStaleProcessing(before: Long, now: Long): Int

    @Query("DELETE FROM pending_label_training_queue WHERE messageId=:messageId")
    suspend fun delete(messageId: Long): Int

    @Query("SELECT * FROM pending_label_training_queue WHERE messageId=:messageId")
    suspend fun get(messageId: Long): PendingLabelTrainingJob?
}

@Dao
interface TrainingDao {
    @Query("SELECT * FROM training_sample WHERE sampleId=:sampleId") suspend fun sample(sampleId: Long): TrainingSample?
    @Query("SELECT sampleId FROM training_sample WHERE sourceMessageId=:sourceMessageId") suspend fun sampleIdsForMessage(sourceMessageId: Long): List<Long>
    @Insert suspend fun insertSample(sample: TrainingSample)
    @Query("UPDATE training_sample SET labelId=:labelId, updatedAt=:now WHERE sampleId=:sampleId") suspend fun updateSampleLabel(sampleId: Long, labelId: Long, now: Long): Int
    @Query("DELETE FROM training_sample WHERE sampleId=:sampleId") suspend fun deleteSample(sampleId: Long): Int
    @Insert suspend fun insertFeatures(features: List<TrainingFeature>)
    @Query("SELECT * FROM training_feature WHERE sampleId=:sampleId") suspend fun features(sampleId: Long): List<TrainingFeature>
    @Query("DELETE FROM training_feature WHERE sampleId=:sampleId") suspend fun deleteFeatures(sampleId: Long): Int
    @Query("SELECT * FROM model_class_stat WHERE labelId=:labelId AND keyId=:keyId AND schemaVersion=:schemaVersion")
    suspend fun classStat(labelId: Long, keyId: String, schemaVersion: Int): ModelClassStat?
    @Query("SELECT * FROM model_class_stat WHERE keyId=:keyId AND schemaVersion=:schemaVersion")
    suspend fun classStats(keyId: String, schemaVersion: Int): List<ModelClassStat>
    @Upsert suspend fun upsertClass(stat: ModelClassStat)
    @Query("SELECT * FROM model_feature_stat WHERE labelId=:labelId AND bucket=:bucket AND keyId=:keyId AND schemaVersion=:schemaVersion")
    suspend fun featureStat(labelId: Long, bucket: Int, keyId: String, schemaVersion: Int): ModelFeatureStat?
    @Query("SELECT * FROM model_feature_stat WHERE keyId=:keyId AND schemaVersion=:schemaVersion")
    suspend fun featureStats(keyId: String, schemaVersion: Int): List<ModelFeatureStat>
    @Upsert suspend fun upsertFeature(stat: ModelFeatureStat)
    @Query("DELETE FROM model_feature_stat WHERE labelId=:labelId AND bucket=:bucket AND keyId=:keyId AND schemaVersion=:schemaVersion")
    suspend fun deleteFeature(labelId: Long, bucket: Int, keyId: String, schemaVersion: Int): Int

    @Query("DELETE FROM training_feature WHERE sampleId IN (SELECT sampleId FROM training_sample WHERE keyId != :currentKeyId)")
    suspend fun deleteIncompatibleFeatures(currentKeyId: String): Int
    @Query("DELETE FROM training_sample WHERE keyId != :currentKeyId")
    suspend fun deleteIncompatibleSamples(currentKeyId: String): Int
    @Query("DELETE FROM model_feature_stat WHERE keyId != :currentKeyId")
    suspend fun deleteIncompatibleModelFeatures(currentKeyId: String): Int
    @Query("DELETE FROM model_class_stat WHERE keyId != :currentKeyId")
    suspend fun deleteIncompatibleModelClasses(currentKeyId: String): Int

    @androidx.room.Transaction
    suspend fun clearIncompatibleKeyData(currentKeyId: String) {
        deleteIncompatibleFeatures(currentKeyId)
        deleteIncompatibleSamples(currentKeyId)
        deleteIncompatibleModelFeatures(currentKeyId)
        deleteIncompatibleModelClasses(currentKeyId)
    }
}

@Dao
interface ScanRunDao {
    @Query("SELECT * FROM scan_run WHERE id = 'history'") suspend fun get(): ScanRun?
    @Query("SELECT * FROM scan_run WHERE id = 'history'") fun observe(): Flow<ScanRun?>
    @Upsert suspend fun upsert(run: ScanRun)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIfMissing(run: ScanRun)
    @Query("UPDATE scan_run SET generation=generation+1, status='RUNNING', lastError=NULL, startedAt=COALESCE(startedAt,:now), updatedAt=:now WHERE id='history' AND status != 'COMPLETED'")
    suspend fun startOrResumeIfNotCompleted(now: Long): Int
    @Query("UPDATE scan_run SET generation=generation+1, status=:status, lastError=NULL, startedAt=COALESCE(startedAt,:now), updatedAt=:now WHERE id='history'")
    suspend fun bumpGeneration(status: ScanStatus, now: Long): Int
    @Query("UPDATE scan_run SET upperDate=:date, upperId=:messageId, estimated=:estimated, updatedAt=:now WHERE id='history' AND generation=:generation AND status='RUNNING'")
    suspend fun setFenceIfRunning(generation: Long, date: Long, messageId: Long, estimated: Int, now: Long): Int
    @Query("UPDATE scan_run SET estimated=:estimated, updatedAt=:now WHERE id='history' AND generation=:generation AND status='RUNNING' AND estimated IS NULL")
    suspend fun setEstimateIfMissing(generation: Long, estimated: Int, now: Long): Int
    @Query("UPDATE scan_run SET status=:status, updatedAt=:now WHERE id='history' AND generation=:generation AND status IN ('RUNNING','WAITING_BATTERY','WAITING_THERMAL')")
    suspend fun setDeviceStatusIfGeneration(generation: Long, status: ScanStatus, now: Long): Int
    @Query("UPDATE scan_run SET status=:status, lastError=:error, completedAt=:completedAt, updatedAt=:now WHERE id='history' AND generation=:generation AND status='RUNNING'")
    suspend fun finishIfRunning(generation: Long, status: ScanStatus, error: String?, completedAt: Long?, now: Long): Int
    @Query("UPDATE scan_run SET status='COMPLETED', estimated=0, completedAt=:now, updatedAt=:now WHERE id='history' AND generation=:generation AND status='RUNNING'")
    suspend fun finishEmptyIfRunning(generation: Long, now: Long): Int
}

class DatabaseConverters {
    @TypeConverter fun mappingStatus(value: SlotMappingStatus): String = value.name
    @TypeConverter fun mappingStatus(value: String): SlotMappingStatus = SlotMappingStatus.valueOf(value)
    @TypeConverter fun scanStatus(value: ScanStatus): String = value.name
    @TypeConverter fun scanStatus(value: String): ScanStatus = ScanStatus.valueOf(value)
}

@Database(entities = [MessageIndex::class, ScanRun::class, SyncState::class, MessageClassification::class,
    TrainingSample::class, TrainingFeature::class, ModelClassStat::class, ModelFeatureStat::class,
    PendingLabelTrainingJob::class], version = 7, exportSchema = false)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageIndexDao(): MessageIndexDao
    abstract fun scanRunDao(): ScanRunDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun classificationDao(): ClassificationDao
    abstract fun pendingLabelTrainingQueueDao(): PendingLabelTrainingQueueDao
    abstract fun trainingDao(): TrainingDao
}
