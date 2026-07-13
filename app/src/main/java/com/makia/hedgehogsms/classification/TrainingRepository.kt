package com.makia.hedgehogsms.classification

import androidx.room.withTransaction
import com.makia.hedgehogsms.data.AppDatabase

class TrainingRepository(private val database: AppDatabase) {
    suspend fun confirmHumanClassification(
        sampleId: Long,
        sourceMessageId: Long,
        labelId: Long,
        platformKey: String,
        displayName: String,
        features: SparseFeatures,
        now: Long,
    ) = database.withTransaction {
        validate(features)
        val dao = database.trainingDao()
        val existing = dao.sample(sampleId)
        when {
            existing == null -> {
                dao.insertSample(TrainingSample(sampleId, sourceMessageId, labelId, "HUMAN",
                    features.schemaVersion, features.keyId, MODEL_SCHEMA, now, now))
                dao.insertFeatures(features.buckets.map { TrainingFeature(sampleId, it.key, it.value) })
                add(dao, labelId, features)
            }
            existing.labelId != labelId -> {
                val stored = SparseFeatures(
                    dao.features(sampleId).associate { it.bucket to it.count },
                    existing.featureVersion,
                    existing.keyId,
                )
                subtractChecked(dao, existing.labelId, stored)
                add(dao, labelId, stored)
                dao.updateSampleLabel(sampleId, labelId, now)
            }
            else -> Unit // Idempotent retry after a previously interrupted UI action.
        }
        check(database.classificationDao().confirmHumanLabel(sourceMessageId, platformKey, displayName, now) == 1) {
            "classification row missing"
        }
    }

    suspend fun learn(
        sampleId: Long,
        sourceMessageId: Long?,
        labelId: Long,
        features: SparseFeatures,
        confirmationSource: String,
        now: Long,
    ) = database.withTransaction {
        validate(features)
        val dao = database.trainingDao()
        check(dao.sample(sampleId) == null) { "sample already learned" }
        dao.insertSample(TrainingSample(sampleId, sourceMessageId, labelId, confirmationSource,
            features.schemaVersion, features.keyId, MODEL_SCHEMA, now, now))
        dao.insertFeatures(features.buckets.map { TrainingFeature(sampleId, it.key, it.value) })
        add(dao, labelId, features)
    }

    suspend fun correct(sampleId: Long, oldLabelId: Long, newLabelId: Long, now: Long) = database.withTransaction {
        val dao = database.trainingDao()
        val sample = requireNotNull(dao.sample(sampleId)) { "unknown sample" }
        require(sample.labelId == oldLabelId) { "sample label mismatch" }
        val features = SparseFeatures(
            dao.features(sampleId).associate { it.bucket to it.count },
            sample.featureVersion,
            sample.keyId,
        )
        validate(features)
        subtractChecked(dao, oldLabelId, features)
        add(dao, newLabelId, features)
        dao.updateSampleLabel(sampleId, newLabelId, now)
    }

    /** Removes linkage/evidence only. Aggregated model knowledge is intentionally retained. */
    suspend fun deleteSampleWithoutForgettingModel(sampleId: Long) = database.withTransaction {
        database.trainingDao().deleteFeatures(sampleId)
        database.trainingDao().deleteSample(sampleId)
    }

    suspend fun deleteSamplesForMissingMessageWithoutForgettingModel(sourceMessageId: Long) = database.withTransaction {
        val dao = database.trainingDao()
        dao.sampleIdsForMessage(sourceMessageId).forEach {
            dao.deleteFeatures(it)
            dao.deleteSample(it)
        }
    }

    suspend fun predict(features: SparseFeatures, mode: ConfidenceMode = ConfidenceMode.BALANCED): Prediction {
        validate(features)
        val dao = database.trainingDao()
        val classes = dao.classStats(features.keyId, MODEL_SCHEMA)
        val featureStats = dao.featureStats(features.keyId, MODEL_SCHEMA)
        val model = ModelSnapshot(MODEL_SCHEMA, classes.associate { cls ->
            cls.labelId to ClassSnapshot(
                cls.documentCount,
                cls.featureCount,
                featureStats.filter { it.labelId == cls.labelId }.associate { it.bucket to it.count },
            )
        })
        return OnlineMultinomialNaiveBayes.predict(model, features, mode)
    }

    private suspend fun add(dao: com.makia.hedgehogsms.data.TrainingDao, labelId: Long, features: SparseFeatures) {
        val total = features.buckets.values.sum()
        val cls = dao.classStat(labelId, features.keyId, MODEL_SCHEMA)
        dao.upsertClass(ModelClassStat(labelId, features.keyId, MODEL_SCHEMA,
            (cls?.documentCount ?: 0) + 1, (cls?.featureCount ?: 0) + total))
        features.buckets.forEach { (bucket, count) ->
            val old = dao.featureStat(labelId, bucket, features.keyId, MODEL_SCHEMA)
            dao.upsertFeature(ModelFeatureStat(labelId, bucket, features.keyId, MODEL_SCHEMA, (old?.count ?: 0) + count))
        }
    }

    private suspend fun subtractChecked(dao: com.makia.hedgehogsms.data.TrainingDao, labelId: Long, features: SparseFeatures) {
        val cls = requireNotNull(dao.classStat(labelId, features.keyId, MODEL_SCHEMA)) { "missing class statistics" }
        val total = features.buckets.values.sum()
        check(cls.documentCount >= 1 && cls.featureCount >= total) { "class statistics would become negative" }
        val existing = features.buckets.mapValues { (bucket, count) ->
            val stat = requireNotNull(dao.featureStat(labelId, bucket, features.keyId, MODEL_SCHEMA)) { "missing feature statistics" }
            check(stat.count >= count) { "feature statistics would become negative" }
            stat
        }
        dao.upsertClass(cls.copy(documentCount = cls.documentCount - 1, featureCount = cls.featureCount - total))
        existing.forEach { (bucket, stat) ->
            val next = stat.count - features.buckets.getValue(bucket)
            if (next == 0) dao.deleteFeature(labelId, bucket, features.keyId, MODEL_SCHEMA)
            else dao.upsertFeature(stat.copy(count = next))
        }
    }

    private fun validate(features: SparseFeatures) {
        require(features.keyId.isNotBlank())
        require(features.buckets.isNotEmpty())
        require(features.buckets.all { it.key in 0 until 16_384 && it.value > 0 })
    }

    companion object { const val MODEL_SCHEMA = 1 }
}
