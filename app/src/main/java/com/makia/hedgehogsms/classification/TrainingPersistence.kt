package com.makia.hedgehogsms.classification

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "training_sample", indices = [Index(value = ["sourceMessageId"])], primaryKeys = ["sampleId"])
data class TrainingSample(
    val sampleId: Long,
    val sourceMessageId: Long?,
    val labelId: Long,
    val confirmationSource: String,
    val featureVersion: Int,
    val keyId: String,
    val schemaVersion: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "training_feature", primaryKeys = ["sampleId", "bucket"])
data class TrainingFeature(val sampleId: Long, val bucket: Int, val count: Int)

@Entity(tableName = "model_class_stat", primaryKeys = ["labelId", "keyId", "schemaVersion"])
data class ModelClassStat(
    val labelId: Long,
    val keyId: String,
    val schemaVersion: Int,
    val documentCount: Int,
    val featureCount: Int,
)

@Entity(tableName = "model_feature_stat", primaryKeys = ["labelId", "bucket", "keyId", "schemaVersion"])
data class ModelFeatureStat(
    val labelId: Long,
    val bucket: Int,
    val keyId: String,
    val schemaVersion: Int,
    val count: Int,
)

data class PersistentModelSnapshot(
    val classes: List<ModelClassStat>,
    val features: List<ModelFeatureStat>,
)
