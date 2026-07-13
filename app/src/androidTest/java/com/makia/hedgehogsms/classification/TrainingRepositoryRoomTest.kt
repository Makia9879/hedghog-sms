package com.makia.hedgehogsms.classification

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.makia.hedgehogsms.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrainingRepositoryRoomTest {
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
    ).build()
    private val repository = TrainingRepository(database)

    @After fun close() = database.close()

    @Test fun learnAndCorrectPersistExactNonNegativeStatistics() = runBlocking {
        val features = SparseFeatures(mapOf(7 to 2, 9 to 1), keyId = "test-key")
        repository.learn(1, 101, 10, features, "HUMAN", 1)

        assertEquals(ModelClassStat(10, "test-key", 1, 1, 3), database.trainingDao().classStat(10, "test-key", 1))
        assertEquals(2, database.trainingDao().featureStat(10, 7, "test-key", 1)?.count)

        repository.correct(1, 10, 20, 2)

        assertEquals(0, database.trainingDao().classStat(10, "test-key", 1)?.documentCount)
        assertEquals(0, database.trainingDao().classStat(10, "test-key", 1)?.featureCount)
        assertNull(database.trainingDao().featureStat(10, 7, "test-key", 1))
        assertEquals(ModelClassStat(20, "test-key", 1, 1, 3), database.trainingDao().classStat(20, "test-key", 1))
        assertEquals(20, database.trainingDao().sample(1)?.labelId)
    }

    @Test fun failedCorrectionRollsBackWithoutPartialSubtraction() = runBlocking {
        val features = SparseFeatures(mapOf(4 to 2), keyId = "test-key")
        repository.learn(2, 102, 30, features, "HUMAN", 1)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.correct(2, 99, 40, 2) }
        }

        assertEquals(1, database.trainingDao().classStat(30, "test-key", 1)?.documentCount)
        assertEquals(2, database.trainingDao().featureStat(30, 4, "test-key", 1)?.count)
        assertEquals(30, database.trainingDao().sample(2)?.labelId)
    }

    @Test fun deletingEvidenceDoesNotForgetAggregatedModelOrChangePrediction() = runBlocking {
        val a = SparseFeatures(mapOf(1 to 3), keyId = "test-key")
        val b = SparseFeatures(mapOf(2 to 3), keyId = "test-key")
        repository.learn(3, 103, 1, a, "HUMAN", 1)
        repository.learn(4, 104, 2, b, "HUMAN", 1)
        val before = repository.predict(a)

        repository.deleteSamplesForMissingMessageWithoutForgettingModel(103)
        val after = repository.predict(a)

        assertNull(database.trainingDao().sample(3))
        assertEquals(3, database.trainingDao().featureStat(1, 1, "test-key", 1)?.count)
        assertEquals(before, after)
        assertFalse(after.autoAccepted)
    }

    @Test fun predictionIsReadOnly() = runBlocking {
        val features = SparseFeatures(mapOf(5 to 1), keyId = "test-key")
        repository.learn(5, null, 5, features, "HUMAN", 1)
        val before = database.trainingDao().classStats("test-key", 1)

        repeat(3) { repository.predict(features, ConfidenceMode.AUTOMATIC) }

        assertEquals(before, database.trainingDao().classStats("test-key", 1))
        assertEquals(1, database.trainingDao().features(5).single().count)
    }

    @Test fun interruptedHumanConfirmationIsIdempotentAndAtomicOnRetry() = runBlocking {
        val features = SparseFeatures(mapOf(12 to 2), keyId = "test-key")
        database.classificationDao().insertAutomated(listOf(
            MessageClassification(9, true, null, null, "PENDING_LABEL", null, false, 1),
        ))
        // Simulate the old two-transaction bug: training committed, UI classification did not.
        repository.learn(9, 9, 77, features, "HUMAN", 1)

        repeat(2) {
            repository.confirmHumanClassification(9, 9, 77, "key", "虚构平台", features, 2)
        }

        assertEquals(1, database.trainingDao().classStat(77, "test-key", 1)?.documentCount)
        assertEquals("LABELED", database.classificationDao().get(9)?.status)
        assertEquals(true, database.classificationDao().get(9)?.isHumanConfirmed)
    }
}
