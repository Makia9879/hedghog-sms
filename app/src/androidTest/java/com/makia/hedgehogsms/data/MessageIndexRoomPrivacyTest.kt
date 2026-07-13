package com.makia.hedgehogsms.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import com.makia.hedgehogsms.sync.SyncState
import com.makia.hedgehogsms.classification.MessageClassification

@RunWith(AndroidJUnit4::class)
class MessageIndexRoomPrivacyTest {
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        AppDatabase::class.java,
    ).build()

    @After fun close() = database.close()

    @Test fun schemaAndStoredRowContainOnlyMetadata() = kotlinx.coroutines.runBlocking {
        val sentinel = "must-never-be-persisted-123456"
        val record = SmsRecord(9, 10, 1, "private-sender", sentinel, 42)
        database.messageIndexDao().upsertAll(listOf(
            record.toIndex(SlotResolver().resolve(42, mapOf(42L to 0), 2), 11),
        ))
        assertTrue(database.messageIndexDao().all().single().sourceMessageId == 9L)
        val columns = database.openHelper.readableDatabase
            .query("PRAGMA table_info(message_index)")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")).lowercase()) } }
        listOf("body", "address", "sender", "preview", "code", "otp").forEach { forbidden ->
            assertFalse(columns.any { forbidden in it })
        }
    }

    @Test fun staleGenerationCannotSetFenceOrFinishAfterPause() = kotlinx.coroutines.runBlocking {
        val dao = database.scanRunDao()
        dao.insertIfMissing(ScanRun(generation = 7, status = ScanStatus.RUNNING, updatedAt = 1))

        assertEquals(1, dao.bumpGeneration(ScanStatus.PAUSED, 2))
        assertEquals(0, dao.setFenceIfRunning(7, 100, 9, 50, 3))
        assertEquals(0, dao.finishIfRunning(7, ScanStatus.COMPLETED, null, 3, 3))

        val paused = dao.get()!!
        assertEquals(8, paused.generation)
        assertEquals(ScanStatus.PAUSED, paused.status)
        assertEquals(null, paused.upperDate)
        assertEquals(null, paused.completedAt)
    }

    @Test fun pauseAndResumePreserveCheckpointAndAdvanceGeneration() = kotlinx.coroutines.runBlocking {
        val dao = database.scanRunDao()
        dao.insertIfMissing(ScanRun(
            generation = 2,
            status = ScanStatus.RUNNING,
            upperDate = 500,
            upperId = 50,
            cursorDate = 300,
            cursorId = 30,
            processed = 25,
            updatedAt = 1,
        ))

        dao.bumpGeneration(ScanStatus.PAUSED, 2)
        dao.bumpGeneration(ScanStatus.RUNNING, 3)

        val resumed = dao.get()!!
        assertEquals(4, resumed.generation)
        assertEquals(ScanStatus.RUNNING, resumed.status)
        assertEquals(300, resumed.cursorDate)
        assertEquals(30, resumed.cursorId)
        assertEquals(25, resumed.processed)
        assertEquals(500, resumed.upperDate)
        assertEquals(50, resumed.upperId)
    }

    @Test fun incrementalCheckpointRejectsStaleGeneration() = kotlinx.coroutines.runBlocking {
        val dao = database.syncStateDao()
        dao.insertIfMissing(SyncState(requestedGeneration = 3, completedGeneration = 1, highWaterId = 8, updatedAt = 1))

        assertEquals(0, dao.checkpoint(2, 2, 9, null, null, 9, 2))
        assertEquals(1, dao.checkpoint(3, 3, 9, null, null, 9, 3))

        val state = dao.get()!!
        assertEquals(3, state.completedGeneration)
        assertEquals(9, state.highWaterId)
    }

    @Test fun newIncrementalRequestKeepsWatermarkAndStartsFreshCursor() = kotlinx.coroutines.runBlocking {
        val dao = database.syncStateDao()
        dao.insertIfMissing(SyncState(
            requestedGeneration = 4,
            completedGeneration = 3,
            highWaterId = 40,
            cursorDate = 30,
            cursorId = 3,
            baselineHighWaterId = 20,
            updatedAt = 1,
        ))

        assertEquals(1, dao.request(2))
        val requested = dao.get()!!
        assertEquals(5, requested.requestedGeneration)
        assertEquals(3, requested.completedGeneration)
        assertEquals(40, requested.highWaterId)
        assertEquals(40, requested.baselineHighWaterId)
        assertEquals(null, requested.cursorDate)
        assertEquals(null, requested.cursorId)
    }

    @Test fun reconcileDeletionRemovesOnlyMissingIndex() = kotlinx.coroutines.runBlocking {
        val dao = database.messageIndexDao()
        dao.upsertAll(listOf(
            MessageIndex(1, 1, 1, null, null, SlotMappingStatus.UNKNOWN_NO_SUB_ID, 1, 1),
            MessageIndex(2, 2, 1, null, null, SlotMappingStatus.UNKNOWN_NO_SUB_ID, 1, 1),
        ))

        assertEquals(1, dao.deleteById(1))
        assertEquals(listOf(2L), dao.all().map { it.sourceMessageId })
    }

    @Test fun automatedRescanNeverOverwritesHumanClassification() = kotlinx.coroutines.runBlocking {
        val dao = database.classificationDao()
        dao.insertAutomated(listOf(MessageClassification(7, true, "human-key", "虚构甲", "LABELED", "HUMAN", true, 1)))

        dao.upsertAutomated(listOf(MessageClassification(7, false, null, null, "NON_OTP", "RULE", false, 2)))

        val preserved = dao.get(7)!!
        assertEquals("human-key", preserved.platformKey)
        assertEquals("虚构甲", preserved.platformDisplayName)
        assertTrue(preserved.isHumanConfirmed)
        assertEquals("HUMAN", preserved.source)
    }
}
